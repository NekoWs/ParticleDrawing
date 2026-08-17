package work.nekow.particledrawing.core.easing

import java.util.Objects
import kotlin.math.abs

/**
 * 三次贝塞尔缓动曲线，由四个控制点参数定义。
 * 用于在粒子动画中计算平滑的过渡效果。
 *
 * @param x1 第一控制点的 X 坐标
 * @param y1 第一控制点的 Y 坐标
 * @param x2 第二控制点的 X 坐标
 * @param y2 第二控制点的 Y 坐标
 */
@Suppress("unused")
class EasingCurve(
    val x1: Double,
    val y1: Double,
    val x2: Double,
    val y2: Double
) {

    // 线性曲线（默认缓动）：直接返回 t，避免贝塞尔反解迭代
    private val linear = x1 == 0.0 && y1 == 0.0 && x2 == 1.0 && y2 == 1.0

    /**
     * 在给定进度下计算缓动值。
     * @param t 动画进度，范围 [0, 1]
     * @return 缓动后的值，范围 [0, 1]
     */
    fun evaluate(t: Float): Float {
        if (t <= 0f) return 0f
        if (t >= 1f) return 1f
        if (linear) return t
        return sampleCurveY(solveTForX(t.toDouble())).toFloat()
    }

    private fun sampleCurveX(t: Double): Double {
        val u = 1 - t
        return 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t
    }

    private fun sampleCurveY(t: Double): Double {
        val u = 1 - t
        return 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t
    }

    private fun sampleCurveDerivativeX(t: Double): Double {
        val u = 1 - t
        return 3 * u * u * x1 + 6 * u * t * (x2 - x1) + 3 * t * t * (1 - x2)
    }

    private fun solveTForX(x: Double): Double {
        var t = x
        for (i in 0 until 8) {
            val curX = sampleCurveX(t) - x
            if (abs(curX) < EPSILON) {
                return t
            }
            val d = sampleCurveDerivativeX(t)
            if (abs(d) < 1e-6) {
                break
            }
            t = (t - curX / d).coerceIn(0.0, 1.0)
        }

        var t0 = 0.0
        var t1 = 1.0
        for (i in 0 until MAX_ITERATIONS) {
            t = (t0 + t1) / 2.0
            if (sampleCurveX(t) - x < 0) {
                t0 = t
            } else {
                t1 = t
            }
        }
        return t
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EasingCurve) return false
        return x1.compareTo(other.x1) == 0
            && y1.compareTo(other.y1) == 0
            && x2.compareTo(other.x2) == 0
            && y2.compareTo(other.y2) == 0
    }

    override fun hashCode(): Int {
        return Objects.hash(x1, y1, x2, y2)
    }

    override fun toString(): String {
        return String.format("cubic-bezier(%.3f, %.3f, %.3f, %.3f)", x1, y1, x2, y2)
    }

    companion object {
        private const val EPSILON = 1e-7
        private const val MAX_ITERATIONS = 20

        /**
         * 从 CSS 格式的字符串解析缓动曲线。
         * @param css CSS cubic-bezier 格式字符串
         * @return 解析后的 EasingCurve 实例
         */
        @JvmStatic
        fun fromCss(css: String): EasingCurve {
            val inner = css.replace("cubic-bezier(", "").replace(")", "").trim()
            val parts = inner.split(",")
            require(parts.size == 4) { "Invalid CSS cubic-bezier: $css" }
            return EasingCurve(
                parts[0].trim().toDouble(),
                parts[1].trim().toDouble(),
                parts[2].trim().toDouble(),
                parts[3].trim().toDouble()
            )
        }
    }
}
