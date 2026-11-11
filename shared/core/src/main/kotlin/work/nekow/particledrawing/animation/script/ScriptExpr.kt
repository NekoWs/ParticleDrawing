package work.nekow.particledrawing.animation.script

import work.nekow.particledrawing.core.easing.EasingType

// 变量关键帧与标量求值的兼容入口。表达式解析统一走 ScriptParser / ScriptRuntime，
// 这里不再保留独立的词法与优先级实现。

/** 变量关键帧：毫秒时刻、值与缓动。 */
data class Keyframe(val ms: Double, val value: Double, val easing: EasingType)

/** 求值单个标量表达式；[vars] 为已解析的变量表。低频路径，解析与求值都走完整引擎。 */
fun evaluate(expr: String, vars: Map<String, Any>): Double {
    val ctx = ScriptRuntime.ProcessCtx(
        i = 0.0, n = 1.0, t = 0.0, dt = 1.0, life = 1.0,
        uv_x = 0.0, uv_y = 0.0, vars = vars,
    )
    return ScriptRuntime.ExpressionRunner(expr).eval(ctx)
}

/** 变量关键帧插值（段 i→i+1 用后一关键帧的缓动）。关键帧时刻为毫秒。 */
fun varKfValue(kf: List<Keyframe>, t: Double): Double {
    if (kf.isEmpty()) return 0.0
    if (t <= kf[0].ms) return kf[0].value
    if (t >= kf.last().ms) return kf.last().value
    for (i in 0 until kf.size - 1) {
        val a = kf[i]; val b = kf[i + 1]
        if (t >= a.ms && t <= b.ms) {
            val dur = b.ms - a.ms
            val f = if (dur == 0.0) 1.0 else (t - a.ms) / dur
            val e = b.easing.evaluate(f.toFloat()).toDouble()
            return a.value + (b.value - a.value) * e
        }
    }
    return kf.last().value
}
