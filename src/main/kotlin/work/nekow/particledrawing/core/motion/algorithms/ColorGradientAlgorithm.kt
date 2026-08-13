package work.nekow.particledrawing.core.motion.algorithms

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.core.motion.MotionAlgorithm
import work.nekow.particledrawing.core.motion.at
import kotlin.math.sqrt

/**
 * 通用渐变着色算法：将粒子相对 pivot 的坐标沿渐变方向投影，映射到颜色渐变。
 *
 * 渐变方向支持三种坐标轴（X / Y / Z）或任意自定义方向向量，可自由调整渐变角度。
 *
 * 参数布局（DoubleArray，缺省值见括号）：
 * ```
 * [0]  axis   渐变方向：0 = X, 1 = Y（默认）, 2 = Z, 3 = 自定义向量 [dx,dy,dz]
 * [1]  mode   颜色模式：0 = HSB 色相渐变（默认）, 1 = RGB 双色渐变
 * [2]  min    渐变下界，沿渐变方向相对 pivot 的坐标（默认 -1）
 * [3]  max    渐变上界，沿渐变方向相对 pivot 的坐标（默认 1）
 * [4]  hueStart | r0   HSB 起始色相（默认 0.0）| RGB 起始红（默认 1.0）
 * [5]  hueEnd   | g0   HSB 结束色相（默认 1.0）| RGB 起始绿（默认 0.0）
 * [6]  sat      | b0   HSB 饱和度（默认 0.9）  | RGB 起始蓝（默认 0.0）
 * [7]  bri      | r1   HSB 亮度（默认 0.9）    | RGB 结束红（默认 0.0）
 * [8]  -        | g1   （HSB 保留）            | RGB 结束绿（默认 0.0）
 * [9]  -        | b1   （HSB 保留）            | RGB 结束蓝（默认 1.0）
 * [10] alpha   透明度（默认 1.0）
 * [11] dx      自定义方向 X 分量（默认 0）
 * [12] dy      自定义方向 Y 分量（默认 1）
 * [13] dz      自定义方向 Z 分量（默认 0）
 * ```
 */
class ColorGradientAlgorithm(params: DoubleArray) : MotionAlgorithm {
    override val id = ID

    private val axis = params.at(0, 1.0).toInt()
    private val mode = params.at(1, 0.0).toInt()
    private val min = params.at(2, -1.0)
    private val max = params.at(3, 1.0)
    private val alpha = params.at(10, 1.0).toFloat()

    private val dirX = params.at(11, 0.0)
    private val dirY = params.at(12, 1.0)
    private val dirZ = params.at(13, 0.0)

    // HSB 模式参数
    private val hueStart = params.at(4, 0.0)
    private val hueEnd = params.at(5, 1.0)
    private val saturation = params.at(6, 0.9).toFloat()
    private val brightness = params.at(7, 0.9).toFloat()

    // RGB 模式参数
    private val startColor = Color.of(
        params.at(4, 1.0).toFloat(), params.at(5, 0.0).toFloat(), params.at(6, 0.0).toFloat())
    private val endColor = Color.of(
        params.at(7, 0.0).toFloat(), params.at(8, 0.0).toFloat(), params.at(9, 1.0).toFloat())

    private fun direction(): Vec3 = when (axis) {
        AXIS_X -> Vec3(1.0, 0.0, 0.0)
        AXIS_Z -> Vec3(0.0, 0.0, 1.0)
        AXIS_CUSTOM -> {
            val len = sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ)
            if (len < 1e-6) Vec3(0.0, 1.0, 0.0) else Vec3(dirX / len, dirY / len, dirZ / len)
        }
        else -> Vec3(0.0, 1.0, 0.0)
    }

    override fun compute(basePos: Vec3, pivot: Vec3, elapsedSeconds: Double): MotionAlgorithm.Result {
        val v = basePos.subtract(pivot).dot(direction())
        val span = max - min
        val t = if (span != 0.0) ((v - min) / span).coerceIn(0.0, 1.0) else 0.5

        val color = if (mode == MODE_RGB) {
            startColor.lerp(endColor, t.toFloat()).withAlpha(alpha)
        } else {
            val hue = (hueStart + (hueEnd - hueStart) * t).toFloat()
            Color.ofHsb(hue, saturation, brightness, alpha)
        }
        return MotionAlgorithm.Result(color = color)
    }

    companion object {
        const val ID = "color_gradient"

        const val MODE_HSB = 0
        const val MODE_RGB = 1

        const val AXIS_X = 0
        const val AXIS_Y = 1
        const val AXIS_Z = 2
        const val AXIS_CUSTOM = 3

        /** 构造 HSB 色相渐变的参数。可通过 [axis] 指定坐标轴，或通过 [direction] 指定任意渐变方向。 */
        fun hsbParams(
            axis: Int = AXIS_Y,
            direction: Vec3? = null,
            hueStart: Double = 0.0,
            hueEnd: Double = 1.0,
            saturation: Double = 0.9,
            brightness: Double = 0.9,
            min: Double = -1.0,
            max: Double = 1.0,
            alpha: Double = 1.0
        ): DoubleArray {
            val useAxis = if (direction != null) AXIS_CUSTOM else axis
            val dx = direction?.x ?: 0.0
            val dy = direction?.y ?: 0.0
            val dz = direction?.z ?: 0.0
            return doubleArrayOf(
                useAxis.toDouble(), MODE_HSB.toDouble(), min, max,
                hueStart, hueEnd, saturation, brightness, 0.0, 0.0, alpha,
                dx, dy, dz
            )
        }

        /** 构造 RGB 双色渐变的参数。可通过 [axis] 指定坐标轴，或通过 [direction] 指定任意渐变方向。 */
        fun rgbParams(
            axis: Int = AXIS_Y,
            direction: Vec3? = null,
            start: Color,
            end: Color,
            min: Double = -1.0,
            max: Double = 1.0,
            alpha: Double = 1.0
        ): DoubleArray {
            val useAxis = if (direction != null) AXIS_CUSTOM else axis
            val dx = direction?.x ?: 0.0
            val dy = direction?.y ?: 0.0
            val dz = direction?.z ?: 0.0
            return doubleArrayOf(
                useAxis.toDouble(), MODE_RGB.toDouble(), min, max,
                start.r.toDouble(), start.g.toDouble(), start.b.toDouble(),
                end.r.toDouble(), end.g.toDouble(), end.b.toDouble(), alpha,
                dx, dy, dz
            )
        }
    }
}
