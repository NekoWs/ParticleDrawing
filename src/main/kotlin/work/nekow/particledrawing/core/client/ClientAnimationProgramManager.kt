package work.nekow.particledrawing.core.client

import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import work.nekow.particledrawing.ParticleDrawing
import work.nekow.particledrawing.animation.expr.CompiledFunction
import work.nekow.particledrawing.animation.expr.GetterRewriter
import work.nekow.particledrawing.animation.expr.InputKey
import work.nekow.particledrawing.animation.expr.compileFunctionObject
import work.nekow.particledrawing.animation.program.AnimInstruction
import work.nekow.particledrawing.animation.program.InputChannel
import work.nekow.particledrawing.animation.program.PivotRef
import work.nekow.particledrawing.core.easing.EasingType
import work.nekow.particledrawing.util.rotateAround
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

/**
 * 客户端动画程序运行时：解释 [AnimInstruction] 指令流并直写渲染。
 *
 * 状态模型：
 * - **组级**：pivot（固定坐标或实体绑定）、pathOffset（平移累积）、pulseMul、
 *   fadeIn/fadeOut 因子参数、continuousFrozenTick（停转时刻）、终极公式模式；
 * - **粒子级**：baseColor/baseScale（arm 快照）+ rel（相对 pivot 偏移，旋转的作用对象）；
 * - 旋转类指令首次应用时快照各粒子 rel，此后每 tick 由快照 + 总角度**重算** rel——
 *   幂等、支持中途追加、无累积误差。
 *
 * [AnimInstruction.EvalParticle] 为终极模式：整段函数对象代码每 tick×每粒子求值，
 * 输出世界绝对坐标；环境含 i/n/t、全套数学函数、实体通道变量（bindInput）、程序变量。
 */
@EventBusSubscriber(modid = ParticleDrawing.MODID, value = [Dist.CLIENT])
internal object ClientAnimationProgramManager {

    /* ---------------- 实体索引 ---------------- */

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

    private fun findEntity(uuid: UUID): Entity? {
        entityByUuid[uuid]?.let { return it }
        val level = Minecraft.getInstance().level ?: return null
        for (e in level.entitiesForRendering()) {
            if (e.uuid == uuid) { entityByUuid[uuid] = e; return e }
        }
        return null
    }

    /* ---------------- 数据模型 ---------------- */

    /** 粒子静态基态。 */
    private class PState(
        val baseR: Float, val baseG: Float, val baseB: Float, val baseA: Float,
        val baseScale: Float,
        var rel: Vec3,
    )

    /** 指令槽位：首用快照。 */
    private class Slot(val ins: AnimInstruction) {
        var applied = false
        var snapRel: Map<UUID, Vec3>? = null          // 旋转类：应用前各粒子 rel
        var snapPathOffset: Vec3 = Vec3.ZERO          // 平移类：应用前组位移
    }

    private class Program(
        val particleIds: List<UUID>,
        val anchorOffset: Long,
        /** 下发时刻的服务端 gameTime：把绝对时钟换算为「程序相对 tick」的原点。 */
        val startAnchor: Long,
        val states: MutableMap<UUID, PState>,
    ) {
        val slots = ArrayList<Slot>()
        // 轴心
        var pivotFixed: Vec3 = Vec3.ZERO
        var pivotEntity: InputChannel? = null
        var pivotEntityOffset: Vec3 = Vec3.ZERO

        // 组级动画状态
        var pathOffset: Vec3 = Vec3.ZERO
        var pulseMul = 1f
        var scaleMul = 1f                              // ScaleBy 累积倍率
        var recolor: Recolor? = null
        var fadeInStart = -1L; var fadeInDur = 0; var fadeInEase = EasingType.EASE_OUT
        var fadeOutStart = -1L; var fadeOutDur = 0; var fadeOutEase = EasingType.EASE_IN
        var continuousFrozenTick: Long? = null

        // 实体注册表 / 变量 / 终极公式
        val entityBindings = ArrayList<InputChannel>()
        val vars = LinkedHashMap<String, Double>()
        var evalCode: String? = null
        var evalStartTick = 0L
        var compiled: CompiledFunction? = null

        /** 名字 -> 注册序号（公式 getter 参数解析用）。 */
        val handleIndexByName: Map<String, Int> by lazy {
            entityBindings.withIndex().associate { it.value.slot to it.index }
        }

        // 被动输入：编译期发现的「合成变量名 → 输入键」需求清单。
        // 每 tick 只采样被引用的值；速度按相邻 tick 位置差分，同 tick 内不重复计差。
        var extNames: Array<String> = emptyArray()
        var requiredKeys: List<InputKey> = emptyList()
        var latestInputs: Map<String, Double> = emptyMap()
        val prevPos = HashMap<UUID, Vec3>()
        var lastSampleGameTime = Long.MIN_VALUE

        // 求值缓冲
        var extVals = DoubleArray(0)
        var regs = DoubleArray(0)
        var stack = DoubleArray(0)
        val out = DoubleArray(8)
    }

