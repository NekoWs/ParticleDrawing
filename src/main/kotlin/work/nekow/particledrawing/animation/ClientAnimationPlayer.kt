package work.nekow.particledrawing.animation

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.animation.expr.ATTR_NAMES
import work.nekow.particledrawing.animation.expr.ExpressionEvaluator
import work.nekow.particledrawing.animation.expr.Keyframe
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.api.ParticleStyle

/**
 * 客户端本地动画播放器：按游戏 tick 实时求值函数对象与轨道，生成粒子可视化状态。
 *
 * 与编辑器语义一致：
 * - 变量关键帧优先（b[2] 缓动），无帧时用表达式（可链式引用 i/n/t 与其它变量）。
 * - 公式代码块顺序执行，[x,y,z]=向量 拆包。
 * - 轨道缓动使用「关键帧 k 的 easing 控制 k-1→k」语义（工程文件内部语义）。
 */
class ClientAnimationPlayer(
    private val animation: ParticleAnimation,
    private val origin: Vec3,
) {

    /** 单个粒子的当前可视化状态。 */
    data class ParticleState(
        val id: String,
        val style: ParticleStyle,
        var pos: Vec3,
        var color: Color,
        var scale: Float,
        var glowing: Boolean,
        var lightLevel: Int,
    )

    private var currentTick = 0
    private val states = LinkedHashMap<String, ParticleState>()
    private var finished = false
    // 播放时长 = 轨道关键帧 + 函数对象 duration + 函数对象变量关键帧的最大 tick
    // （精简 .pdraw 后派生轨道不再烘焙，函数对象动画时长需从自身字段推导，否则 maxTick 会误判为 0）
    private val maxTick: Int = run {
        var max = animation.tracks.flatMap { it.keyframes }.maxOfOrNull { it.tick }?.toDouble() ?: 0.0
        for (fx in animation.functions) {
            if (fx.duration > max) max = fx.duration.toDouble()
            for (v in fx.vars.values) {
                val kfMax = v.kf.maxOfOrNull { it.tick } ?: continue
                if (kfMax > max) max = kfMax
            }
        }
        max.toInt()
    }

    init {
        // 物化独立粒子
        for (p in animation.particles) {
            states[p.id] = ParticleState(p.id, p.style, origin.add(p.pos), p.color, p.scale, p.glowing, p.lightLevel)
        }
        // 物化函数对象派生粒子
        for (fx in animation.functions) {
            for (i in 0 until fx.count) {
                val id = fx.id + ":p" + i
                val base = evaluateFunctionParticle(fx, i, fx.count, 0.0)
                states[id] = ParticleState(id, fx.style, origin.add(base.first), base.second, base.third, base.fourth, base.fifth)
            }
        }
        advanceTo(0.0)
    }

    /** 推进一 tick，返回是否仍在播放。 */
    fun tick(): Boolean {
        if (finished) return false
        currentTick++
        if (currentTick > maxTick) {
            if (animation.loop) {
                currentTick = 0
            } else {
                finished = true
                return false
            }
        }
        advanceTo(currentTick.toDouble())
        return true
    }

    fun isFinished(): Boolean = finished
    fun currentStates(): Collection<ParticleState> = states.values

    /** 立即停止（状态保留，由调用方清理渲染）。 */
    fun stop() { finished = true }

    /** 更新函数对象变量：改为表达式（清空关键帧），后续 tick 实时生效。按变量名匹配（跨函数对象）。 */
    fun updateVariable(name: String, value: String) {
        for (fx in animation.functions) {
            val v = fx.vars[name] ?: continue
            v.expr = value
            v.kf = emptyList()
            return
        }
    }

    // ------------------------------------------------------------------
    // 求值
    // ------------------------------------------------------------------

    /** 在指定 tick 计算所有粒子状态。 */
    private fun advanceTo(t: Double) {
        // 1. 独立粒子基础值（无函数对象）
        for (p in animation.particles) {
            val s = states[p.id] ?: continue
            val pos = particlePosition(p, t)
            val col = particleColor(p, t)
            val scl = particleScale(p, t)
            s.pos = origin.add(pos)
            s.color = col
            s.scale = scl
        }
        // 2. 函数对象派生粒子：实时求值 + 整体轨道
        for (fx in animation.functions) {
            for (i in 0 until fx.count) {
                val id = fx.id + ":p" + i
                val s = states[id] ?: continue
                val base = evaluateFunctionParticle(fx, i, fx.count, t)
                var pos = base.first
                // 整体位置 op 增量
                val opDelta = trackValue("pos", "f:" + fx.id, t, doubleArrayOf(0.0, 0.0, 0.0))
                if (trackMode("pos", "f:" + fx.id) == AnimTrack.Mode.OP) {
                    pos = Vec3(pos.x + opDelta[0], pos.y + opDelta[1], pos.z + opDelta[2])
                }
                // 整体旋转（绕 center）
                val rot = trackValue("rot", "f:" + fx.id, t, doubleArrayOf(0.0, 0.0, 0.0))
                if (rot[0] != 0.0 || rot[1] != 0.0 || rot[2] != 0.0) {
                    pos = rotateAround(pos, Vec3(fx.center[0], fx.center[1], fx.center[2]), rot)
                }
                // 整体缩放（set 倍数）
                var scale = base.third
                val sclTr = findTrack("scl", "f:" + fx.id)
                if (sclTr != null && sclTr.keyframes.isNotEmpty()) {
                    val sv = trackValueAt(sclTr, t, doubleArrayOf(scale.toDouble()))
                    scale = sv[0].toFloat().coerceAtLeast(0.01f)
                }
                s.pos = origin.add(pos)
                s.color = base.second
                s.scale = scale
                s.glowing = base.fourth
                s.lightLevel = base.fifth
            }
        }
    }

    /** 求值单个函数对象派生粒子的基础状态（不含整体轨道）。返回 pos/color/scale/glow/light。 */
    private fun evaluateFunctionParticle(fx: FunctionObject, i: Int, n: Int, t: Double): Five<Vec3, Color, Float, Boolean, Int> {
        val env = buildEnv(fx.vars, i, n, t)
        val out = ExpressionEvaluator.evalFunctionCode(fx.code, env)
        val center = fx.center
        val clamp01 = { v: Double -> v.coerceIn(0.0, 1.0) }
        val pos = Vec3(out.pos.x + center[0], out.pos.y + center[1], out.pos.z + center[2])
        val color = Color.of(clamp01(out.color[0]).toFloat(), clamp01(out.color[1]).toFloat(), clamp01(out.color[2]).toFloat(), clamp01(out.color[3]).toFloat())
        val scale = if (out.scale.isFinite()) out.scale.toFloat().coerceAtLeast(0.01f) else 1f
        val light = out.light.toInt().coerceIn(0, 15)
        return Five(pos, color, scale, out.glow, light)
    }

    /** 链式求值变量：关键帧优先按 t 插值，无帧用表达式（可引用 i/n/t 与其它变量）。 */
    private fun buildEnv(vars: Map<String, FunctionVar>, i: Int, n: Int, t: Double): Map<String, Any> {
        val env = HashMap<String, Any>()
        env["i"] = i.toDouble()
        env["n"] = n.toDouble()
        env["t"] = t
        val memo = HashMap<String, Any>()
        val inStack = HashSet<String>()

        fun resolve(name: String): Any {
            memo[name]?.let { return it }
            env[name]?.let { return it }
            val v = vars[name] ?: throw IllegalArgumentException("未知变量: $name")
            if (name in inStack) throw IllegalArgumentException("变量循环引用: $name")
            inStack.add(name)
            val value = if (v.kf.isNotEmpty()) {
                ExpressionEvaluator.varKfValue(v.kf, t)
            } else {
                val resolver = object : java.util.AbstractMap<String, Any>() {
                    override val entries: MutableSet<MutableMap.MutableEntry<String, Any>>
                        get() = mutableSetOf()
                    override fun get(key: String): Any? {
                        memo[key]?.let { return it }
                        env[key]?.let { return it }
                        return resolve(key)
                    }
                }
                ExpressionEvaluator.evaluate(v.expr, resolver)
            }
            inStack.remove(name)
            memo[name] = value
            return value
        }

        for (name in vars.keys) {
            if (name in ATTR_NAMES) throw IllegalArgumentException("变量名 $name 是属性保留字")
            resolve(name)
        }
        for ((k, v) in memo) env[k] = v
        return env
    }

    // ------------------------------------------------------------------
    // 轨道查询（工程文件内部语义：段 i→i+1 用关键帧 i+1 的缓动）
    // ------------------------------------------------------------------

    private fun findTrack(property: String, id: String): AnimTrack? =
        animation.tracks.find { it.property.wire == property && it.ids.size == 1 && it.ids[0] == id }

    private fun trackMode(property: String, id: String): AnimTrack.Mode? = findTrack(property, id)?.mode

    private fun trackValue(property: String, id: String, t: Double, fallback: DoubleArray): DoubleArray {
        val tr = findTrack(property, id) ?: return fallback
        return trackValueAt(tr, t, fallback)
    }

    private fun trackValueAt(tr: AnimTrack, t: Double, fallback: DoubleArray): DoubleArray {
        val kfs = tr.keyframes
        if (kfs.isEmpty()) return fallback
        if (t <= kfs[0].tick) return kfs[0].value
        if (t >= kfs.last().tick) return kfs.last().value
        for (idx in 0 until kfs.size - 1) {
            val a = kfs[idx]; val b = kfs[idx + 1]
            if (t >= a.tick && t <= b.tick) {
                val dur = (b.tick - a.tick).toDouble()
                val f = if (dur == 0.0) 1.0 else (t - a.tick) / dur
                val e = b.easing.evaluate(f.toFloat()).toDouble()
                val out = DoubleArray(a.value.size)
                for (j in a.value.indices) out[j] = a.value[j] + (b.value[j] - a.value[j]) * e
                return out
            }
        }
        return kfs.last().value
    }

    /** 独立粒子的位置（含粒子 set 轨道覆盖 + 组/函数对象 op 增量）。 */
    private fun particlePosition(p: AnimParticle, t: Double): Vec3 {
        var pos = p.pos
        // 粒子自身 set 轨道覆盖
        val ownSet = findTrack("pos", p.id)
        if (ownSet != null && ownSet.mode == AnimTrack.Mode.SET && ownSet.keyframes.isNotEmpty()) {
            val v = trackValueAt(ownSet, t, doubleArrayOf(pos.x, pos.y, pos.z))
            pos = Vec3(v[0], v[1], v[2])
        }
        // 组 op 增量
        for ((gname, members) in animation.groups) {
            if (p.id !in members) continue
            val tr = findTrack("pos", "g:" + gname)
            if (tr != null && tr.mode == AnimTrack.Mode.OP) {
                val d = trackValueAt(tr, t, doubleArrayOf(0.0, 0.0, 0.0))
                pos = pos.add(d[0], d[1], d[2])
            }
        }
        return pos
    }

    private fun particleColor(p: AnimParticle, t: Double): Color {
        val tr = findTrack("col", p.id)
        if (tr != null && tr.keyframes.isNotEmpty()) {
            val v = trackValueAt(tr, t, doubleArrayOf(p.color.r.toDouble(), p.color.g.toDouble(), p.color.b.toDouble(), p.color.a.toDouble()))
            return Color.of(v[0].toFloat(), v[1].toFloat(), v[2].toFloat(), v[3].toFloat())
        }
        return p.color
    }

    private fun particleScale(p: AnimParticle, t: Double): Float {
        val tr = findTrack("scl", p.id)
        if (tr != null && tr.keyframes.isNotEmpty()) {
            val v = trackValueAt(tr, t, doubleArrayOf(p.scale.toDouble()))
            return v[0].toFloat().coerceAtLeast(0.01f)
        }
        return p.scale
    }

    // ------------------------------------------------------------------
    // 旋转
    // ------------------------------------------------------------------

    private fun rotateAround(p: Vec3, pivot: Vec3, rot: DoubleArray): Vec3 {
        var r = p.subtract(pivot)
        if (rot[0] != 0.0) r = rotateVec(r, Vec3(1.0, 0.0, 0.0), Math.toRadians(rot[0]))
        if (rot[1] != 0.0) r = rotateVec(r, Vec3(0.0, 1.0, 0.0), Math.toRadians(rot[1]))
        if (rot[2] != 0.0) r = rotateVec(r, Vec3(0.0, 0.0, 1.0), Math.toRadians(rot[2]))
        return pivot.add(r)
    }

    private fun rotateVec(v: Vec3, axis: Vec3, angle: Double): Vec3 {
        val c = Math.cos(angle); val s = Math.sin(angle)
        val dot = v.x * axis.x + v.y * axis.y + v.z * axis.z
        return Vec3(
            v.x * c + (axis.y * v.z - axis.z * v.y) * s + axis.x * dot * (1 - c),
            v.y * c + (axis.z * v.x - axis.x * v.z) * s + axis.y * dot * (1 - c),
            v.z * c + (axis.x * v.y - axis.y * v.x) * s + axis.z * dot * (1 - c),
        )
    }

    /** 五元组辅助。 */
    private data class Five<A, B, C, D, E>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E)

    private val AnimTrack.Property.wire: String
        get() = when (this) {
            AnimTrack.Property.POSITION -> "pos"
            AnimTrack.Property.ROTATION -> "rot"
            AnimTrack.Property.VELOCITY -> "vel"
            AnimTrack.Property.COLOR -> "col"
            AnimTrack.Property.SCALE -> "scl"
        }
}
