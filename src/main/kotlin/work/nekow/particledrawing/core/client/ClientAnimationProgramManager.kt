package work.nekow.particledrawing.core.client

import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import work.nekow.particledrawing.ParticleDrawing
import work.nekow.particledrawing.animation.script.CompiledFunction
import work.nekow.particledrawing.animation.script.GetterRewriter
import work.nekow.particledrawing.animation.script.InputKey
import work.nekow.particledrawing.animation.script.compileFunctionObject
import work.nekow.particledrawing.animation.script.evaluate
import work.nekow.particledrawing.api.EntityProp
import work.nekow.particledrawing.api.Orient
import work.nekow.particledrawing.api.WorldProp
import work.nekow.particledrawing.animation.program.AnimInstruction
import work.nekow.particledrawing.animation.program.EntityBinding
import work.nekow.particledrawing.animation.program.PivotRef
import work.nekow.particledrawing.animation.program.finiteDurationMs
import work.nekow.particledrawing.core.DebugFlags
import work.nekow.particledrawing.core.easing.EasingType
import work.nekow.particledrawing.core.network.ProgramCompletePayload
import work.nekow.particledrawing.util.AttachMath
import work.nekow.particledrawing.util.rotateAround
import net.neoforged.neoforge.client.network.ClientPacketDistributor
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

// 客户端动画程序运行时：解释 AnimInstruction 指令流并直写渲染。
// 组级状态（pivot/pathOffset/scaleMul/pulseMul/fade 因子）+ 粒子级相对轴心的偏移 rel。
// 旋转按「每帧增量」累加到 rel 上，所以 spin 与 rotate 互相叠加（见 RotationSlot）；表达式模式每 tick×每粒子求值，输出世界绝对坐标。
@EventBusSubscriber(modid = ParticleDrawing.MODID, value = [Dist.CLIENT])
internal object ClientAnimationProgramManager {

    private val LOGGER = com.mojang.logging.LogUtils.getLogger()

    // —— 实体索引 ——

    private val entityByUuid = ConcurrentHashMap<UUID, Entity>()

    @SubscribeEvent
    @JvmStatic
    fun onEntityJoin(ev: net.neoforged.neoforge.event.entity.EntityJoinLevelEvent) {
        if (ev.level.isClientSide) entityByUuid[ev.entity.uuid] = ev.entity
    }

    @SubscribeEvent
    @JvmStatic
    fun onEntityLeave(ev: net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent) {
        if (ev.level.isClientSide) entityByUuid.remove(ev.entity.uuid, ev.entity)
    }

    // 找实体：本地玩家 → 缓存（校验存活与维度，防冻结坐标）→ 全局注册表直查 → 渲染列表兜底。
    // 原始粒子的实体锚点也走这里，保证两条路径对「实体身份」的解析完全一致。
    internal fun findEntity(uuid: UUID): Entity? {
        val mc = Minecraft.getInstance()
        mc.player?.let { if (it.uuid == uuid) return it }
        entityByUuid[uuid]?.let { cached ->
            if (cached.isAlive && cached.level() === mc.level) return cached
            LOGGER.warn("[ParticleDrawing] 实体缓存失效，重新解析: {}", uuid)
            entityByUuid.remove(uuid, cached)
        }
        val level = mc.level ?: return null
        // 实体全局注册表 O(1) 直查（覆盖尚未进入渲染列表的实体，消除跟踪间隙）
        level.getEntity(uuid)?.let { entityByUuid[uuid] = it; return it }
        for (e in level.entitiesForRendering()) {
            if (e.uuid == uuid) { entityByUuid[uuid] = e; return e }
        }
        return null
    }

    // —— 数据模型 ——

    /** 粒子静态基态。 */
    private class PState(
        val baseR: Float, val baseG: Float, val baseB: Float, val baseA: Float,
        val baseScale: Float,
        var rel: Vec3,
    )

    /** 指令槽位：首用快照。 */
    private class Slot(val ins: AnimInstruction) {
        var applied = false
        var snapPathOffset: Vec3 = Vec3.ZERO          // 平移类：应用前组位移
        val rotation = RotationSlot()                 // 旋转类：角度累加账本
        val scale = ScaleLedger()                     // 缩放类：执行起点倍率账本
        var driftScale = 0f                           // MoveEach：本帧的「沿各自偏移方向」倍率
    }

    /** 一个正在渐变的变量：从 [from] 缓动到 [to]，[startMs] 为首帧（程序相对毫秒）。 */
    private class VarEase(
        val from: Double,
        val to: Double,
        val durationMs: Int,
        val easing: EasingType,
        var startMs: Long = -1L,
    )

