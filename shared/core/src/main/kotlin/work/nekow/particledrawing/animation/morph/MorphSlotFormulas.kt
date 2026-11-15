package work.nekow.particledrawing.animation.morph

import work.nekow.particledrawing.animation.script.ScriptException
import work.nekow.particledrawing.animation.script.ScriptRuntime
import work.nekow.particledrawing.animation.script.Vec3 as ScriptVec3
import work.nekow.particledrawing.math.Vec3
import work.nekow.particledrawing.i18n.LocalizedArgumentException
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.translation

/**
 * 用公式描述的一整套槽位。
 *
 * 每个槽位按序号求值：变量 `i`（序号）、`n`（总数）、`u`（i/(n-1)）、`t`（形变毫秒）。
 * 位置必须是能取三个分量的向量（`vec3(...)`）；朝向、尺寸、控制点可选。
 * [build] 可以被每 tick 调用（动态目标），也可以只在开始时调一次（静态快照）。
 */
class MorphSlotFormulas(
    val count: Int,
    private val position: String,
    private val yaw: String? = null,
    private val pitch: String? = null,
    private val size: String? = null,
    private val control1: String? = null,
    private val control2: String? = null,
) {
    init {
        requireLocalized(count >= 1) { translation("error.morph.slots") }
        requireLocalized(position.isNotBlank()) { translation("error.morph.position_formula") }
    }

    private val posRunner = runner(position, "position")
    private val yawRunner = yaw?.let { runner(it, "yaw") }
    private val pitchRunner = pitch?.let { runner(it, "pitch") }
    private val sizeRunner = size?.let { runner(it, "size") }
    private val c1Runner = control1?.let { runner(it, "control1") }
    private val c2Runner = control2?.let { runner(it, "control2") }

    private val values = HashMap<String, Any?>(8)
    private val ctx = ScriptRuntime.ProcessCtx(0.0, 1.0, 0.0, 0.0, 1.0, 0.0, 0.0, values)

    /** 按当前形变时间（毫秒）算出整套槽位。 */
    fun build(tMs: Double): List<MorphSlot> {
        val n = count.toDouble()
        val out = ArrayList<MorphSlot>(count)
        for (i in 0 until count) {
            val u = if (count == 1) 0.0 else i / (n - 1.0)
            values.clear()
            values["i"] = i.toDouble()
            values["n"] = n
            values["u"] = u
            values["t"] = tMs
            try {
                val p = vec(posRunner.evalAny(ctx), "position")
                out += MorphSlot(
                    position = p,
                    yaw = yawRunner?.let { scalar(it.eval(ctx)) } ?: Double.NaN,
                    pitch = pitchRunner?.let { scalar(it.eval(ctx)) } ?: Double.NaN,
                    size = sizeRunner?.let {
                        val value = scalar(it.eval(ctx))
                        requireLocalized(value in 0.0..Float.MAX_VALUE.toDouble()) { translation("error.morph.slot_size") }
                        value
                    } ?: Double.NaN,
                    control1 = c1Runner?.let { vec(it.evalAny(ctx), "control1") },
                    control2 = c2Runner?.let { vec(it.evalAny(ctx), "control2") },
                )
            } catch (e: ScriptException) {
                throw LocalizedArgumentException(translation("error.morph.slot_evaluation", i, e.message), e)
            }
        }
        return out
    }

    private fun runner(expr: String, field: String): ScriptRuntime.ExpressionRunner {
        requireLocalized(expr.isNotBlank()) { translation("error.morph.empty_formula", field) }
        return try {
            ScriptRuntime.ExpressionRunner(expr)
        } catch (e: RuntimeException) {
            throw LocalizedArgumentException(translation("error.morph.invalid_formula", field, e.message), e)
        }
    }

    private fun scalar(v: Double): Double {
        requireLocalized(v.isFinite()) { translation("error.morph.slot_nonfinite") }
        return v
    }

    private fun vec(v: Any?, field: String): Vec3 {
        val point = when (v) {
            is Vec3 -> v
            is ScriptVec3 -> Vec3(v.x, v.y, v.z)
            is List<*> -> {
                requireLocalized(v.size >= 3) { translation("error.morph.slot_components", field) }
                Vec3(
                    (v[0] as? Number)?.toDouble() ?: throw LocalizedArgumentException(translation("error.morph.slot_x", field)),
                    (v[1] as? Number)?.toDouble() ?: throw LocalizedArgumentException(translation("error.morph.slot_y", field)),
                    (v[2] as? Number)?.toDouble() ?: throw LocalizedArgumentException(translation("error.morph.slot_z", field)),
                )
            }
            else -> throw LocalizedArgumentException(translation("error.morph.slot_vector", field))
        }
        requireLocalized(point.x.isFinite() && point.y.isFinite() && point.z.isFinite()) { translation("error.morph.slot_finite", field) }
        return point
    }
}
