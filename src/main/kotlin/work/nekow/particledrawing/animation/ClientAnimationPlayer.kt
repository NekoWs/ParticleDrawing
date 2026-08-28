package work.nekow.particledrawing.animation

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.animation.expr.ATTR_NAMES
import work.nekow.particledrawing.animation.expr.CompiledFunction
import work.nekow.particledrawing.animation.expr.ExpressionEvaluator
import work.nekow.particledrawing.animation.expr.Reg
import work.nekow.particledrawing.animation.expr.VarDef
import work.nekow.particledrawing.animation.expr.compileFunctionObject
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.util.rotateAround

@Suppress("unused")
class ClientAnimationPlayer(
    private val animation: ParticleAnimation,
    private val origin: Vec3,
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

    private var currentTick = 0
    private val states = LinkedHashMap<String, ParticleState>()
    private var finished = false
    private var justLooped = false

    // ---- 调试统计 ----
    var lastAdvanceNanos: Long = 0; private set
    var frameCount: Long = 0; private set
    private var advanceNanosTotal = 0L
    private var advanceCount = 0L

    val avgAdvanceNanos: Long get() = if (advanceCount == 0L) 0L else advanceNanosTotal / advanceCount
    val particleCount: Int get() = states.size
    val currentTickValue: Int get() = currentTick
    val maxTickValue: Int get() = maxTick

    private val maxTick: Int = run {
        var max = animation.tracks.flatMap { it.keyframes }.maxOfOrNull { it.tick }?.toDouble() ?: 0.0
        // 粒子起始时间与有限寿命计入时长：晚出场/晚回收的粒子不能被截断
        for (p in animation.particles) {
            if (p.st > max) max = p.st.toDouble()
            if (p.life >= 0 && p.st + p.life > max) max = (p.st + p.life).toDouble()
        }
        for (fx in animation.functions) {
            // 函数对象跨度 = st + 自身 extent（变量关键帧 或 依赖 t 时的 duration）
            var extent = 0.0
            var hasVarAnim = false
            for (v in fx.vars.values) {
                val kfMax = v.kf.maxOfOrNull { it.tick } ?: continue
                hasVarAnim = true
                if (kfMax > extent) extent = kfMax
            }
            if (!hasVarAnim && usesTimeVar(fx.code)) extent = maxOf(extent, fx.duration.toDouble())
            if (fx.st + extent > max) max = fx.st + extent
        }
        max.toInt()
    }

    // 静态动画（无轨道/时间轴，且公式与变量均不含 random()）：init 已算好 t=0 状态，每 tick 无需重算。
    // 5w 粒子的静态粒子云若每刻重算会白费约 70ms/tick。
    // 存在 st 门控或入场预设时必然随时间变化，强制按动态处理。
    private val isStaticAnimation: Boolean = run {
        if (maxTick > 0) return@run false
        if (animation.particles.any { it.st > 0 || it.ent != null }) return@run false
        if (animation.functions.any { it.st > 0 || it.ent != null }) return@run false
        animation.functions.none { fx -> usesRandom(fx.code) }
    }

    // ---- 预构建求值索引（避免每 tick 线性扫描轨道 / 组 / 粒子） ----
    private val trackIndex: Map<String, Map<String, AnimTrack>> = buildTrackIndex()
    private val opTracks: List<AnimTrack> = animation.tracks.filter { it.mode == AnimTrack.Mode.OP }
    private val opTracksByPr: Map<String, List<AnimTrack>> = opTracks.filter { it.keyframes.isNotEmpty() }.groupBy { it.pr }
    private val groupSets: Map<String, Set<String>> = animation.groups.mapValues { (_, v) -> v.toSet() }
    private val particleGroupIndex: Map<String, Set<String>> = buildParticleGroupIndex()
    private val groupCentroidCache: Map<String, Vec3> = buildGroupCentroids()
    private val particleFxCache: Map<String, FunctionObject?> = buildParticleFxCache()

    // ---- 函数对象纯标量快路径编译缓存（null = 含向量/矩阵，回退通用解释器） ----
    private val compiledFunctions: MutableMap<String, CompiledFunction?> = buildCompiledFunctions()

    // 视觉会随时间变化的普通粒子（有轨道/速度/入场过渡）；其余静态粒子每 tick 只更新可见性。
    private val dynamicParticleIds: Set<String> = buildDynamicParticleIds()

    // 函数对象纯标量求值缓冲复用：regs/stack 每次 eval 整体重写，跨 tick 复用安全。
    private val fxBuffers = HashMap<String, Pair<DoubleArray, DoubleArray>>()

    private fun buildCompiledFunctions(): MutableMap<String, CompiledFunction?> {
        val map = HashMap<String, CompiledFunction?>()
        for (fx in animation.functions) {
            val varDefs = fx.vars.map { (name, v) -> VarDef(name, v.base, v.kf) }
            map[fx.id] = compileFunctionObject(fx.code, varDefs)
        }
        return map
    }

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

    private fun buildParticleFxCache(): Map<String, FunctionObject?> {
        val map = HashMap<String, FunctionObject?>()
        for (p in animation.particles) map[p.id] = null
        for (fx in animation.functions) {
            for (i in 0 until fx.count) map[fx.id + ":p" + i] = fx
        }
        return map
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
        for (fx in animation.functions) {
            for (i in 0 until fx.count) {
                val id = fx.id + ":p" + i
                val base = evaluateFunctionParticle(fx, i, fx.count, 0.0)
                states[id] = ParticleState(id, origin.add(base.first), base.second, base.third, base.fourth, base.fifth, resolveUV(id, fx.uv))
            }
        }
        advanceTo(0.0)
    }

    fun tick(): Boolean {
        if (finished) return false
        frameCount++
        currentTick++
        if (currentTick > maxTick) {
            if (animation.loop) { currentTick = 0; justLooped = true }
            else { finished = true; return false }
        }
        if (!isStaticAnimation) {
            val t0 = System.nanoTime()
            advanceTo(currentTick.toDouble())
            val elapsed = System.nanoTime() - t0
            lastAdvanceNanos = elapsed
            advanceNanosTotal += elapsed
            advanceCount++
        }
        return true
    }

    fun isFinished(): Boolean = finished
    fun consumeJustLooped(): Boolean { val v = justLooped; justLooped = false; return v }
    fun isStatic(): Boolean = isStaticAnimation

    private fun usesTimeVar(code: String): Boolean = Regex("\\bt\\b").containsMatchIn(code)
    private fun usesRandom(code: String): Boolean = Regex("\\brandom\\s*\\(").containsMatchIn(code)
    fun currentStates(): Collection<ParticleState> = states.values
    fun stop() { finished = true }

    fun updateVariable(name: String, value: String) {
        for (fx in animation.functions) {
            val v = fx.vars[name] ?: continue
            v.base = value.toDoubleOrNull() ?: 0.0
            v.kf = emptyList()
            val varDefs = fx.vars.map { (n, vv) -> VarDef(n, vv.base, vv.kf) }
            compiledFunctions[fx.id] = compileFunctionObject(fx.code, varDefs)
            fxBuffers.remove(fx.id) // 重编译可能改变 reg/stack 尺寸，旧缓冲不可复用
            return
        }
    }

    private fun advanceTo(t: Double) {
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
        for (fx in animation.functions) {
            val fxLocalT = t - fx.st
            val cf = compiledFunctions[fx.id]
            val bufs = if (cf != null) fxBuffers.getOrPut(fx.id) { cf.allocRegs() to cf.allocStack() } else null
            val regs = bufs?.first
            val stack = bufs?.second
            val cx = fx.center[0]; val cy = fx.center[1]; val cz = fx.center[2]
            // 整体变换 / op 增量 / 整体缩放 每 tick 只算一次（与粒子序号无关）
            val rx = scalarAt("rot.x", "f:" + fx.id, t, 0.0)
            val ry = scalarAt("rot.y", "f:" + fx.id, t, 0.0)
            val rz = scalarAt("rot.z", "f:" + fx.id, t, 0.0)
            val hasRot = rx != 0.0 || ry != 0.0 || rz != 0.0
            val rotPivot = Vec3(cx, cy, cz)
            val rot = doubleArrayOf(rx, ry, rz)
            val dx = opDeltaAt("pos.x", "f:" + fx.id, t)
            val dy = opDeltaAt("pos.y", "f:" + fx.id, t)
            val dz = opDeltaAt("pos.z", "f:" + fx.id, t)
            val n = fx.count.toDouble()
            for (i in 0 until fx.count) {
                val id = fx.id + ":p" + i
                val s = states[id] ?: continue
                if (cf != null) {
                    cf.eval(i.toDouble(), n, t, regs!!, stack!!)
                    var px = regs[Reg.X] + cx
                    var py = regs[Reg.Y] + cy
                    var pz = regs[Reg.Z] + cz
                    if (hasRot) {
                        val rotated = rotateAround(Vec3(px, py, pz), rotPivot, rot)
                        px = rotated.x; py = rotated.y; pz = rotated.z
                    }
                    px += dx; py += dy; pz += dz
                    s.pos = origin.add(px, py, pz)
                    val baseA = regs[Reg.A].coerceIn(0.0, 1.0).toFloat()
                    s.color = Color.of(
                        regs[Reg.R].coerceIn(0.0, 1.0).toFloat(),
                        regs[Reg.G].coerceIn(0.0, 1.0).toFloat(),
                        regs[Reg.B].coerceIn(0.0, 1.0).toFloat(),
                        (baseA * entranceFactor(fx.ent, fxLocalT)),
                    )
                    val scaleRaw = if (regs[Reg.SC].isFinite()) regs[Reg.SC] else 1.0
                    s.scale = fxScale(fx.id, scaleRaw, t)
                    s.glowing = regs[Reg.GLOW] > 0.5
                    s.lightLevel = regs[Reg.LIGHT].toInt().coerceIn(0, 15)
                    // 逐粒子寿命：代码里 maxAge = ...（tick；<0/未写=无限）
                    val maxAge = regs[Reg.MAXAGE]
                    s.visible = fxLocalT >= 0 && run {
                        val lifeOverride = if (maxAge.isFinite() && maxAge >= 0) maxAge else -1.0
                        lifeOverride < 0 || fxLocalT < lifeOverride
                    }
                } else {
                    // 通用解释器回退路径：不支持逐粒子 maxAge，仅函数对象级 st 门控
                    val base = evaluateFunctionParticle(fx, i, fx.count, t)
                    var pos = base.first
                    if (hasRot) pos = rotateAround(pos, rotPivot, rot)
                    pos = Vec3(pos.x + dx, pos.y + dy, pos.z + dz)
                    s.pos = origin.add(pos)
                    s.color = applyEntrance(base.second, fx.ent, fxLocalT)
                    s.scale = fxScale(fx.id, base.third[0].toDouble(), t)
                    s.glowing = base.fourth
                    s.lightLevel = base.fifth
                    s.visible = fxLocalT >= 0
                }
            }
        }
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

    private fun evaluateFunctionParticle(fx: FunctionObject, i: Int, n: Int, t: Double): Five<Vec3, Color, FloatArray, Boolean, Int> {
        val env = buildEnv(fx.vars, i, n, t)
        val out = ExpressionEvaluator.evalFunctionCode(fx.code, env)
        val center = fx.center
        val clamp01 = { v: Double -> v.coerceIn(0.0, 1.0) }
        val pos = Vec3(out.pos.x + center[0], out.pos.y + center[1], out.pos.z + center[2])
        val color = Color.of(clamp01(out.color[0]).toFloat(), clamp01(out.color[1]).toFloat(), clamp01(out.color[2]).toFloat(), clamp01(out.color[3]).toFloat())
        val s = if (out.scale.isFinite()) out.scale.toFloat().coerceAtLeast(0.01f) else 1f
        val scale = floatArrayOf(s, s, s)
        val light = out.light.toInt().coerceIn(0, 15)
        return Five(pos, color, scale, out.glow, light)
    }

    private fun buildEnv(vars: Map<String, FunctionVar>, i: Int, n: Int, t: Double): Map<String, Any> {
        val env = HashMap<String, Any>()
        env["i"] = i.toDouble()
        env["n"] = n.toDouble()
        env["t"] = t
        for ((name, v) in vars) {
            if (name in ATTR_NAMES) throw IllegalArgumentException("变量名 " + name + " 是属性保留字")
            env[name] = if (v.kf.isNotEmpty()) ExpressionEvaluator.varKfValue(v.kf, t) else v.base
        }
        return env
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

    private fun particleFunction(id: String): FunctionObject? = particleFxCache[id]

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
        pos = applyGroupRotation(p, pos, t)
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

    private fun applyGroupRotation(p: AnimParticle, value: Vec3, t: Double): Vec3 {
        val gs = particleGroupIndex[p.id]
        if (gs != null) {
            for (gname in gs) {
                val rot = rotVectorAt("g:" + gname, t)
                if (rot[0] == 0.0 && rot[1] == 0.0 && rot[2] == 0.0) return value
                return rotateAround(value, groupCentroidCache[gname] ?: groupCentroid(gname), rot)
            }
        }
        val fx = particleFunction(p.id)
        if (fx != null) {
            val rot = rotVectorAt("f:" + fx.id, t)
            if (rot[0] == 0.0 && rot[1] == 0.0 && rot[2] == 0.0) return value
            return rotateAround(value, Vec3(fx.center[0], fx.center[1], fx.center[2]), rot)
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

    private data class Five<A, B, C, D, E>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E)
}