    private class Program(
        /** 程序 id（= 粒子组 id）：上报完成时要用它。 */
        val animationId: UUID,
        val particleIds: List<UUID>,
        val anchorOffset: Long,
        /** 下发时刻的服务端 gameTime：把绝对时钟换算为「程序相对 tick」的原点。 */
        val startAnchor: Long,
        val states: MutableMap<UUID, PState>,
    ) {
        val slots = ArrayList<Slot>()
        // 轴心
        var pivotFixed: Vec3 = Vec3.ZERO
        var pivotEntity: EntityBinding? = null
        var pivotEntityOffset: Vec3 = Vec3.ZERO
        var pivotEntityLocal = false

        // 组级动画状态
        var pathOffset: Vec3 = Vec3.ZERO
        var pulseMul = 1f
        var scaleMul = 1f                              // ScaleBy 当前倍率（作用于视觉尺寸与粒子到轴心的距离）
        var recolor: Recolor? = null
        var fadeInStart = -1L; var fadeInDur = 0; var fadeInEase = EasingType.EASE_OUT
        var fadeOutStart = -1L; var fadeOutDur = 0; var fadeOutEase = EasingType.EASE_IN
        var continuousFrozenMs: Long? = null

        // 实体注册表 / 变量 / 表达式
        val entityBindings = ArrayList<EntityBinding>()
        val vars = LinkedHashMap<String, Double>()
        var expressionCode: String? = null
        var expressionStartMs = 0L
        var compiled: CompiledFunction? = null

        // 可移动轴心（BindPivot(Movable) 绑定 + ProgramAnchorPayload 逐 tick 更新）
        var movableAnchor = false
        var movableOrientLocal = true
        var anchorPos: Vec3 = Vec3.ZERO
        var anchorPrev: Vec3 = Vec3.ZERO
        var anchorVelocity: Vec3 = Vec3.ZERO
        var anchorSampleNanos: Long = 0L
        /** 本 tick 是否让粒子位置跳变（瞬移/断流刚恢复）：避免在两个远点之间扫出一条假轨迹。 */
        var anchorSnapPending = false

        /** 有限指令的最晚结束时刻（程序相对毫秒）；-1 = 这是一段没有终点的编排。 */
        var endMs: Long = -1L

        /** 是否已经把「跑完了」上报给服务端（每次时间轴被追加延长后会重置，可再报一次）。 */
        var completionReported = false

        /** 渐变中的变量：名字 → 目标与进度（见 `setVariableEased`）。 */
        val varEases = HashMap<String, VarEase>()

        /** 名字 -> 注册序号（公式 getter 参数解析用）。 */
        val handleIndexByName: Map<String, Int> by lazy {
            entityBindings.withIndex().associate { it.value.handle to it.index }
        }

        // 被动输入：编译期发现的「合成变量名 → 输入键」需求清单。
        // 每 tick 只采样被引用的值；速度按相邻 tick 位置差分，同 tick 内不重复计差。
        var extNames: Array<String> = emptyArray()
        var requiredKeys: List<InputKey> = emptyList()
        var latestInputs: Map<String, Double> = emptyMap()
        val prevPos = HashMap<UUID, Vec3>()
        var lastSampleGameTime = Long.MIN_VALUE

        /** 输入实体连续解析失败的采样次数（诊断用，成功解析即清零）。 */
        var missStreak = 0

        // 求值缓冲
        var extVals = DoubleArray(0)
        var regs = DoubleArray(0)
        var stack = DoubleArray(0)
        val out = DoubleArray(8)
    }

    private val programs = ConcurrentHashMap<UUID, Program>()

    // —— 协议入口 ——

    fun arm(
        programId: UUID,
        particleIds: List<UUID>,
        anchorGameTime: Long,
        initialPivot: Vec3,
        entitiesIn: List<EntityBinding>,
        varsIn: Map<String, Double>,
        instructions: List<AnimInstruction>,
    ) {
        val level = Minecraft.getInstance().level
        val p = Program(programId, particleIds, anchorGameTime - (level?.gameTime ?: 0L), anchorGameTime, HashMap())
        p.pivotFixed = initialPivot
        p.entityBindings.addAll(entitiesIn)
        for ((k, v) in varsIn) p.vars[k] = v

        ClientParticleEngine.instance()?.let { engine ->
            for (id in particleIds) {
                val s = engine.snapshot(id) ?: continue
                p.states[id] = PState(s.r, s.g, s.b, s.a, s.scale, s.position.subtract(initialPivot))
            }
        }
        if (p.states.isEmpty()) {
            LOGGER.warn(
                "[ParticleDrawing] program {} armed with zero known particles ({} ids); client spawn packets missing?",
                programId, particleIds.size,
            )
        } else if (verboseLogging()) {
            LOGGER.info(
                "[ParticleDrawing] program {} armed: {} particles, {} instructions, anchorOffset={}",
                programId, p.states.size, p.slots.size + (if (p.expressionCode != null) 1 else 0), p.anchorOffset,
            )
        } else {
            LOGGER.debug(
                "[ParticleDrawing] program {} armed: {} particles, {} instructions, anchorOffset={}",
                programId, p.states.size, p.slots.size + (if (p.expressionCode != null) 1 else 0), p.anchorOffset,
            )
        }
        for (ins in instructions) addInstruction(p, ins)
        programs[programId] = p
    }

