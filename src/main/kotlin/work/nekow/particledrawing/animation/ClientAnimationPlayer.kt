package work.nekow.particledrawing.animation

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.animation.script.ParticleHost
import work.nekow.particledrawing.animation.script.ScriptProgram
import work.nekow.particledrawing.animation.script.ScriptRuntime
import work.nekow.particledrawing.animation.script.parseProgram
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.util.rotateAround
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

@Suppress("unused")
class ClientAnimationPlayer(
    private val animation: ParticleAnimation,
    private val origin: Vec3,
    // 服务端权威进度起点（维度 gameTime）；进度 = wrap/clamp(currentGameTick - startGameTick)
    private val startGameTick: Long = 0L,
    currentGameTick: Long = 0L,
    // 显式起始 tick（特效 API 使用；<0 时按 gameTime 时钟定位）
    initialTick: Int = -1,
) {

    data class ParticleState(
        val id: String,
        var pos: Vec3,
        var color: Color,
        var scale: FloatArray,
        var glowing: Boolean,
        var lightLevel: Int,
        var uv: UvData?,
        /** t ≥ st 才为 true；隐藏门控在同步层生效（未出场粒子不生成/已回收）。 */
        var visible: Boolean = true,
    )

    /**
     * 摄像机在某时刻的姿态（供脚本/模组按 id 查询；播放端不自动改变玩家相机）。
     *
     * @param pos 世界坐标 [x,y,z]
     * @param target 看向目标点 [x,y,z]（世界坐标）；pitch/yaw 由 lookAt(pos, target) 计算
     * @param roll 翻滚角（度，绕视线方向，静态基础值）
     * @param fov 视场角（度）
     */
    data class CameraPose(
        val pos: DoubleArray,
        val target: DoubleArray,
        val roll: Double,
        val fov: Double,
    )

    private var currentTick = 0
    private val states = LinkedHashMap<String, ParticleState>()
    private var finished = false
    private var justLooped = false
    private var prevAdvanceT = 0.0
    private var advanceInitialized = false

    // ---- 调试统计 ----
    var lastAdvanceNanos: Long = 0; private set
    var lastRestoreNanos: Long = 0; private set   // TEMP 计时探针
    var frameCount: Long = 0; private set
    private var advanceNanosTotal = 0L
    private var advanceCount = 0L

    val avgAdvanceNanos: Long get() = if (advanceCount == 0L) 0L else advanceNanosTotal / advanceCount
    val particleCount: Int get() = states.size
    val currentTickValue: Int get() = currentTick
    val maxTickValue: Int get() = maxTick

    private val maxTick: Int = animation.timelineLength()

    // 静态动画（无轨道/时间轴，且公式与变量均不含 random()）：init 已算好 t=0 状态，每 tick 无需重算。
    // 5w 粒子的静态粒子云若每刻重算会白费约 70ms/tick。
    // 存在 st 门控或入场预设时必然随时间变化，强制按动态处理。
    private val isStaticAnimation: Boolean = run {
        if (maxTick > 0) return@run false
        if (animation.particles.any { it.st > 0 || it.ent != null }) return@run false
        if (animation.functions.any { it.st > 0 || it.ent != null }) return@run false
        animation.functions.none { fx -> usesRandom(fx) }
    }

    // ---- 预构建求值索引（避免每 tick 线性扫描轨道 / 组 / 粒子） ----
    private val trackIndex: Map<String, Map<String, AnimTrack>> = buildTrackIndex()
    private val opTracks: List<AnimTrack> = animation.tracks.filter { it.mode == AnimTrack.Mode.OP }
    private val opTracksByPr: Map<String, List<AnimTrack>> = opTracks.filter { it.keyframes.isNotEmpty() }.groupBy { it.pr }
    private val groupSets: Map<String, Set<String>> = animation.groups.mapValues { (_, v) -> v.toSet() }
    private val groupSpinLocal: Set<String> = animation.groupSpinSpace.filterValues { it }.keys
    private val groupRotLocal: Set<String> = animation.groupRotSpace.filterValues { it }.keys
    private val particleGroupIndex: Map<String, Set<String>> = buildParticleGroupIndex()
    private val groupCentroidCache: Map<String, Vec3> = buildGroupCentroids()
    private val camUp = Vec3(0.0, 1.0, 0.0)

    // ---- UV 字段表达式（v10/v2 新增）----
    // 普通粒子全局序号：index=全局序号、count=粒子总数（普通 + 派生，与编辑器 state.particles.length 一致）；
    // 派生粒子 index=spawn 序号、uv=(0,0)。
    private val particleGlobalIndex: Map<String, Int> = animation.particles.mapIndexed { i, p -> p.id to i }.toMap()
    private val animationParticleById: Map<String, AnimParticle> = animation.particles.associateBy { it.id }

    // UV 表达式 runner 缓存：同一表达式字符串跨粒子复用编译产物。
    private val uvExprCache = HashMap<String, ScriptRuntime.ExpressionRunner?>()

    // 普通粒子 UV 表达式复用的 ProcessCtx（每次求值前改字段；vars 恒为 emptyMap）。
    private val uvCtx = ScriptRuntime.ProcessCtx(
        0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, emptyMap(),
        fastMath = false, duration = 0.0,
    )

    private fun uvExprRunner(expr: String): ScriptRuntime.ExpressionRunner? =
        uvExprCache.getOrPut(expr) {
            try {
                ScriptRuntime.ExpressionRunner(expr)
            } catch (e: Exception) {
                println("[pdrawc] UV 表达式编译失败：${e.message}")
                null
            }
        }

    /** 求值单个 UV 字段表达式；出错/非有限回退到数值字段 [fallback]。 */
    private fun evalUvField(expr: String?, ctx: ScriptRuntime.ProcessCtx, fallback: Double): Double {
        if (expr == null) return fallback
        val runner = uvExprRunner(expr) ?: return fallback
        return try {
            val v = runner.eval(ctx)
            if (v.isFinite()) v else fallback
        } catch (e: Exception) {
            println("[pdrawc] UV 表达式求值失败：${e.message}")
            fallback
        }
    }

    /** 把 UV 表达式逐字段求值并生成「数值化」的新 UvData（表达式置 null）。 */
    private fun evalUv(uv: UvData, ctx: ScriptRuntime.ProcessCtx): UvData {
        if (!uv.hasExpressions()) return uv
        val start = intArrayOf(
            evalUvField(uv.uvStartExpr[0], ctx, uv.uvStart[0].toDouble()).roundToInt(),
            evalUvField(uv.uvStartExpr[1], ctx, uv.uvStart[1].toDouble()).roundToInt(),
        )
        val size = intArrayOf(
            evalUvField(uv.uvSizeExpr[0], ctx, uv.uvSize[0].toDouble()).roundToInt(),
            evalUvField(uv.uvSizeExpr[1], ctx, uv.uvSize[1].toDouble()).roundToInt(),
        )
        val step = intArrayOf(
            evalUvField(uv.uvStepExpr[0], ctx, uv.uvStep[0].toDouble()).roundToInt(),
            evalUvField(uv.uvStepExpr[1], ctx, uv.uvStep[1].toDouble()).roundToInt(),
        )
        val fps = evalUvField(uv.fpsExpr, ctx, uv.fps.toDouble()).toFloat()
        val maxFrame = evalUvField(uv.maxFrameExpr, ctx, uv.maxFrame.toDouble()).roundToInt()
        return UvData(uv.texture, uv.mode, uv.texSize, start, size, step, fps, maxFrame, uv.loop)
    }

    // ---- 函数对象脚本运行时（v12 spawn 模型：setup 一次、tick 补跑、process 每帧一次）----
    private val fxById: Map<String, FunctionObject> = animation.functions.associateBy { it.id }

    private class FxRuntime(
        val fx: FunctionObject,
        val program: ScriptProgram,
        val objState: ScriptRuntime.ObjectState,
    ) {
        val particles = ArrayList<ParticleHost>()
        var spawnSerial = 0
        var tickCursor = 0.0
        var curTick = 0.0

        /** 粒子句柄桥接（脚本 this.spawn() / 粒子字段读写 / kill()）。 */
        inner class FxParticleHost(
            override val index: Int,
            var spawnTick: Double,
        ) : ParticleHost {
            override val pos = DoubleArray(3)
            override val color = doubleArrayOf(1.0, 1.0, 1.0, 1.0)
            override val vel = DoubleArray(3)
            override var scale = 1.0
            override var glow = false
            override var light = 0.0
            override var life = -1.0
            override val fields = HashMap<String, Any?>()
            var alive = true

            override fun kill() {
                if (!alive) return
                alive = false
                particles.remove(this)
            }
        }

        fun spawn(): ParticleHost {
            val serial = spawnSerial++
            val host = FxParticleHost(serial, curTick)
            particles.add(host)
            return host
        }

        // ---- 循环回卷快照：避免回卷时重新 setup / 重建粒子造成顿挫，并保证 rand/global 确定性。 ----
        // loopSnapshot：process 前（首圈活动窗口首个 tick、cursor == st）捕获，供一般向后 seek 正向补跑。
        // loopDoneSnapshot：process 后（t == st）捕获，供回卷恰好落在 st 时直接恢复并跳过 process。
        var loopSnapshot: LoopSnapshot? = null
        var loopDoneSnapshot: LoopSnapshot? = null
        var skipProcessOnce = false

        class LoopSnapshot(
            val particles: List<HostSnapshot>,
            val spawnSerial: Int,
            val tickCursor: Double,
            val curTick: Double,
            val globals: Map<String, Any?>,
            val randState: Int,
        )

        class HostSnapshot(
            val index: Int,
            val spawnTick: Double,
            val pos: DoubleArray,
            val color: DoubleArray,
            val vel: DoubleArray,
            val scale: Double,
            val glow: Boolean,
            val light: Double,
            val life: Double,
            val fields: Map<String, Any?>,
        )

        private fun buildSnapshot(): LoopSnapshot = LoopSnapshot(
            particles = particles.map { h ->
                val p = h as FxParticleHost
                HostSnapshot(
                    index = p.index,
                    spawnTick = p.spawnTick,
                    pos = p.pos.copyOf(),
                    color = p.color.copyOf(),
                    vel = p.vel.copyOf(),
                    scale = p.scale,
                    glow = p.glow,
                    light = p.light,
                    life = p.life,
                    fields = HashMap<String, Any?>().also { out ->
                        for ((k, v) in p.fields) out[k] = deepCopyScriptValue(v)
                    },
                )
            },
            spawnSerial = spawnSerial,
            tickCursor = tickCursor,
            curTick = curTick,
            globals = HashMap<String, Any?>().also { out ->
                for ((k, v) in objState.globals) out[k] = deepCopyScriptValue(v)
            },
            randState = objState.rand.a,
        )

        fun captureLoopSnapshot() {
            loopSnapshot = buildSnapshot()
        }

        fun captureLoopDoneSnapshot() {
            loopDoneSnapshot = buildSnapshot()
        }

        /** 按目标 tick 恢复循环起点：目标恰为 st 时用 process 后快照并跳过 process；否则用 process 前快照正向补跑。 */
        fun restoreForLoopStart(targetT: Double) {
            val st = fx.st.toDouble()
            val done = loopDoneSnapshot
            if (done != null && targetT == st) {
                applySnapshot(done)
                skipProcessOnce = true
            } else {
                loopSnapshot?.let { applySnapshot(it) }
            }
        }

        private fun applySnapshot(s: LoopSnapshot) {
            // 原地恢复：按 index 复用本圈仍存活的句柄，只回写字段；
            // 避免每圈全量重新分配粒子句柄（每个句柄还带 3 个 DoubleArray + fields Map）造成 GC 顿挫。
            val aliveByIndex = HashMap<Int, FxParticleHost>(particles.size)
            for (h in particles) {
                val host = h as FxParticleHost
                aliveByIndex[host.index] = host
            }
            particles.clear()
            for (hs in s.particles) {
                val host = aliveByIndex.remove(hs.index) ?: FxParticleHost(hs.index, hs.spawnTick)
                host.spawnTick = hs.spawnTick
                hs.pos.copyInto(host.pos)
                hs.color.copyInto(host.color)
                hs.vel.copyInto(host.vel)
                host.scale = hs.scale
                host.glow = hs.glow
                host.light = hs.light
                host.life = hs.life
                host.alive = true
                host.fields.clear()
                for ((k, v) in hs.fields) host.fields[k] = deepCopyScriptValue(v)
                particles.add(host)
            }
            // aliveByIndex 剩余的是本圈额外 spawn / 已被 kill 的句柄，不再入列，自然丢弃。
            spawnSerial = s.spawnSerial
            tickCursor = s.tickCursor
            curTick = s.curTick
            objState.globals.clear()
            for ((k, v) in s.globals) objState.globals[k] = deepCopyScriptValue(v)
            objState.rand.a = s.randState
        }
    }

    private val fxRuntimes: MutableMap<String, FxRuntime?> = buildFxRuntimes()
    private val derivedByFx: HashMap<String, HashSet<String>> = HashMap()
    private val derivedHosts: HashMap<String, FxRuntime.FxParticleHost> = HashMap()

    // 视觉会随时间变化的普通粒子（有轨道/速度/入场过渡）；其余静态粒子每 tick 只更新可见性。
    private val dynamicParticleIds: Set<String> = buildDynamicParticleIds()

    private fun buildFxRuntimes(): MutableMap<String, FxRuntime?> {
        val map = HashMap<String, FxRuntime?>()
        for (fx in animation.functions) map[fx.id] = buildFxRuntime(fx)
        return map
    }

    private fun buildFxRuntime(fx: FunctionObject): FxRuntime? = try {
        val program = parseProgram(fx.source)
        val obj = ScriptRuntime.createObjectState(fx.seed)
        val rt = FxRuntime(fx, program, obj)
        rt.tickCursor = floor(fx.st.toDouble()) - 1
        rt.curTick = fx.st.toDouble()
        ScriptRuntime.runSpawnSetup(program, obj, makeCtx(fx, rt, fx.st.toDouble(), 0.0))
        rt
    } catch (e: Exception) {
        println("[pdrawc] 函数对象 ${fx.id} 编译失败：${e.message}")
        null
    }

    private fun resetFxRuntime(fx: FunctionObject, rt: FxRuntime) {
        rt.particles.clear()
        rt.spawnSerial = 0
        rt.tickCursor = floor(fx.st.toDouble()) - 1
        rt.curTick = fx.st.toDouble()
        // 与编辑器一致：向后 seek 重建 objState（fresh globals + fresh PRNG），
        // 避免 setup 反复在脏 global / 已推进的 rand 状态上叠加导致漂移。
        rt.objState.globals.clear()
        rt.objState.rand.a = fx.seed
        try {
            ScriptRuntime.runSpawnSetup(rt.program, rt.objState, makeCtx(fx, rt, fx.st.toDouble(), 0.0))
        } catch (e: Exception) {
            println("[pdrawc] 函数对象 ${fx.id} setup 求值失败：${e.message}")
        }
    }

    private fun makeCtx(fx: FunctionObject, rt: FxRuntime, t: Double, deltaMs: Double): ScriptRuntime.ScriptCtx =
        ScriptRuntime.ScriptCtx(
            t = t,
            duration = maxTick.toDouble(),
            vars = varsAt(fx, t),
            particles = rt.particles,
            spawn = rt::spawn,
            deltaMs = deltaMs,
            fastMath = fx.fastMath,
            print = { line -> println("[pdrawc:${fx.id}] $line") },
        )

    private fun buildTrackIndex(): Map<String, Map<String, AnimTrack>> {
        val map = HashMap<String, HashMap<String, AnimTrack>>()
        for (tr in animation.tracks) {
            if (tr.ids.size == 1) {
                val byId = map.getOrPut(tr.pr) { HashMap() }
                byId[tr.ids[0]] = tr
            }
        }
        return map
    }

    private fun buildParticleGroupIndex(): Map<String, Set<String>> {
        val map = HashMap<String, HashSet<String>>()
        for ((gname, members) in animation.groups) {
            for (id in members) map.getOrPut(id) { HashSet() }.add(gname)
        }
        return map
    }

    private fun buildGroupCentroids(): Map<String, Vec3> {
        val byId = HashMap<String, AnimParticle>()
        for (p in animation.particles) byId[p.id] = p
        val map = HashMap<String, Vec3>()
        for (gname in animation.groups.keys) {
            centroidOf(gname, byId)?.let { map[gname] = it }
        }
        return map
    }

    /** 组质心（成员位置平均）；空组或不存在返回 null。 */
    private fun centroidOf(gname: String, byId: Map<String, AnimParticle>): Vec3? {
        val members = animation.groups[gname] ?: return null
        var sx = 0.0; var sy = 0.0; var sz = 0.0; var n = 0
        for (id in members) {
            val m = byId[id] ?: continue
            sx += m.pos.x; sy += m.pos.y; sz += m.pos.z; n++
        }
        if (n == 0) return null
        return Vec3(sx / n, sy / n, sz / n)
    }

    /** 派生粒子 id（fxId:p<serial>）反查函数对象；普通粒子返回 null。 */
    private fun particleFunction(id: String): FunctionObject? {
        val marker = id.indexOf(":p")
        if (marker <= 0) return null
        return fxById[id.substring(0, marker)]
    }

    /**
     * 标记「视觉会随时间变化」的普通粒子：自身或所属组存在 set/op 轨道、速度非零、带入场过渡。
     * 其余粒子（如 64×64 图片导入的 4096 个静态像素粒子）位置/颜色/缩放恒定，
     * advanceTo 只更新其 st/life 可见性，避免每 tick 重算与大量 Vec3/Color/FloatArray 分配。
     * 派生粒子（fx）由函数对象循环求值，不在本集合内。
     */
    private fun buildDynamicParticleIds(): Set<String> {
        val ids = HashSet<String>()
        for (p in animation.particles) {
            if (p.vel.x != 0.0 || p.vel.y != 0.0 || p.vel.z != 0.0) ids.add(p.id)
            if (p.ent != null) ids.add(p.id)
        }
        for (tr in animation.tracks) {
            if (tr.keyframes.isEmpty()) continue
            for (id in tr.ids) {
                if (id.startsWith("g:")) {
                    val members = animation.groups[id.removePrefix("g:")] ?: continue
                    ids.addAll(members)
                } else if (!id.startsWith("f:")) {
                    ids.add(id)
                }
            }
        }
        return ids
    }

    init {
        for (p in animation.particles) {
            states[p.id] = ParticleState(p.id, origin.add(p.pos), p.color, p.scale.copyOf(), p.glowing, p.lightLevel, resolveUV(p.id, p.uv))
        }
        // 函数对象运行时已在字段初始化阶段构建（setup 各执行一次）；派生粒子状态由 advanceTo 的
        // reconcile 按当前存活粒子动态创建/更新/删除。
        // 按服务端权威进度定位到当前帧（elapsed = currentGameTick - startGameTick）：
        // 新播放等价于从 0 开始；重发/迟到加入则直接跳到其他玩家正在看的同一帧。
        val initial = if (initialTick >= 0) initialTick else AnimationProgress.tickAt(
            (currentGameTick - startGameTick).coerceAtLeast(0L), maxTick, animation.loop
        )
        currentTick = initial
        advanceTo(initial.toDouble())
    }

    /**
     * 以维度 gameTime 为权威时钟推进一 tick。
     * 所有客户端使用同一 startGameTick 与同一 gameTime，因此帧号完全一致；
     * 本地不再各自递增，杜绝客户端间漂移与重发后从头重播的问题。
     */
    fun tick(gameTick: Long): Boolean {
        if (finished) return false
        frameCount++
        val elapsed = (gameTick - startGameTick).coerceAtLeast(0L)
        if (AnimationProgress.isFinished(elapsed, maxTick, animation.loop)) {
            finished = true
            return false
        }
        val target = AnimationProgress.tickAt(elapsed, maxTick, animation.loop)
        if (target != currentTick) {
            if (target < currentTick) {
                justLooped = true // 循环回卷（st 门控粒子在 sync 中重新生成）
                if (!isStaticAnimation) restoreLoopStart(target)
            }
            currentTick = target
            if (!isStaticAnimation) {
                val t0 = System.nanoTime()
                advanceTo(target.toDouble())
                val elapsedNs = System.nanoTime() - t0
                lastAdvanceNanos = elapsedNs
                advanceNanosTotal += elapsedNs
                advanceCount++
            }
        }
        return true
    }

    /**
     * 由外部播放时钟驱动推进（特效 API 使用）：直接给定目标时间轴 tick，
     * 而非由 `gameTime - startGameTick` 推导。支持任意 seek（含向后回退）、暂停与变速。
     * @return 是否仍在播放（非循环动画越过 maxTick 时返回 false 并置 finished）
     */
    fun tickExternal(targetTick: Int): Boolean {
        if (finished) return false
        frameCount++
        val max = maxTick
        if (!animation.loop && max > 0 && targetTick >= max) {
            finished = true
            return false
        }
        val target = if (max <= 0) 0
        else if (animation.loop) ((targetTick % max) + max) % max
        else minOf(targetTick, max - 1)
        if (target != currentTick) {
            if (target < currentTick && animation.loop) {
                justLooped = true
                if (!isStaticAnimation) restoreLoopStart(target)
            }
            currentTick = target
            if (!isStaticAnimation) advanceTo(target.toDouble())
        }
        return true
    }

    fun isFinished(): Boolean = finished
    fun consumeJustLooped(): Boolean { val v = justLooped; justLooped = false; return v }
    fun isJustLooped(): Boolean = justLooped   // TEMP 计时探针
    fun isStatic(): Boolean = isStaticAnimation

    /** 循环回卷：把函数对象运行时恢复到循环起点快照，避免重新 setup / 重建粒子。 */
    private fun restoreLoopStart(target: Int) {
        val t0 = System.nanoTime()
        for (rt in fxRuntimes.values) rt?.restoreForLoopStart(target.toDouble())
        lastRestoreNanos = System.nanoTime() - t0
    }

    private fun usesRandom(fx: FunctionObject): Boolean =
        Regex("\\brandom\\s*\\(").containsMatchIn(fx.source) || Regex("\\brand\\s*\\(").containsMatchIn(fx.source)
    fun currentStates(): Collection<ParticleState> = states.values
    fun stop() { finished = true }

    /**
     * 查询某摄像机对象在 t 时刻的姿态（v6 新增；v7 起朝向为 target 目标点 + roll；v8 起支持旋转公转）。
     *
     * 与编辑器 `cameraPoseAt` 语义一致：
     * - pos/target 分量：set 轨道 `trackValueAt(t, base)`；op 轨道 `base + trackValueAt(t, 0)`；
     * - rot 分量：set/op 均 `trackValueAt(t, 0)`（基值 0）；
     * - roll：静态基础值，不走关键帧；
     * - fov：直接 `trackValueAt(t, fov)`（不区分 set/op，与编辑器一致）。
     * - 旋转 = 位置绕 target 公转：world 空间绕世界 X/Y/Z 轴依次旋转；
     *   local 空间以 lookAt+roll 自身朝向做 intrinsic XYZ 旋转（M = M_look·M_local·M_lookᵀ）。
     *
     * 摄像机不存在时返回 null。
     */
    fun cameraPoseAt(camId: String, t: Double): CameraPose? {
        val cam = animation.cameras.firstOrNull { it.id == camId } ?: return null
        val id = "c:" + camId
        val pos = DoubleArray(3)
        val target = DoubleArray(3)
        for (i in 0 until 3) {
            val comp = when (i) { 0 -> "x"; 1 -> "y"; else -> "z" }
            pos[i] = cameraComponentAt(id, "pos", comp, cam.pos[i], t)
            target[i] = cameraComponentAt(id, "target", comp, cam.target[i], t)
        }
        val rot = rotVectorAt(id, t)
        if (rot[0] != 0.0 || rot[1] != 0.0 || rot[2] != 0.0) {
            applyCameraOrbit(pos, target, rot, cam.rotLocal, cam.roll)
        }
        val roll = cam.roll
        val fov = scalarAt("fov", id, t, cam.fov)
        return CameraPose(pos, target, roll, fov)
    }

    /** 摄像机公转（v8）：把位置绕「看向目标点」旋转。 */
    private fun applyCameraOrbit(pos: DoubleArray, target: DoubleArray, rot: DoubleArray, rotLocal: Boolean, roll: Double) {
        val d = Vec3(pos[0] - target[0], pos[1] - target[1], pos[2] - target[2])
        if (d.lengthSqr() < 1e-18) return // 与目标重合：无法公转
        val r = if (rotLocal) {
            val frame = cameraLookFrame(Vec3(pos[0], pos[1], pos[2]), Vec3(target[0], target[1], target[2]), roll)
                ?: return worldCameraOrbitInto(pos, target, rot)
            rotateAroundFrame(d, rot, frame.first, frame.second, frame.third)
        } else {
            rotateAround(d, Vec3.ZERO, rot)
        }
        pos[0] = target[0] + r.x
        pos[1] = target[1] + r.y
        pos[2] = target[2] + r.z
    }

    /** 世界空间回退（lookAt 退化时）：绕世界 X/Y/Z 轴依次旋转并写回 pos。 */
    private fun worldCameraOrbitInto(pos: DoubleArray, target: DoubleArray, rot: DoubleArray) {
        val d = Vec3(pos[0] - target[0], pos[1] - target[1], pos[2] - target[2])
        val r = rotateAround(d, Vec3.ZERO, rot)
        pos[0] = target[0] + r.x
        pos[1] = target[1] + r.y
        pos[2] = target[2] + r.z
    }

    /**
     * 摄像机 lookAt+roll 朝向基（列 = 世界坐标）：(right, camUp, back)。
     * 与编辑器一致：局部 +Z = normalize(pos − target)（即局部 −Z 指向目标），
     * right = normalize(cross(up, back))，camUp = cross(back, right)，再绕 back 翻滚 roll。
     * 视线与世界 up 平行（lookAt 退化）时返回 null。
     */
    private fun cameraLookFrame(pos: Vec3, target: Vec3, roll: Double): Triple<Vec3, Vec3, Vec3>? {
        val back = pos.subtract(target).normalize()
        var right = camUp.cross(back)
        if (right.lengthSqr() < 1e-12) return null
        right = right.normalize()
        val up = back.cross(right)
        if (roll == 0.0) return Triple(right, up, back)
        val rz = Math.toRadians(roll)
        return Triple(right.rotateAround(back, rz), up.rotateAround(back, rz), back)
    }

    /** 在给定正交基（列 = 世界坐标）下做 intrinsic XYZ 旋转（绕原点；与编辑器 M_look·M_local·M_lookᵀ 等价）。 */
    private fun rotateAroundFrame(p: Vec3, rot: DoubleArray, xAxis: Vec3, yAxis: Vec3, zAxis: Vec3): Vec3 {
        var r = p
        val rx = Math.toRadians(rot[0])
        val ry = Math.toRadians(rot[1])
        val rz = Math.toRadians(rot[2])
        if (rot[0] != 0.0) r = r.rotateAround(xAxis, rx)
        var y = yAxis
        if (rot[0] != 0.0) y = y.rotateAround(xAxis, rx)
        if (rot[1] != 0.0) r = r.rotateAround(y, ry)
        var z = zAxis
        if (rot[0] != 0.0) z = z.rotateAround(xAxis, rx)
        if (rot[1] != 0.0) z = z.rotateAround(y, ry)
        if (rot[2] != 0.0) r = r.rotateAround(z, rz)
        return r
    }

    /** 摄像机单分量求值（pos/target；set=绝对值，op=增量叠加到基础值）。 */
    private fun cameraComponentAt(id: String, prop: String, comp: String, base: Double, t: Double): Double {
        val tr = findTrackByPr(compPr(prop, comp), id) ?: return base
        if (tr.keyframes.isEmpty()) return base
        return if (tr.mode == AnimTrack.Mode.OP) base + trackValueAt(tr, t, 0.0) else trackValueAt(tr, t, base)
    }

    fun updateVariable(name: String, value: String) {
        for (fx in animation.functions) {
            val v = fx.vars[name] ?: continue
            v.base = value.toDoubleOrNull() ?: 0.0
            v.kf = emptyList()
            fxRuntimes[fx.id] = buildFxRuntime(fx)
            return
        }
    }

    private fun advanceTo(t: Double) {
        val deltaMs = if (advanceInitialized && t == prevAdvanceT + 1.0) 50.0 else 0.0

        // 1) 普通粒子：st/life 门控 + 视觉更新（UV 在阶段 3 统一求值）
        for (p in animation.particles) {
            val s = states[p.id] ?: continue
            val localT = t - p.st
            val life = p.life
            // st 门控 + 寿命到期回收（life=-1 无限）
            s.visible = localT >= 0 && (life < 0 || localT < life)
            if (p.id in dynamicParticleIds) {
                s.pos = origin.add(particlePosition(p, t))
                s.color = applyEntrance(particleColor(p, t), p.ent, localT)
                s.scale = particleScale(p, t)
            }
        }

        // 2) 函数对象：按编辑器 evaluateFxFrame 语义推进（setup 一次 / tick 补跑 / process 每帧一次），
        //    并按当前存活粒子动态 reconcile 状态（新建/更新/删除）。
        for (fx in animation.functions) {
            val rt = fxRuntimes[fx.id] ?: continue
            advanceFx(fx, rt, t, deltaMs)
        }

        // 3) UV 字段表达式（普通 + 派生粒子统一；n=总粒子数，与编辑器 state.particles.length 一致）
        val n = states.size.toDouble()
        for ((id, s) in states) {
            val host = derivedHosts[id]
            val fx = particleFunction(id)
            val ownUv = if (fx != null) fx.uv else animationParticleById[id]?.uv
            val resolvedUv = resolveUV(id, ownUv)
            if (resolvedUv != null && resolvedUv.hasExpressions()) {
                uvCtx.i = if (host != null) host.index.toDouble() else (particleGlobalIndex[id] ?: 0).toDouble()
                uvCtx.n = n
                uvCtx.t = t
                uvCtx.dt = 0.0
                uvCtx.duration = maxTick.toDouble()
                uvCtx.life = if (host != null) host.life else (animationParticleById[id]?.life?.toDouble() ?: -1.0)
                uvCtx.uv_x = 0.0
                uvCtx.uv_y = 0.0
                uvCtx.out.pos[0] = s.pos.x; uvCtx.out.pos[1] = s.pos.y; uvCtx.out.pos[2] = s.pos.z
                uvCtx.out.color[0] = s.color.r.toDouble(); uvCtx.out.color[1] = s.color.g.toDouble()
                uvCtx.out.color[2] = s.color.b.toDouble(); uvCtx.out.color[3] = s.color.a.toDouble()
                if (host != null) {
                    uvCtx.out.vel[0] = host.vel[0]; uvCtx.out.vel[1] = host.vel[1]; uvCtx.out.vel[2] = host.vel[2]
                } else {
                    val p = animationParticleById[id]
                    if (p != null) {
                        uvCtx.out.vel[0] = componentValueAt(p, "vel", "x", t)
                        uvCtx.out.vel[1] = componentValueAt(p, "vel", "y", t)
                        uvCtx.out.vel[2] = componentValueAt(p, "vel", "z", t)
                    }
                }
                uvCtx.out.scale = s.scale[0].toDouble()
                uvCtx.out.glow = s.glowing
                uvCtx.out.light = s.lightLevel.toDouble()
                uvCtx.out.life = uvCtx.life
                s.uv = evalUv(resolvedUv, uvCtx)
            } else {
                s.uv = resolvedUv
            }
        }

        prevAdvanceT = t
        advanceInitialized = true
    }

    /**
     * 把函数对象推进到时间 t（与编辑器 evaluateFxFrame 同语义）：
     * - t < st 或超过对象时长：仅保持 setup 已执行（粒子保留，渲染层按门控隐藏）；
     * - 向后 seek（t < tickCursor）：重建运行时（清空粒子、重跑 setup、重置 tick 游标）；
     * - 正常：补跑 (tickCursor, floor(t)] 的 tick()，再跑一次 process(deltaMs)。
     */
    private fun advanceFx(fx: FunctionObject, rt: FxRuntime, t: Double, deltaMs: Double) {
        val st = fx.st.toDouble()
        val dur = fx.duration.toDouble()
        if (t >= st && (dur <= 0 || t < st + dur)) {
            // 向后 seek：确定性重算
            if (t < rt.tickCursor) resetFxRuntime(fx, rt)

            val floorT = floor(t)
            while (rt.tickCursor < floorT) {
                rt.tickCursor += 1.0
                rt.curTick = rt.tickCursor
                decrementLife(fx, rt, rt.tickCursor)
                if (rt.program.tick.isNotEmpty()) {
                    try {
                        ScriptRuntime.runTickFrame(rt.program, rt.objState, makeCtx(fx, rt, rt.tickCursor, deltaMs))
                    } catch (e: Exception) {
                        println("[pdrawc] 函数对象 ${fx.id} tick 求值失败：${e.message}")
                        break
                    }
                }
                // 捕获循环起点快照：loop 动画首圈、恰在活动窗口首个 tick（cursor == st）、process 执行前。
                // 回卷时用 restoreLoopSnapshot 恢复，避免重新 setup / 重建粒子造成的顿挫。
                if (animation.loop && rt.loopSnapshot == null && rt.tickCursor == st) {
                    rt.captureLoopSnapshot()
                }
            }

            rt.curTick = t
            if (rt.skipProcessOnce) {
                rt.skipProcessOnce = false
            } else if (rt.program.process.isNotEmpty()) {
                try {
                    ScriptRuntime.runProcessFrame(rt.program, rt.objState, makeCtx(fx, rt, t, deltaMs))
                } catch (e: Exception) {
                    println("[pdrawc] 函数对象 ${fx.id} process 求值失败：${e.message}")
                }
            }
            // 捕获 process 后快照：loop 动画首圈、恰在活动窗口首个 tick（t == st）、process 执行后。
            // 回卷恰好落在 st 时直接恢复该快照并跳过 process，进一步消除回卷帧的脚本求值开销。
            if (animation.loop && rt.loopDoneSnapshot == null && t == st) {
                rt.captureLoopDoneSnapshot()
            }
        }
        reconcileFxStates(fx, rt, t, t - fx.st)
    }

    /** 每个 tick 开始前递减剩余寿命；到期（或 life 已为 0）立即移除。 */
    private fun decrementLife(fx: FunctionObject, rt: FxRuntime, tick: Double) {
        val st = fx.st.toDouble()
        for (i in rt.particles.indices.reversed()) {
            val host = rt.particles[i] as FxRuntime.FxParticleHost
            val entry = max(host.spawnTick, st)
            if (host.life >= 0.0 && tick > entry) {
                if (host.life <= 1.0) {
                    host.kill()
                } else {
                    host.life -= 1.0
                }
            }
        }
    }

    /** 把函数对象当前存活粒子同步进渲染状态（新建/更新/删除 + 整体变换 + 可见性门控）。 */
    private fun reconcileFxStates(fx: FunctionObject, rt: FxRuntime, t: Double, fxLocalT: Double) {
        val visible = fxLocalT >= 0 && (fx.duration <= 0 || fxLocalT < fx.duration)
        val cx = fx.center[0]; val cy = fx.center[1]; val cz = fx.center[2]
        // 整体变换 / 自转 / 公转 / op 增量 每 tick 只算一次（与粒子序号无关）
        val sx = scalarAt("spin.x", "f:" + fx.id, t, 0.0)
        val sy = scalarAt("spin.y", "f:" + fx.id, t, 0.0)
        val sz = scalarAt("spin.z", "f:" + fx.id, t, 0.0)
        val hasSpin = sx != 0.0 || sy != 0.0 || sz != 0.0
        val spinPivot = Vec3(cx, cy, cz)
        val spin = doubleArrayOf(sx, sy, sz)
        val rx = scalarAt("rot.x", "f:" + fx.id, t, 0.0)
        val ry = scalarAt("rot.y", "f:" + fx.id, t, 0.0)
        val rz = scalarAt("rot.z", "f:" + fx.id, t, 0.0)
        val hasRot = rx != 0.0 || ry != 0.0 || rz != 0.0
        val orbitPivot = orbitCenterAt("f:" + fx.id, t)
        val rot = doubleArrayOf(rx, ry, rz)
        val dx = opDeltaAt("pos.x", "f:" + fx.id, t)
        val dy = opDeltaAt("pos.y", "f:" + fx.id, t)
        val dz = opDeltaAt("pos.z", "f:" + fx.id, t)

        val newIds = HashSet<String>()
        for (particle in rt.particles) {
            val host = particle as FxRuntime.FxParticleHost
            val id = fx.id + ":p" + host.index
            newIds.add(id)
            derivedHosts[id] = host
            val s = states.getOrPut(id) {
                ParticleState(id, Vec3.ZERO, Color.WHITE, floatArrayOf(1f, 1f, 1f), false, 0, null, visible = true)
            }
            var pos = Vec3(host.pos[0] + cx, host.pos[1] + cy, host.pos[2] + cz)
            if (hasSpin) pos = if (fx.spinLocal) rotateAroundLocal(pos, spinPivot, spin) else rotateAround(pos, spinPivot, spin)
            // pos op 位移必须先于公转：函数对象的实际世界位置应绕公转中心旋转。
            pos = Vec3(pos.x + dx, pos.y + dy, pos.z + dz)
            if (hasRot) pos = if (fx.rotLocal) rotateAroundLocalOrbit(pos, orbitPivot, rot, spin, fx.spinLocal) else rotateAround(pos, orbitPivot, rot)
            s.pos = origin.add(pos)
            s.color = applyEntrance(
                Color.of(
                    host.color[0].coerceIn(0.0, 1.0).toFloat(),
                    host.color[1].coerceIn(0.0, 1.0).toFloat(),
                    host.color[2].coerceIn(0.0, 1.0).toFloat(),
                    host.color[3].coerceIn(0.0, 1.0).toFloat(),
                ),
                fx.ent, fxLocalT,
            )
            val base = if (host.scale.isFinite()) host.scale else 1.0
            s.scale = fxScale(fx.id, base, t)
            s.glowing = host.glow
            s.lightLevel = host.light.toInt().coerceIn(0, 15)
            s.visible = visible
        }
        // 删除已消失（被 kill / 重建后未再 spawn）的派生粒子状态
        val oldIds = derivedByFx[fx.id] ?: emptySet()
        for (id in oldIds) {
            if (id !in newIds) {
                states.remove(id)
                derivedHosts.remove(id)
            }
        }
        derivedByFx[fx.id] = newIds
    }

    /** 入场预设的 alpha 系数（仅 fade：localT ∈ [0,dur) 线性 0→1）；其余/超窗恒 1。 */
    private fun entranceFactor(ent: Entrance?, localT: Double): Float {
        if (ent == null || ent.preset != "fade" || localT >= ent.dur) return 1f
        if (localT <= 0) return 0f
        return (localT / ent.dur.coerceAtLeast(1)).toFloat()
    }

    private fun applyEntrance(c: Color, ent: Entrance?, localT: Double): Color {
        val k = entranceFactor(ent, localT)
        if (k >= 1f) return c
        return Color.of(c.r, c.g, c.b, (c.a * k))
    }

    private fun varsAt(fx: FunctionObject, t: Double): Map<String, Double> {
        val out = HashMap<String, Double>()
        for ((name, v) in fx.vars) out[name] = varValue(v, t)
        return out
    }

    private fun varValue(v: FunctionVar, t: Double): Double {
        val kf = v.kf
        if (kf.isEmpty()) return v.base
        if (t <= kf[0].tick) return kf[0].value
        if (t >= kf.last().tick) return kf.last().value
        var lo = 0
        var hi = kf.size - 1
        while (lo + 1 < hi) {
            val mid = (lo + hi) ushr 1
            if (kf[mid].tick <= t) lo = mid else hi = mid
        }
        val a = kf[lo]; val b = kf[lo + 1]
        val dur = (b.tick - a.tick).toDouble()
        val f = if (dur == 0.0) 1.0 else (t - a.tick) / dur
        val e = b.easing.evaluate(f.toFloat()).toDouble()
        return a.value + (b.value - a.value) * e
    }

    private fun compPr(prop: String, comp: String): String = if (comp.isEmpty()) prop else prop + "." + comp

    private fun findTrackByPr(pr: String, id: String): AnimTrack? = trackIndex[pr]?.get(id)

    private fun trackValueAt(tr: AnimTrack, t: Double, fallback: Double): Double {
        val kfs = tr.keyframes
        if (kfs.isEmpty()) return fallback
        if (t <= kfs[0].tick) return kfs[0].value
        if (t >= kfs.last().tick) return kfs.last().value
        var lo = 0
        var hi = kfs.size - 1
        while (lo + 1 < hi) {
            val mid = (lo + hi) ushr 1
            if (kfs[mid].tick <= t) lo = mid else hi = mid
        }
        val a = kfs[lo]; val b = kfs[lo + 1]
        val dur = (b.tick - a.tick).toDouble()
        val f = if (dur == 0.0) 1.0 else (t - a.tick) / dur
        val e = b.easing.evaluate(f.toFloat()).toDouble()
        return a.value + (b.value - a.value) * e
    }

    private fun scalarAt(pr: String, id: String, t: Double, fallback: Double): Double {
        val tr = findTrackByPr(pr, id) ?: return fallback
        return trackValueAt(tr, t, fallback)
    }

    private fun opDeltaAt(pr: String, id: String, t: Double): Double {
        val tr = findTrackByPr(pr, id) ?: return 0.0
        if (tr.mode != AnimTrack.Mode.OP || tr.keyframes.isEmpty()) return 0.0
        return trackValueAt(tr, t, 0.0)
    }

    private fun findSetTrackFor(id: String, prop: String, comp: String): AnimTrack? {
        val pr = compPr(prop, comp)
        findTrackByPr(pr, id)?.let { if (it.mode != AnimTrack.Mode.OP) return it }
        for (gname in particleGroupIndex[id] ?: emptySet()) {
            findTrackByPr(pr, "g:" + gname)?.let { if (it.mode != AnimTrack.Mode.OP) return it }
        }
        val fx = particleFunction(id)
        if (fx != null) {
            findTrackByPr(pr, "f:" + fx.id)?.let { if (it.mode != AnimTrack.Mode.OP) return it }
        }
        return null
    }

    private fun compOpDelta(p: AnimParticle, prop: String, comp: String, t: Double): Double {
        val pr = compPr(prop, comp)
        var delta = 0.0
        for (tr in opTracksByPr[pr] ?: emptyList()) {
            for (id in tr.ids) {
                if (id.startsWith("g:")) {
                    val members = groupSets[id.removePrefix("g:")] ?: continue
                    if (p.id in members) delta += trackValueAt(tr, t, 0.0)
                } else if (id.startsWith("f:") && p.id.startsWith(id.removePrefix("f:") + ":p")) {
                    delta += trackValueAt(tr, t, 0.0)
                }
            }
        }
        return delta
    }

    private fun rotVectorAt(id: String, t: Double): DoubleArray =
        doubleArrayOf(
            scalarAt("rot.x", id, t, 0.0),
            scalarAt("rot.y", id, t, 0.0),
            scalarAt("rot.z", id, t, 0.0),
        )

    private fun spinVectorAt(id: String, t: Double): DoubleArray =
        doubleArrayOf(
            scalarAt("spin.x", id, t, 0.0),
            scalarAt("spin.y", id, t, 0.0),
            scalarAt("spin.z", id, t, 0.0),
        )

    private fun orbitCenterAt(id: String, t: Double): Vec3 =
        Vec3(
            scalarAt("center.x", id, t, 0.0),
            scalarAt("center.y", id, t, 0.0),
            scalarAt("center.z", id, t, 0.0),
        )

    private fun baseComponent(p: AnimParticle, prop: String, comp: String): Double = when (prop) {
        "pos" -> when (comp) { "x" -> p.pos.x; "y" -> p.pos.y; else -> p.pos.z }
        "col" -> when (comp) { "r" -> p.color.r.toDouble(); "g" -> p.color.g.toDouble(); "b" -> p.color.b.toDouble(); else -> p.color.a.toDouble() }
        "vel" -> when (comp) { "x" -> p.vel.x; "y" -> p.vel.y; else -> p.vel.z }
        "scl" -> when (comp) { "x" -> p.scale[0].toDouble(); "y" -> p.scale[1].toDouble(); else -> p.scale[2].toDouble() }
        else -> 0.0
    }

    private fun componentValueAt(p: AnimParticle, prop: String, comp: String, t: Double): Double {
        var v = baseComponent(p, prop, comp)
        val tr = findSetTrackFor(p.id, prop, comp)
        if (tr != null && tr.keyframes.isNotEmpty()) v = trackValueAt(tr, t, v)
        v += compOpDelta(p, prop, comp, t)
        return v
    }

    private fun particlePosition(p: AnimParticle, t: Double): Vec3 {
        var pos = Vec3(
            setComponentValueAt(p, "pos", "x", t),
            setComponentValueAt(p, "pos", "y", t),
            setComponentValueAt(p, "pos", "z", t),
        )
        pos = applyGroupScale(p, pos, t)
        pos = applyParticleOrbit(p, pos, t)
        pos = applySelfRotation(p, pos, t)
        pos = applyOrbitRotation(p, pos, t)
        pos = pos.add(
            compOpDelta(p, "pos", "x", t),
            compOpDelta(p, "pos", "y", t),
            compOpDelta(p, "pos", "z", t),
        )
        return pos
    }

    private fun setComponentValueAt(p: AnimParticle, prop: String, comp: String, t: Double): Double {
        var v = baseComponent(p, prop, comp)
        val tr = findSetTrackFor(p.id, prop, comp)
        if (tr != null && tr.keyframes.isNotEmpty()) v = trackValueAt(tr, t, v)
        return v
    }

    private fun applyParticleOrbit(p: AnimParticle, value: Vec3, t: Double): Vec3 {
        val fx = particleFunction(p.id)
        if (fx != null) return value // 派生粒子没有独立公转轨道
        val rot = rotVectorAt(p.id, t)
        if (rot[0] == 0.0 && rot[1] == 0.0 && rot[2] == 0.0) return value
        return rotateAround(value, orbitCenterAt(p.id, t), rot)
    }

    private fun applySelfRotation(p: AnimParticle, value: Vec3, t: Double): Vec3 {
        val gs = particleGroupIndex[p.id]
        if (gs != null) {
            for (gname in gs) {
                val spin = spinVectorAt("g:" + gname, t)
                if (spin[0] == 0.0 && spin[1] == 0.0 && spin[2] == 0.0) continue
                val pivot = groupCentroidCache[gname] ?: groupCentroid(gname)
                return if (gname in groupSpinLocal) rotateAroundLocal(value, pivot, spin) else rotateAround(value, pivot, spin)
            }
        }
        val fx = particleFunction(p.id)
        if (fx != null) {
            val spin = spinVectorAt("f:" + fx.id, t)
            if (spin[0] == 0.0 && spin[1] == 0.0 && spin[2] == 0.0) return value
            val pivot = Vec3(fx.center[0], fx.center[1], fx.center[2])
            return if (fx.spinLocal) rotateAroundLocal(value, pivot, spin) else rotateAround(value, pivot, spin)
        }
        return value
    }

    private fun applyOrbitRotation(p: AnimParticle, value: Vec3, t: Double): Vec3 {
        val gs = particleGroupIndex[p.id]
        if (gs != null) {
            for (gname in gs) {
                val rot = rotVectorAt("g:" + gname, t)
                if (rot[0] == 0.0 && rot[1] == 0.0 && rot[2] == 0.0) continue
                val pivot = orbitCenterAt("g:" + gname, t)
                if (gname in groupRotLocal) {
                    val spin = spinVectorAt("g:" + gname, t)
                    return rotateAroundLocalOrbit(value, pivot, rot, spin, gname in groupSpinLocal)
                }
                return rotateAround(value, pivot, rot)
            }
        }
        val fx = particleFunction(p.id)
        if (fx != null) {
            val rot = rotVectorAt("f:" + fx.id, t)
            if (rot[0] == 0.0 && rot[1] == 0.0 && rot[2] == 0.0) return value
            val pivot = orbitCenterAt("f:" + fx.id, t)
            if (fx.rotLocal) {
                val spin = spinVectorAt("f:" + fx.id, t)
                return rotateAroundLocalOrbit(value, pivot, rot, spin, fx.spinLocal)
            }
            return rotateAround(value, pivot, rot)
        }
        return value
    }

    /** 组整体缩放：作用于成员相对组质心的偏移，而非粒子大小。 */
    private fun applyGroupScale(p: AnimParticle, value: Vec3, t: Double): Vec3 {
        val gs = particleGroupIndex[p.id] ?: return value
        for (gname in gs) {
            val s = groupScaleAt(gname, t)
            if (s[0] == 1.0 && s[1] == 1.0 && s[2] == 1.0) continue
            val pivot = groupCentroidCache[gname] ?: groupCentroid(gname)
            return Vec3(
                pivot.x + (value.x - pivot.x) * s[0],
                pivot.y + (value.y - pivot.y) * s[1],
                pivot.z + (value.z - pivot.z) * s[2],
            )
        }
        return value
    }

    private fun groupScaleAt(gname: String, t: Double): DoubleArray =
        doubleArrayOf(
            groupScaleComponent(gname, "x", t),
            groupScaleComponent(gname, "y", t),
            groupScaleComponent(gname, "z", t),
        )

    private fun groupScaleComponent(gname: String, comp: String, t: Double): Double {
        val tr = findTrackByPr("scl.$comp", "g:$gname") ?: return 1.0
        if (tr.keyframes.isEmpty()) return 1.0
        return if (tr.mode == AnimTrack.Mode.OP) 1.0 + trackValueAt(tr, t, 0.0)
        else trackValueAt(tr, t, 1.0)
    }

    private fun groupCentroid(gname: String): Vec3 {
        val byId = HashMap<String, AnimParticle>(animation.particles.size)
        for (p in animation.particles) byId[p.id] = p
        return centroidOf(gname, byId) ?: Vec3.ZERO
    }

    private fun particleColor(p: AnimParticle, t: Double): Color {
        return Color.of(
            componentValueAt(p, "col", "r", t).toFloat(),
            componentValueAt(p, "col", "g", t).toFloat(),
            componentValueAt(p, "col", "b", t).toFloat(),
            componentValueAt(p, "col", "a", t).toFloat(),
        )
    }

    private fun particleScale(p: AnimParticle, t: Double): FloatArray {
        // 粒子缩放只有 X/Y（billboard 尺寸由 sx/sy 决定）；组 scl 不再影响粒子大小，改为位置级整体缩放。
        return floatArrayOf(
            ownScaleComponent(p, "x", t).toFloat().coerceAtLeast(0.01f),
            ownScaleComponent(p, "y", t).toFloat().coerceAtLeast(0.01f),
            1f,
        )
    }

    private fun ownScaleComponent(p: AnimParticle, comp: String, t: Double): Double {
        var v = baseComponent(p, "scl", comp)
        val tr = findTrackByPr("scl.$comp", p.id)
        if (tr != null && tr.mode != AnimTrack.Mode.OP && tr.keyframes.isNotEmpty()) v = trackValueAt(tr, t, v)
        return v
    }

    /**
     * 函数对象整体缩放（三分量）：代码块输出标量 [base]，再叠加作用于 `f:fxId` 的
     * `scl.x/y/z` 轨道（存在则覆盖对应分量，与编辑器 currentVisualDerived 语义一致）。
     */
    private fun fxScale(fxId: String, base: Double, t: Double): FloatArray {
        return floatArrayOf(
            scalarAt("scl.x", "f:" + fxId, t, base).toFloat().coerceAtLeast(0.01f),
            scalarAt("scl.y", "f:" + fxId, t, base).toFloat().coerceAtLeast(0.01f),
            scalarAt("scl.z", "f:" + fxId, t, base).toFloat().coerceAtLeast(0.01f),
        )
    }

    /**
     * 解析粒子最终 UV（继承覆盖：p.uv > 组 guv[gname] > 函数对象 fx.uv）。
     * [ownUv] 为该对象自身的 UV（粒子为 p.uv，派生粒子为 fx.uv），组级在内部按成员关系补充。
     */
    private fun resolveUV(stateId: String, ownUv: UvData?): UvData? {
        if (ownUv != null && ownUv.texture != null) return ownUv
        for (gname in particleGroupIndex[stateId] ?: emptySet()) {
            animation.groupUV[gname]?.let { if (it.texture != null) return it }
        }
        // 派生粒子：函数对象级 uv 已在 ownUv 传入；此处兜底再查一次（按 id 反查 fx）
        val fx = particleFunction(stateId)
        if (fx?.uv != null && fx.uv.texture != null) return fx.uv
        return ownUv
    }

    private fun rotateAround(p: Vec3, pivot: Vec3, rot: DoubleArray): Vec3 {
        var r = p.subtract(pivot)
        if (rot[0] != 0.0) r = r.rotateAround(Vec3(1.0, 0.0, 0.0), Math.toRadians(rot[0]))
        if (rot[1] != 0.0) r = r.rotateAround(Vec3(0.0, 1.0, 0.0), Math.toRadians(rot[1]))
        if (rot[2] != 0.0) r = r.rotateAround(Vec3(0.0, 0.0, 1.0), Math.toRadians(rot[2]))
        return pivot.add(r)
    }

    /** 局部自转：intrinsic XYZ（先绕局部 X，再绕已旋转的局部 Y，再绕已旋转的局部 Z）。 */
    private fun rotateAroundLocal(p: Vec3, pivot: Vec3, rot: DoubleArray): Vec3 {
        var r = p.subtract(pivot)
        val rx = Math.toRadians(rot[0])
        val ry = Math.toRadians(rot[1])
        val rz = Math.toRadians(rot[2])
        if (rot[0] != 0.0) r = r.rotateAround(Vec3(1.0, 0.0, 0.0), rx)
        var yAxis = Vec3(0.0, 1.0, 0.0)
        if (rot[0] != 0.0) yAxis = yAxis.rotateAround(Vec3(1.0, 0.0, 0.0), rx)
        if (rot[1] != 0.0) r = r.rotateAround(yAxis, ry)
        var zAxis = Vec3(0.0, 0.0, 1.0)
        if (rot[0] != 0.0) zAxis = zAxis.rotateAround(Vec3(1.0, 0.0, 0.0), rx)
        if (rot[1] != 0.0) zAxis = zAxis.rotateAround(yAxis, ry)
        if (rot[2] != 0.0) r = r.rotateAround(zAxis, rz)
        return pivot.add(r)
    }

    /** 把向量按自转姿态旋转（绕原点；local = intrinsic XYZ）。 */
    private fun forwardSpin(v: Vec3, spin: DoubleArray, local: Boolean): Vec3 =
        if (local) rotateAroundLocal(v, Vec3.ZERO, spin) else rotateAround(v, Vec3.ZERO, spin)

    /** 把向量按自转姿态的逆旋转（绕原点；local = intrinsic XYZ）。 */
    private fun inverseSpin(v: Vec3, spin: DoubleArray, local: Boolean): Vec3 {
        val rx = Math.toRadians(spin[0])
        val ry = Math.toRadians(spin[1])
        val rz = Math.toRadians(spin[2])
        var r = v
        if (local) {
            if (spin[0] != 0.0) r = r.rotateAround(Vec3(1.0, 0.0, 0.0), -rx)
            if (spin[1] != 0.0) r = r.rotateAround(Vec3(0.0, 1.0, 0.0), -ry)
            if (spin[2] != 0.0) r = r.rotateAround(Vec3(0.0, 0.0, 1.0), -rz)
        } else {
            if (spin[2] != 0.0) r = r.rotateAround(Vec3(0.0, 0.0, 1.0), -rz)
            if (spin[1] != 0.0) r = r.rotateAround(Vec3(0.0, 1.0, 0.0), -ry)
            if (spin[0] != 0.0) r = r.rotateAround(Vec3(1.0, 0.0, 0.0), -rx)
        }
        return r
    }

    /** 局部公转：公转轴跟随对象自转后的姿态。M = M_spin · M_localOrbit · M_spinᵀ。 */
    private fun rotateAroundLocalOrbit(p: Vec3, pivot: Vec3, rot: DoubleArray, spin: DoubleArray, spinLocal: Boolean): Vec3 {
        val r = p.subtract(pivot)
        val local = inverseSpin(r, spin, spinLocal)
        val rotated = rotateAroundLocal(local, Vec3.ZERO, rot)
        return pivot.add(forwardSpin(rotated, spin, spinLocal))
    }

    }

/** 深拷贝脚本值（global / 粒子自定义字段）：数组递归拷贝；其余值类型（标量/向量/矩阵/函数）视为不可变。 */
private fun deepCopyScriptValue(v: Any?): Any? = when (v) {
    is MutableList<*> -> {
        val out = ArrayList<Any?>(v.size)
        for (x in v) out.add(deepCopyScriptValue(x))
        out
    }
    is Map<*, *> -> {
        val out = HashMap<String, Any?>()
        for ((k, x) in v) out[k as String] = deepCopyScriptValue(x)
        out
    }
    else -> v
}
