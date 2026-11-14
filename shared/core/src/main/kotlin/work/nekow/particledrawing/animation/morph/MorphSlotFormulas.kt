package work.nekow.particledrawing.animation.morph

import work.nekow.particledrawing.animation.script.ScriptRuntime
import work.nekow.particledrawing.animation.script.Vec3 as ScriptVec3
import work.nekow.particledrawing.math.Vec3

/**
 * 用公式描述的一整套槽位。
 *
 * 每个槽位按序号求值：变量 `i`（序号）、`n`（总数）、`u`（i/n）、`t`（形变毫秒）。
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
        require(count >= 1) { "槽位数量至少为 1" }
        require(position.isNotBlank()) { "槽位位置公式不能为空" }
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
            val p = vec(posRunner.evalAny(ctx), "position")
            out += MorphSlot(
                position = p,
                yaw = yawRunner?.let { scalar(it.eval(ctx)) } ?: Double.NaN,
                pitch = pitchRunner?.let { scalar(it.eval(ctx)) } ?: Double.NaN,
                size = sizeRunner?.let { scalar(it.eval(ctx)) } ?: Double.NaN,
                control1 = c1Runner?.let { vec(it.evalAny(ctx), "control1") },
                control2 = c2Runner?.let { vec(it.evalAny(ctx), "control2") },
            )
        }
        return out
    }

    private fun runner(expr: String, field: String): ScriptRuntime.ExpressionRunner {
        require(expr.isNotBlank()) { "槽位字段 '$field' 的公式为空" }
        return try {
            ScriptRuntime.ExpressionRunner(expr)
        } catch (e: RuntimeException) {
            throw IllegalArgumentException("槽位字段 '$field' 的公式无法解析: ${e.message}", e)
        }
    }

    private fun scalar(v: Double): Double {
        require(v.isFinite()) { "槽位公式产生了非有限数" }
        return v
    }

    private fun vec(v: Any?, field: String): Vec3 = when (v) {
        is Vec3 -> v
        // 脚本表达式里的 vec3(...) 是脚本值类型，跨到核心模型要转一次。
        is ScriptVec3 -> Vec3(v.x, v.y, v.z)
        is List<*> -> {
            require(v.size >= 3) { "槽位字段 '$field' 的列表至少要有三个分量" }
            Vec3(
                (v[0] as? Number)?.toDouble() ?: throw IllegalArgumentException("槽位字段 '$field' 的第 1 个分量不是数"),
                (v[1] as? Number)?.toDouble() ?: throw IllegalArgumentException("槽位字段 '$field' 的第 2 个分量不是数"),
                (v[2] as? Number)?.toDouble() ?: throw IllegalArgumentException("槽位字段 '$field' 的第 3 个分量不是数"),
            )
        }
        else -> throw IllegalArgumentException("槽位字段 '$field' 需要 vec3(...) 形式的三维向量")
    }
}