    /**
     * arm 日志是否走 INFO（默认 false = DEBUG）：
     * 生存模式实战里一次受击会 arm 几十个组，INFO 会把日志刷爆；排查时用
     * [work.nekow.particledrawing.api.ParticleManager.setDebugLogging] 或客户端配置打开。
     */
    private fun verboseLogging(): Boolean =
        DebugFlags.verboseProgramLogging ||
            work.nekow.particledrawing.config.ParticleDrawingConfig.CLIENT.debugProgramLogging.get()

    fun append(programId: UUID, instructions: List<AnimInstruction>) {
        val p = programs[programId] ?: return
        for (ins in instructions) addInstruction(p, ins)
    }

    /**
     * 热更程序变量：value 为公式，求值环境 = 其余变量 + 被动输入当前值。
     * 公式里的 get_* 调用先重写为合成变量再求值；未知名在此处记日志并放弃本次热更。
     */
    fun setVariable(programId: UUID, name: String, expr: String) {
        val p = programs[programId] ?: return
        if (name !in p.vars && name.length > 64) return
        val v = evaluateVar(p, expr) ?: return
        val hadCode = p.expressionCode != null
        // 立即赋值取消同名的渐变（后到的指令说了算）
        p.varEases.remove(name)
        p.vars[name] = v
        // 变量名集合可能扩大：表达式指令的 externals 布局需随之重建
        if (hadCode) recompileExpression(p) else prepareExpressionBuffers(p)
    }

    /**
     * 求一条变量公式的值：公式里的 get_* 先重写为合成变量，环境 = 其余变量 + 被动输入当前值。
     * 未知名/解析失败记日志并返回 null（本次热更放弃）。
     */
    private fun evaluateVar(p: Program, expr: String): Double? {
        val rw = try {
            GetterRewriter.rewrite(expr, p.handleIndexByName, p.entityBindings.size)
        } catch (e: IllegalArgumentException) {
            LOGGER.warn(
                "[ParticleDrawing] setVariable getter 解析失败: {}", e.message,
            )
            return null
        }
        val scope = HashMap<String, Any>(p.vars)
        scope.putAll(p.latestInputs)
        return try {
            evaluate(rw.code, scope)
        } catch (_: Exception) {
            null
        }
    }

    /** 每 tick 推进渐变中的变量（到点即从表里摘掉，值停在目标上）。 */
    private fun applyVarEases(p: Program, now: Long) {
        if (p.varEases.isEmpty()) return
        val it = p.varEases.entries.iterator()
        while (it.hasNext()) {
            val (name, ease) = it.next()
            if (ease.startMs < 0L) ease.startMs = now
            val k = progress(now - ease.startMs, ease.durationMs)
            p.vars[name] = ease.from + (ease.to - ease.from) * eased(ease.easing, k).toDouble()
            if (k >= 1f) it.remove()
        }
    }

    /**
     * 某个变量此刻的值：正在渐变就**按当前程序时钟求旧曲线的值**（不是上一 tick 的快照），
     * 否则取静态快照。热更重定向时用它当新起点，连续重设目标才不会一轮比一轮落后。
     */
    private fun currentVarValue(p: Program, name: String, now: Long): Double? {
        val ease = p.varEases[name] ?: return p.vars[name]
        if (ease.startMs < 0L) return p.vars[name]
        val k = progress(now - ease.startMs, ease.durationMs)
        return ease.from + (ease.to - ease.from) * eased(ease.easing, k).toDouble()
    }

    /** 程序相对毫秒的当前值（按客户端的 gameTime 与程序时钟锚点算）。 */
    private fun programNowMs(p: Program): Long? {
        val level = Minecraft.getInstance().level ?: return null
        return (level.gameTime + p.anchorOffset - p.startAnchor) * 50
    }

    fun stop(programId: UUID, destroyParticles: Boolean) {
        val p = programs.remove(programId) ?: return
        if (destroyParticles) ClientParticleEngine.instance()?.destroyParticles(p.particleIds.toTypedArray())
    }

    /** 维度卸载 / 断线清理。 */
    @JvmStatic
    fun clearAll() { programs.clear(); entityByUuid.clear() }