    private val programs = ConcurrentHashMap<UUID, Program>()

    /* ---------------- 协议入口 ---------------- */

    fun arm(
        programId: UUID,
        particleIds: List<UUID>,
        anchorGameTime: Long,
        initialPivot: Vec3,
        entitiesIn: List<InputChannel>,
        varsIn: Map<String, Double>,
        instructions: List<AnimInstruction>,
    ) {
        val level = Minecraft.getInstance().level
        val p = Program(particleIds, anchorGameTime - (level?.gameTime ?: 0L), anchorGameTime, HashMap())
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
            com.mojang.logging.LogUtils.getLogger().warn(
                "[ParticleDrawing] program {} armed with zero known particles ({} ids); client spawn packets missing?",
                programId, particleIds.size,
            )
        } else {
            com.mojang.logging.LogUtils.getLogger().info(
                "[ParticleDrawing] program {} armed: {} particles, {} instructions, anchorOffset={}",
                programId, p.states.size, p.slots.size + (if (p.evalCode != null) 1 else 0), p.anchorOffset,
            )
        }
        for (ins in instructions) addInstruction(p, ins)
        programs[programId] = p
    }

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
        val rw = try {
            GetterRewriter.rewrite(expr, p.handleIndexByName, p.entityBindings.size)
        } catch (e: IllegalArgumentException) {
            com.mojang.logging.LogUtils.getLogger().warn(
                "[ParticleDrawing] setVariable {}:{} getter 解析失败: {}", programId, name, e.message,
            )
            return
        }
        val scope = HashMap<String, Any>(p.vars)
        scope.putAll(p.latestInputs)
        val v = try {
            work.nekow.particledrawing.animation.expr.ExpressionEvaluator.evaluate(rw.code, scope) as? Double
        } catch (_: Exception) { null } ?: return
        val hadCode = p.evalCode != null
        p.vars[name] = v
        // 变量名集合可能扩大：终极公式的 externals 布局需随之重建
        if (hadCode) recompileEval(p) else prepareEvalBuffers(p)
    }

    fun stop(programId: UUID, destroyParticles: Boolean) {
        val p = programs.remove(programId) ?: return
        if (destroyParticles) ClientParticleEngine.instance()?.destroyParticles(p.particleIds.toTypedArray())
    }

    /** 维度卸载 / 断线清理。 */
    @JvmStatic
    fun clearAll() { programs.clear(); entityByUuid.clear() }

    private fun addInstruction(p: Program, ins: AnimInstruction) {
        if (ins is AnimInstruction.EvalParticle) {
            // 终极模式唯一化：后到覆盖先到
            p.evalCode = ins.code
            p.evalStartTick = ins.startTick.toLong()
            recompileEval(p)
            return
        }
        p.slots.add(Slot(ins))
        prepareEvalBuffers(p)
    }

    /* ---------------- 输入采样与编译缓冲 ---------------- */

    /**
     * 编译终极公式：先把 get_* 调用重写为合成外部变量（同时发现输入需求），再走纯标量快路径。
     * 未知名/未登记句柄在此抛错——程序不生效并记日志（服务端绑定处无法预知公式语义）。
     */
    private fun recompileEval(p: Program) {
        val code = p.evalCode ?: return
        val rw = try {
            GetterRewriter.rewrite(code, p.handleIndexByName, p.entityBindings.size)
        } catch (e: IllegalArgumentException) {
            com.mojang.logging.LogUtils.getLogger().error(
                "[ParticleDrawing] perParticle 公式编译失败（getter 解析）: {}", e.message,
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
        prepareEvalBuffers(p)
    }

    private fun prepareEvalBuffers(p: Program) {
        if (p.evalCode == null || p.compiled == null) return
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
        for (idx in usedIndices) {
            val binding = p.entityBindings.getOrNull(idx) ?: continue
            val e = findEntity(binding.uuid)
            entities[idx] = e
            val pos = e?.position()
            if (pos != null) {
                newPrev[binding.uuid] = pos
                val prev = p.prevPos[binding.uuid]
                vels[idx] = if (prev != null) pos.subtract(prev) else Vec3.ZERO
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

    /** 实体属性取值（prop 字面量与 expr/GetterProps.ENTITY 对齐）；实体缺失由调用方短路为 0。 */
    private fun sampleEntityProp(e: Entity, prop: String, vel: Vec3): Double = when (prop) {
        "x" -> e.x
        "y" -> e.y
        "z" -> e.z
        "exists" -> 1.0
        "yaw" -> e.yRot.toDouble()
        "pitch" -> e.xRot.toDouble()
        "dirx" -> e.getViewVector(1f).x
        "diry" -> e.getViewVector(1f).y
        "dirz" -> e.getViewVector(1f).z
        "vx" -> vel.x
        "vy" -> vel.y
        "vz" -> vel.z
        "hp" -> (e as? LivingEntity)?.health?.toDouble() ?: 0.0
        "hp_max" -> (e as? LivingEntity)?.maxHealth?.toDouble() ?: 0.0
        "ground" -> if (e.onGround()) 1.0 else 0.0
        "sneaking" -> if (e.isShiftKeyDown()) 1.0 else 0.0
        "on_fire" -> if (e.isOnFire()) 1.0 else 0.0
        "swimming" -> if (e.isSwimming()) 1.0 else 0.0
        "sprinting" -> if (e.isSprinting()) 1.0 else 0.0
        else -> 0.0
    }

    /** 世界属性取值（26.2 时钟 API：getOverworldClockTime 即旧 day time 域）。 */
    private fun sampleWorldProp(level: net.minecraft.client.multiplayer.ClientLevel, prop: String): Double {
        val clock = level.overworldClockTime
        return when (prop) {
            "day_time" -> (clock % 24000L).toDouble()
            "game_time" -> clock.toDouble()
            "rain" -> level.getRainLevel(1f).toDouble()
            "thunder" -> level.getThunderLevel(1f).toDouble()
            "moon_phase" -> (clock / 24000L % 8L + 8L).toDouble()
            else -> 0.0
        }
    }

    /* ---------------- 每 tick 主循环 ---------------- */

    /** 由客户端 tick 事件调用。 */
    @JvmStatic
    fun tick() {
        val level = Minecraft.getInstance().level ?: return
        val engine = ClientParticleEngine.instance() ?: return
        val nowClient = level.gameTime

        for ((programId, p) in programs.toList()) {
            // 统一到「程序相对 tick」域：
            // clientGameTime + anchorOffset ≈ 服务端绝对 gameTime；再减程序起点 = 相对时刻。
            // 指令 startTick 与公式变量 t 均为该相对域，量纲一致、与存档时长无关。
            val now = nowClient + p.anchorOffset - p.startAnchor
            refreshInputs(p, nowClient)

            if (p.evalCode != null) {
                evalFrame(p, engine, now)
                continue
            }
            sugarFrame(p, engine, now)
        }
    }

    /* ---------------- 终极公式模式 ---------------- */

    private fun evalFrame(p: Program, engine: ClientParticleEngine, now: Long) {
        val cf = p.compiled ?: return
        if (p.regs.size != cf.regCount) prepareEvalBuffers(p)
        val local = (now - p.evalStartTick).coerceAtLeast(0).toDouble()
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
            val scl = (o[4].toFloat() * st.baseScale).coerceAtLeast(0.001f)
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

    /* ---------------- 结构化糖指令模式 ---------------- */

    private fun sugarFrame(p: Program, engine: ClientParticleEngine, now: Long) {
        val pivot = resolvePivot(p) ?: return

        for (slot in p.slots) applySlot(p, slot, now)

        val fadeIn = fadeFactor(p.fadeInStart, p.fadeInDur, p.fadeInEase, now, default = 1f)
        val fadeOut = fadeFactor(p.fadeOutStart, p.fadeOutDur, p.fadeOutEase, now, default = 0f)
        val rc = p.recolor
        val kR = if (rc == null) 1f else eased(rc.ease, progress(now - rc.startTick, rc.durationTicks))

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
            val scale = (st.baseScale * p.scaleMul * p.pulseMul).coerceAtLeast(0.001f)
            engine.applyProgramFrame(uuid, pivot.add(p.pathOffset).add(st.rel), r, g, b, alpha, scale)
        }
    }

    /** 组级重着色的首用快照与目标。 */
    private class Recolor(
        val startTick: Long, val durationTicks: Int, val ease: EasingType,
        val r: Float, val g: Float, val b: Float, val a: Float,
        val from: Map<UUID, FloatArray>,
    )

    private fun resolvePivot(p: Program): Vec3? {
        val ch = p.pivotEntity ?: return p.pivotFixed
        val e = findEntity(ch.uuid) ?: return null
        return e.position().add(p.pivotEntityOffset)
    }

    private fun applySlot(p: Program, slot: Slot, now: Long) {
        val ins = slot.ins
        val start = ins.startTick.toLong()
        if (now < start) return

        if (!slot.applied) {
            slot.applied = true
            when (ins) {
                is AnimInstruction.RotateOnce, is AnimInstruction.Spin ->
                    slot.snapRel = p.states.mapValues { it.value.rel }
                is AnimInstruction.Translate, is AnimInstruction.MovePath ->
                    slot.snapPathOffset = p.pathOffset
                else -> {}
            }
        }
        val local = now - start

        when (ins) {
            is AnimInstruction.BindPivot -> when (val ref = ins.pivot) {
                is PivotRef.Fixed -> { p.pivotFixed = ref.pos; p.pivotEntity = null; p.pivotEntityOffset = Vec3.ZERO }
                is PivotRef.FollowEntity -> { p.pivotEntity = InputChannel("__pivot__", ref.uuid); p.pivotEntityOffset = ref.offset }
            }

            is AnimInstruction.FadeIn -> { p.fadeInStart = start; p.fadeInDur = ins.durationTicks; p.fadeInEase = ins.easing }
            is AnimInstruction.FadeOut -> { p.fadeOutStart = start; p.fadeOutDur = ins.durationTicks; p.fadeOutEase = ins.easing }

            is AnimInstruction.Recolor -> {
                if (p.recolor == null) {
                    p.recolor = Recolor(start, ins.durationTicks, ins.easing,
                        ins.r, ins.g, ins.b, ins.a,
                        p.states.mapValues { floatArrayOf(it.value.baseR, it.value.baseG, it.value.baseB, it.value.baseA) })
                }
            }

            is AnimInstruction.ScaleBy -> {
                val k = eased(ins.easing, progress(local, ins.durationTicks))
                p.scaleMul = 1f + (ins.ratio - 1f) * k
            }

            is AnimInstruction.Translate -> {
                p.pathOffset = slot.snapPathOffset.add(ins.delta.scale(eased(ins.easing, progress(local, ins.durationTicks)).toDouble()))
            }

            is AnimInstruction.RotateOnce -> {
                val angle = ins.radians * eased(ins.easing, progress(local, ins.durationTicks))
                rotateToSnapshot(p, slot, ins.axis, angle)
            }

            is AnimInstruction.Spin -> {
                val effective = if (p.continuousFrozenTick != null)
                    (p.continuousFrozenTick!! - start).coerceAtLeast(0)
                else (now - start).coerceAtLeast(0)
                rotateToSnapshot(p, slot, ins.axis, ins.radiansPerTick * effective.toDouble())
            }

            is AnimInstruction.Pulse -> {
                if (p.continuousFrozenTick != null) return
                val half = ins.halfPeriodTicks.coerceAtLeast(1)
                val phase = (local % (half * 2L)).toFloat() / half
                val tri = if (phase <= 1f) phase else 2f - phase
                p.pulseMul = 1f + (ins.peakRatio - 1f) * tri
            }

            is AnimInstruction.MovePath -> {
                val k = eased(ins.easing, progress(local, ins.durationTicks))
                p.pathOffset = slot.snapPathOffset.add(samplePath(ins.points, k).subtract(ins.points.first()))
            }

            is AnimInstruction.StopContinuous ->
                if (p.continuousFrozenTick == null || p.continuousFrozenTick!! > now) p.continuousFrozenTick = now

            is AnimInstruction.EvalParticle -> {} // 终极模式由 addInstruction 分流，不进入糖指令槽
        }
    }

    private fun rotateToSnapshot(p: Program, slot: Slot, axis: Vec3, angleTotal: Double) {
        val snaps = slot.snapRel ?: return
        val nAxis = axis.normalize()
        for ((uuid, base) in snaps) {
            val st = p.states[uuid] ?: continue
            st.rel = base.rotateAround(nAxis, angleTotal)
        }
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

    private fun progress(local: Long, durationTicks: Int): Float {
        if (durationTicks <= 0) return 1f
        return (local.toFloat() / durationTicks).coerceIn(0f, 1f)
    }

    private fun eased(easing: EasingType, t: Float): Float = easing.evaluate(t.coerceIn(0f, 1f))

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