    private fun addInstruction(p: Program, ins: AnimInstruction) {
        if (ins is AnimInstruction.Expression) {
            // 表达式唯一化：后到覆盖先到
            p.expressionCode = ins.code
            p.expressionStartMs = ins.startMs.toLong()
            recompileExpression(p)
            return
        }
        if (ins is AnimInstruction.BindPivot && ins.pivot is PivotRef.Movable) {
            // 可移动轴心：绑定一次，位置之后只走 ProgramAnchorPayload
            val ref = ins.pivot
            p.movableAnchor = true
            p.movableOrientLocal = ref.orient == Orient.VELOCITY
            p.anchorPos = ref.pos
            p.anchorPrev = ref.pos
            p.anchorVelocity = ref.velocity
            p.anchorSampleNanos = System.nanoTime()
        }
        // 时间轴被延长就重新允许上报（长寿组运行期追加一段动画后同样能报「这回也跑完了」）
        ins.finiteDurationMs()?.let { duration ->
            val end = ins.startMs.toLong() + duration
            if (end > p.endMs) {
                p.endMs = end
                p.completionReported = false
            }
        }
        p.slots.add(Slot(ins))
        prepareExpressionBuffers(p)
    }

    /**
     * 有限指令全部跑完时向服务端上报一次（多给 1 tick 余量，等桥接粒子的插值端点收尾）。
     * 没有有限时长指令（只有 spin / 无限 pulse / 表达式）的程序不上报——它本来就没有终点。
     */
    private fun reportCompletionIfDue(p: Program, now: Long) {
        if (p.endMs < 0L || p.completionReported) return
        if (now < p.endMs + 50L) return
        p.completionReported = true
        ClientPacketDistributor.sendToServer(ProgramCompletePayload(p.animationId))
    }

    /**
     * 移动轴心的相邻样本（服务端每 tick 一条）：
     * 位置直接采用本 tick 的样本（渲染帧之间的插值由桥接粒子的 `xo/x` 完成，与 track 同相位）；
     * [prev] 用来判断瞬移与断流恢复——那两种情况按跳变处理，不扫出一条假轨迹。
     */
    fun applyAnchor(programId: UUID, prev: Vec3, current: Vec3, velocity: Vec3) {
        val p = programs[programId] ?: return
        val now = System.nanoTime()
        val stale = p.anchorSampleNanos != 0L && now - p.anchorSampleNanos > MovableAnchorSamples.STALE_NANOS
        if (stale || current.distanceTo(prev) > MovableAnchorSamples.TELEPORT_LIMIT) {
            p.anchorSnapPending = true
        }
        p.anchorPrev = prev
        p.anchorPos = current
        if (velocity.lengthSqr() > 1e-12) p.anchorVelocity = velocity
        p.anchorSampleNanos = now
    }

    /**
     * 热更变量并渐变到目标值：先按与 [setVariable] 相同的环境求出目标，
     * 再让客户端在 [durationMs] 内从**当前值**缓动过去（空间端点因此是扫过去的，不是瞬移）。
     */
    fun setVariableEased(programId: UUID, name: String, expr: String, durationMs: Int, easing: EasingType) {
        val p = programs[programId] ?: return
        val target = evaluateVar(p, expr) ?: return
        if (durationMs <= 0) {
            p.varEases.remove(name)
            p.vars[name] = target
        } else {
            // 重定向：起点取「旧渐变按当前时钟的值」，不是上一 tick 的快照 ——
            // 连续热更时每轮都从真正的位置接着走，不会越落越远。
            val now = programNowMs(p)
            val from = if (now != null) currentVarValue(p, name, now) ?: target else p.vars[name] ?: target
            p.vars[name] = from
            p.varEases[name] = VarEase(from, target, durationMs, easing)
        }
        // 变量名集合可能扩大：表达式指令的 externals 布局需随之重建
        if (p.expressionCode != null) recompileExpression(p) else prepareExpressionBuffers(p)
    }

    // —— 输入采样与编译缓冲 ——

    /**
     * 编译表达式：先把 get_* 调用重写为合成外部变量（同时发现输入需求），再走纯标量快路径。
     * 未知名/未登记句柄在此抛错——程序不生效并记日志（服务端绑定处无法预知公式语义）。
     */
    private fun recompileExpression(p: Program) {
        val code = p.expressionCode ?: return
        val rw = try {
            GetterRewriter.rewrite(code, p.handleIndexByName, p.entityBindings.size)
        } catch (e: IllegalArgumentException) {
            LOGGER.error(
                "[ParticleDrawing] 表达式编译失败（getter 解析）: {}", e.message,
            )
            p.compiled = null
            p.requiredKeys = emptyList()
            p.extNames = emptyArray()
            return
        }
        // externals 布局 = 合成输入名 + 程序变量名（变量值随每 tick 快照注入，公式可直接引用）
        val extAll = rw.extNames + p.vars.keys
        p.extNames = extAll.toTypedArray()
        p.requiredKeys = rw.keys
        p.compiled = compileFunctionObject(rw.code, emptyList(), extAll)
        prepareExpressionBuffers(p)
    }

    private fun prepareExpressionBuffers(p: Program) {
        if (p.expressionCode == null || p.compiled == null) return
        p.extVals = DoubleArray(p.extNames.size)
        val cf = p.compiled ?: return
        p.regs = cf.allocRegs()
        p.stack = cf.allocStack()
    }

    /** 按 [extNames] 名字序注入当前输入值；缺失值落 0——槽位永不错位。 */
    private fun fillExternal(p: Program) {
        val src = p.latestInputs
        for ((i, name) in p.extNames.withIndex()) p.extVals[i] = src[name] ?: 0.0
    }

    /**
     * 每 tick 刷新程序的被动输入快照（同名同 tick 只采一次）。
     * 只采样 [Program.requiredKeys] 引用的值；实体缺失时相关 getter 全部读 0；
     * 速度按相邻 tick 位置差分，首 tick 与闪现后为 0。
     */
    private fun refreshInputs(p: Program, gameTime: Long) {
        if (p.requiredKeys.isEmpty()) {
            // 无被动输入：快照仅含程序变量（公式引用变量靠它注入）
            p.latestInputs = if (p.vars.isEmpty()) emptyMap() else HashMap(p.vars)
            return
        }
        if (gameTime == p.lastSampleGameTime) return
        val level = Minecraft.getInstance().level ?: return
        p.lastSampleGameTime = gameTime

        // 先解析本程序引用到的实体并做速度差分（每实体一次）
        val usedIndices = HashSet<Int>()
        for (key in p.requiredKeys) if (key is InputKey.Entity) usedIndices.add(key.handleIndex)
        val entities = HashMap<Int, Entity?>()
        val vels = HashMap<Int, Vec3>()
        val newPrev = HashMap<UUID, Vec3>()
        val missing = ArrayList<String>()
        for (idx in usedIndices) {
            val binding = p.entityBindings.getOrNull(idx) ?: continue
            val e = findEntity(binding.uuid)
            if (e == null) missing.add(binding.handle)
            entities[idx] = e
            val pos = e?.position()
            if (pos != null) {
                newPrev[binding.uuid] = pos
                val prev = p.prevPos[binding.uuid]
                vels[idx] = if (prev != null) pos.subtract(prev) else Vec3.ZERO
            }
        }
        if (missing.isEmpty()) {
            p.missStreak = 0
        } else {
            p.missStreak++
            if (p.missStreak % 60 == 0) {
                LOGGER.warn(
                    "[ParticleDrawing] 程序输入实体连续 {} 次无法解析（相关 getter 读 0）: slots={}",
                    p.missStreak, missing,
                )
            }
        }
        p.prevPos.clear()
        p.prevPos.putAll(newPrev)

        val m = HashMap<String, Double>(p.extNames.size)
        for ((i, key) in p.requiredKeys.withIndex()) {
            val name = p.extNames.getOrNull(i) ?: continue
            m[name] = when (key) {
                is InputKey.Entity ->
                    sampleEntityProp(entities[key.handleIndex] ?: continue, key.prop, vels[key.handleIndex] ?: Vec3.ZERO)
                is InputKey.World -> sampleWorldProp(level, key.prop)
            }
        }
        m.putAll(p.vars) // 变量值并入快照，fillExternal 按名注入
        p.latestInputs = m
    }

    /** 实体属性取值（枚举穷举）；实体缺失由调用方短路为 0。 */
    private fun sampleEntityProp(e: Entity, prop: EntityProp, vel: Vec3): Double = when (prop) {
        EntityProp.X -> e.x
        EntityProp.Y -> e.y
        EntityProp.Z -> e.z
        EntityProp.POS -> 0.0 // 整取形态在重写期已展开为三分量，不会到达此处
        EntityProp.EXISTS -> 1.0
        EntityProp.YAW -> e.yRot.toDouble()
        EntityProp.PITCH -> e.xRot.toDouble()
        EntityProp.DIR_X -> e.getViewVector(1f).x
        EntityProp.DIR_Y -> e.getViewVector(1f).y
        EntityProp.DIR_Z -> e.getViewVector(1f).z
        EntityProp.VEL_X -> vel.x
        EntityProp.VEL_Y -> vel.y
        EntityProp.VEL_Z -> vel.z
        EntityProp.HP -> (e as? LivingEntity)?.health?.toDouble() ?: 0.0
        EntityProp.HP_MAX -> (e as? LivingEntity)?.maxHealth?.toDouble() ?: 0.0
        EntityProp.GROUND -> if (e.onGround()) 1.0 else 0.0
        EntityProp.SNEAKING -> if (e.isShiftKeyDown()) 1.0 else 0.0
        EntityProp.ON_FIRE -> if (e.isOnFire()) 1.0 else 0.0
        EntityProp.SWIMMING -> if (e.isSwimming()) 1.0 else 0.0
        EntityProp.SPRINTING -> if (e.isSprinting()) 1.0 else 0.0
    }

    /** 世界属性取值（26.2 时钟 API：getOverworldClockTime 即旧 day time 域）。 */
    private fun sampleWorldProp(level: net.minecraft.client.multiplayer.ClientLevel, prop: WorldProp): Double {
        val clock = level.overworldClockTime
        return when (prop) {
            WorldProp.DAY_TIME -> (clock % 24000L).toDouble()
            WorldProp.GAME_TIME -> clock.toDouble()
            WorldProp.RAIN -> level.getRainLevel(1f).toDouble()
            WorldProp.THUNDER -> level.getThunderLevel(1f).toDouble()
            WorldProp.MOON_PHASE -> (clock / 24000L % 8L + 8L).toDouble()
        }
    }

    // —— 每 tick 主循环 ——

    /** 由本地玩家 game tick 事件调用（真 20Hz、实体移动后）。 */
    @JvmStatic
    fun tick() {
        val level = Minecraft.getInstance().level ?: return
        val engine = ClientParticleEngine.instance() ?: return
        val nowClient = level.gameTime

        for ((programId, p) in programs.toList()) {
            // 统一到「程序相对毫秒」域：
            // clientGameTime + anchorOffset ≈ 服务端绝对 gameTime；再减程序起点、×50 = 相对毫秒。
            // 指令 startMs 与公式变量 t 均为该相对域，量纲一致、与存档时长无关。
            val now = (nowClient + p.anchorOffset - p.startAnchor) * 50
            // 先推进变量渐变、再形成表达式输入快照：否则公式读到的是上一 tick 的旧值，
            // 连续热更（每 tick 重设目标）会一直落后一拍，看起来「不收敛」。
            applyVarEases(p, now)
            refreshInputs(p, nowClient)

            if (p.expressionCode != null) {
                expressionFrame(p, engine, now)
                continue
            }
            sugarFrame(p, engine, now)
            reportCompletionIfDue(p, now)
        }
    }

    // —— 表达式模式 ——

    private fun expressionFrame(p: Program, engine: ClientParticleEngine, now: Long) {
        val cf = p.compiled ?: return
        if (p.regs.size != cf.regCount) prepareExpressionBuffers(p)
        val local = (now - p.expressionStartMs).coerceAtLeast(0).toDouble()
        fillExternal(p)

        val n = p.particleIds.size.toDouble()
        val fadeIn = fadeFactor(p.fadeInStart, p.fadeInDur, p.fadeInEase, now, default = 1f)
        val fadeOut = fadeFactor(p.fadeOutStart, p.fadeOutDur, p.fadeOutEase, now, default = 0f)

        for ((index, uuid) in p.particleIds.withIndex()) {
            val st = p.states[uuid] ?: continue
            cf.eval(index.toDouble(), n, local, p.regs, p.stack, p.extVals)
            val o = p.out
            readAttrs(p.regs, o)
            val alpha = (o[3].toFloat() * fadeIn * (1f - fadeOut)).coerceIn(0f, 1f)
            val scl = (o[4].toFloat() * st.baseScale).coerceAtLeast(0f)
            engine.applyProgramFrame(
                uuid, Vec3(o[0], o[1], o[2]),
                o[5].toFloat(), o[6].toFloat(), o[7].toFloat(),
                alpha, scl,
            )
        }
    }

    private fun readAttrs(regs: DoubleArray, out: DoubleArray) {
        out[0] = regs[3]; out[1] = regs[4]; out[2] = regs[5]   // x y z
        out[3] = regs[9]                                        // a
        out[4] = regs[13]                                       // sc
        out[5] = regs[6]; out[6] = regs[7]; out[7] = regs[8]   // r g b
    }

    // —— 结构化糖指令模式 ——

    private fun sugarFrame(p: Program, engine: ClientParticleEngine, now: Long) {
        val pivot = resolvePivot(p) ?: return

        for (slot in p.slots) applySlot(p, slot, now)

        // 逐成员各自方向的漂移倍率：多条 MoveEach 叠加（与旋转/缩放的累加口径一致）
        var driftScale = 0f
        for (slot in p.slots) driftScale += slot.driftScale

        val fadeIn = fadeFactor(p.fadeInStart, p.fadeInDur, p.fadeInEase, now, default = 1f)
        val fadeOut = fadeFactor(p.fadeOutStart, p.fadeOutDur, p.fadeOutEase, now, default = 0f)
        val rc = p.recolor
        val kR = if (rc == null) 1f else eased(rc.ease, progress(now - rc.startMs, rc.durationMs))

        // 轴心瞬移/断流恢复：这一帧让位置跳变，不在新旧两点之间扫出一条假轨迹
        val anchorSnap = p.anchorSnapPending
        p.anchorSnapPending = false

        for ((uuid, st) in p.states) {
            val r: Float; val g: Float; val b: Float; val aBase: Float
            if (rc != null) {
                val from = rc.from[uuid]
                if (from != null) {
                    r = lerp(from[0], rc.r, kR); g = lerp(from[1], rc.g, kR)
                    b = lerp(from[2], rc.b, kR); aBase = lerp(from[3], rc.a, kR)
                } else { r = st.baseR; g = st.baseG; b = st.baseB; aBase = st.baseA }
            } else { r = st.baseR; g = st.baseG; b = st.baseB; aBase = st.baseA }

            val alpha = (aBase * fadeIn * (1f - fadeOut)).coerceIn(0f, 1f)
            // 倍率可以到 0：0 = 完全收起、什么都不画（不是钳到一个「很小但还看得见」的值）
            val scale = (st.baseScale * p.scaleMul * p.pulseMul).coerceAtLeast(0f)
            val local = radialRel(st.rel, p.scaleMul, p.pulseMul)
            // 沿各自偏移方向外移：方向用**当前**的 rel（跟着旋转一起转），幅度 = 倍率 × 偏移长度
            val shifted = if (driftScale == 0f) local else local.add(st.rel.scale(driftScale.toDouble()))
            engine.applyProgramFrame(
                uuid, pivot.apply(p.pathOffset.add(shifted)),
                r, g, b, alpha, scale, anchorSnap,
            )
        }
    }

    /** 组级重着色的首用快照与目标。 */
    private class Recolor(
        val startMs: Long, val durationMs: Int, val ease: EasingType,
        val r: Float, val g: Float, val b: Float, val a: Float,
        val from: Map<UUID, FloatArray>,
    )

    /**
     * 轴心解算结果：固定轴心只给世界坐标；跟随实体时记下实体位置、偏移与朝向，
     * local 模式下把组内相对坐标一起按实体朝向旋转（[AttachMath] 与实体锚点同一套约定）。
     */
    private class PivotFrame(
        val pos: Vec3,
        val offset: Vec3,
        val yawDeg: Float,
        val pitchDeg: Float,
        val local: Boolean,
    ) {
        /** 把组内相对坐标（pathOffset + rel）映射为世界坐标。 */
        fun apply(localVec: Vec3): Vec3 =
            if (local) AttachMath.resolve(pos, yawDeg, pitchDeg, offset.add(localVec), true)
            else pos.add(offset).add(localVec)
    }

    private fun resolvePivot(p: Program): PivotFrame? {
        // 可移动轴心：位置来自逐 tick 的样本；orient=VELOCITY 时整组随运动方向转向
        if (p.movableAnchor) {
            if (!p.movableOrientLocal) return PivotFrame(p.anchorPos, Vec3.ZERO, 0f, 0f, false)
            val yp = EffectAnchorResolver.yawPitchDegrees(p.anchorVelocity) ?: return PivotFrame(p.anchorPos, Vec3.ZERO, 0f, 0f, false)
            return PivotFrame(p.anchorPos, Vec3.ZERO, yp[0].toFloat(), yp[1].toFloat(), true)
        }
        val ch = p.pivotEntity ?: return PivotFrame(p.pivotFixed, Vec3.ZERO, 0f, 0f, false)
        val e = findEntity(ch.uuid) ?: return null
        return PivotFrame(e.position(), p.pivotEntityOffset, e.yRot, e.xRot, p.pivotEntityLocal)
    }

    private fun applySlot(p: Program, slot: Slot, now: Long) {
        val ins = slot.ins
        val start = ins.startMs.toLong()
        if (now < start) return

        if (!slot.applied) {
            slot.applied = true
            when (ins) {
                is AnimInstruction.Translate, is AnimInstruction.MovePath ->
                    slot.snapPathOffset = p.pathOffset
                else -> {}
            }
        }
        val local = now - start

        when (ins) {
            is AnimInstruction.BindPivot -> when (val ref = ins.pivot) {
                is PivotRef.Fixed -> {
                    p.pivotFixed = ref.pos; p.pivotEntity = null
                    p.pivotEntityOffset = Vec3.ZERO; p.pivotEntityLocal = false
                    p.movableAnchor = false
                }
                is PivotRef.FollowEntity -> {
                    p.pivotEntity = EntityBinding("__pivot__", ref.uuid)
                    p.pivotEntityOffset = ref.offset
                    p.pivotEntityLocal = ref.local
                    p.movableAnchor = false
                }
                // 可移动轴心：绑定状态在 addInstruction 里就位，位置只走 ProgramAnchorPayload
                is PivotRef.Movable -> Unit
            }

            is AnimInstruction.FadeIn -> { p.fadeInStart = start; p.fadeInDur = ins.durationMs; p.fadeInEase = ins.easing }
            is AnimInstruction.FadeOut -> { p.fadeOutStart = start; p.fadeOutDur = ins.durationMs; p.fadeOutEase = ins.easing }

            is AnimInstruction.Recolor -> {
                if (p.recolor == null) {
                    p.recolor = Recolor(start, ins.durationMs, ins.easing,
                        ins.r, ins.g, ins.b, ins.a,
                        p.states.mapValues { floatArrayOf(it.value.baseR, it.value.baseG, it.value.baseB, it.value.baseA) })
                }
            }

            is AnimInstruction.ScaleBy -> {
                val k = eased(ins.easing, progress(local, ins.durationMs))
                val target = slot.scale.begin(p.scaleMul, ins.ratio, absolute = false)
                p.scaleMul = slot.scale.valueAt(target, k)
            }

            is AnimInstruction.ScaleTo -> {
                val k = eased(ins.easing, progress(local, ins.durationMs))
                val target = slot.scale.begin(p.scaleMul, ins.target, absolute = true)
                p.scaleMul = slot.scale.valueAt(target, k)
            }

            is AnimInstruction.Translate -> {
                p.pathOffset = slot.snapPathOffset.add(ins.delta.scale(eased(ins.easing, progress(local, ins.durationMs)).toDouble()))
            }

            is AnimInstruction.RotateOnce -> {
                val angle = ins.radians * eased(ins.easing, progress(local, ins.durationMs))
                applyRotation(p, slot, ins.axis, angle)
            }

            is AnimInstruction.Spin -> {
                val effective = if (p.continuousFrozenMs != null)
                    (p.continuousFrozenMs!! - start).coerceAtLeast(0)
                else (now - start).coerceAtLeast(0)
                applyRotation(p, slot, ins.axis, ins.radiansPerMs * effective.toDouble())
            }

            is AnimInstruction.Pulse -> {
                if (p.continuousFrozenMs != null) return
                val half = ins.halfPeriodMs.coerceAtLeast(1)
                val phase = (local % (half * 2L)).toFloat() / half
                val tri = if (phase <= 1f) phase else 2f - phase
                p.pulseMul = 1f + (ins.peakRatio - 1f) * tri
            }

            is AnimInstruction.MovePath -> {
                val k = eased(ins.easing, progress(local, ins.durationMs))
                p.pathOffset = slot.snapPathOffset.add(samplePath(ins.points, k).subtract(ins.points.first()))
            }

            is AnimInstruction.StopContinuous ->
                if (p.continuousFrozenMs == null || p.continuousFrozenMs!! > now) p.continuousFrozenMs = now

            is AnimInstruction.MoveEach -> {
                slot.driftScale = ins.offsetScale * eased(ins.easing, progress(local, ins.durationMs))
            }

            is AnimInstruction.Expression -> {} // 表达式由 addInstruction 分流，不进入糖指令槽
        }
    }

    /**
     * 旋转增量落地：每帧只把「本指令应达到的总角度」与「已写入角度」之差叠加到各粒子 rel 上。
     * 于是多条旋转指令互相叠加（后一条不会把前一条的角度覆盖掉），一次性旋转的缓动按帧增量平滑推进。
     */
    private fun applyRotation(p: Program, slot: Slot, axis: Vec3, angleTotal: Double) {
        val delta = slot.rotation.take(angleTotal)
        if (delta == 0.0) return
        val nAxis = axis.normalize()
        for (st in p.states.values) st.rel = st.rel.rotateAround(nAxis, delta)
    }

    private fun samplePath(points: List<Vec3>, t: Float): Vec3 {
        if (points.isEmpty()) return Vec3.ZERO
        if (points.size == 1) return points[0]
        val ft = t.coerceIn(0f, 1f) * (points.size - 1)
        val i = floor(ft.toDouble()).toInt().coerceIn(0, points.size - 2)
        val f = ft - i
        val a = points[i]; val b = points[i + 1]
        return Vec3(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f, a.z + (b.z - a.z) * f)
    }

    private fun fadeFactor(start: Long, dur: Int, ease: EasingType, now: Long, default: Float): Float =
        if (start < 0) default else eased(ease, progress(now - start, dur))

    private fun progress(local: Long, durationMs: Int): Float {
        if (durationMs <= 0) return 1f
        return (local.toFloat() / durationMs).coerceIn(0f, 1f)
    }

    private fun eased(easing: EasingType, t: Float): Float = easing.evaluate(t.coerceIn(0f, 1f))

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
