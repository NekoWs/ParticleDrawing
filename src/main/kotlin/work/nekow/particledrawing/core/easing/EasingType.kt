package work.nekow.particledrawing.core.easing

import kotlin.math.abs

/**
 * 缓动类型，可为预设或自定义三次贝塞尔曲线。
 * 支持序列化与反序列化以便网络传输。
 */
@Suppress("unused")
class EasingType private constructor(
    val name: String?,
    val ordinal: Int,
    val curve: EasingCurve,
    private val step: Boolean = false
) {

    /**
     * 在给定进度下计算缓动值。
     * @param t 动画进度，范围 [0, 1]
     * @return 缓动后的值
     */
    fun evaluate(t: Float): Float {
        if (step) return if (t >= 1f) 1f else 0f // 无缓动：阶跃（保持到下一关键帧）
        return curve.evaluate(t)
    }

    /**
     * 判断当前是否为预设缓动类型。
     * @return 若为预设则返回 true
     */
    fun isPreset(): Boolean = ordinal >= 0

    /** 判断是否为无缓动（阶跃）类型。 */
    fun isStep(): Boolean = step

    /**
     * 序列化为双精度数组以便网络传输。
     * @return 包含类型标识与控制点参数的数组
     */
    fun serialize(): DoubleArray {
        if (step) return doubleArrayOf(STEP_ORDINAL.toDouble(), 0.0, 0.0, 0.0, 0.0)
        return if (isPreset()) {
            doubleArrayOf(ordinal.toDouble(), 0.0, 0.0, 0.0, 0.0)
        } else {
            doubleArrayOf(-1.0, curve.x1, curve.y1, curve.x2, curve.y2)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EasingType) return false
        return ordinal == other.ordinal && curve == other.curve
    }

    override fun hashCode(): Int {
        return java.util.Objects.hash(ordinal, curve)
    }

    override fun toString(): String {
        if (step) return "EasingType.NONE"
        if (isPreset()) return "EasingType.$name"
        return String.format("EasingType.cubic-bezier(%.2f,%.2f,%.2f,%.2f)",
            curve.x1, curve.y1, curve.x2, curve.y2)
    }

    companion object {
        private const val STEP_ORDINAL = -2
        @JvmField val LINEAR       = EasingType("LINEAR",             0, EasingCurve(0.0,   0.0,   1.0,  1.0))
        @JvmField val EASE_IN      = EasingType("EASE_IN",            1, EasingCurve(0.42,  0.0,   1.0,  1.0))
        @JvmField val EASE_OUT     = EasingType("EASE_OUT",           2, EasingCurve(0.0,   0.0,   0.58, 1.0))
        @JvmField val EASE_IN_OUT  = EasingType("EASE_IN_OUT",        3, EasingCurve(0.42,  0.0,   0.58, 1.0))
        @JvmField val EASE_IN_QUAD    = EasingType("EASE_IN_QUAD",    4, EasingCurve(0.55,  0.085, 0.68, 0.53))
        @JvmField val EASE_OUT_QUAD   = EasingType("EASE_OUT_QUAD",   5, EasingCurve(0.25,  0.46,  0.45, 0.94))
        @JvmField val EASE_IN_OUT_QUAD= EasingType("EASE_IN_OUT_QUAD",6, EasingCurve(0.455, 0.03,  0.515,0.955))
        @JvmField val EASE_IN_CUBIC   = EasingType("EASE_IN_CUBIC",   7, EasingCurve(0.55,  0.055, 0.675,0.19))
        @JvmField val EASE_OUT_CUBIC  = EasingType("EASE_OUT_CUBIC",  8, EasingCurve(0.215, 0.61,  0.355,1.0))
        @JvmField val EASE_IN_OUT_CUBIC= EasingType("EASE_IN_OUT_CUBIC",9, EasingCurve(0.645, 0.045, 0.355,1.0))
        @JvmField val EASE_IN_BOUNCE  = EasingType("EASE_IN_BOUNCE", 10, EasingCurve(0.71,  0.01,  0.53, 1.61))
        @JvmField val EASE_OUT_BOUNCE = EasingType("EASE_OUT_BOUNCE",11, EasingCurve(0.29, -0.61,  0.47, 0.99))
        @JvmField val EASE_IN_ELASTIC = EasingType("EASE_IN_ELASTIC",12, EasingCurve(0.56,  0.01,  0.73, 1.61))
        @JvmField val EASE_OUT_ELASTIC= EasingType("EASE_OUT_ELASTIC",13, EasingCurve(0.25, -0.61,  0.44, 0.99))

        /** 无缓动（阶跃）：保持前一关键帧值直到下一关键帧。 */
        @JvmField val NONE = EasingType("NONE", STEP_ORDINAL, EasingCurve(0.0, 0.0, 1.0, 1.0), step = true)

        @JvmField val PRESETS: List<EasingType> = listOf(
            LINEAR, EASE_IN, EASE_OUT, EASE_IN_OUT,
            EASE_IN_QUAD, EASE_OUT_QUAD, EASE_IN_OUT_QUAD,
            EASE_IN_CUBIC, EASE_OUT_CUBIC, EASE_IN_OUT_CUBIC,
            EASE_IN_BOUNCE, EASE_OUT_BOUNCE,
            EASE_IN_ELASTIC, EASE_OUT_ELASTIC
        )
        private val PRESET_ARRAY: Array<EasingType> = PRESETS.toTypedArray()

        /**
         * 使用自定义贝塞尔控制点创建缓动类型。
         * @param x1 第一控制点的 X 坐标
         * @param y1 第一控制点的 Y 坐标
         * @param x2 第二控制点的 X 坐标
         * @param y2 第二控制点的 Y 坐标
         * @return 自定义 EasingType 实例
         */
        @JvmStatic
        fun custom(x1: Double, y1: Double, x2: Double, y2: Double): EasingType {
            return EasingType(null, -1, EasingCurve(x1, y1, x2, y2))
        }

        /**
         * 根据控制点匹配最近的预设，若无匹配则创建自定义类型。
         * @param x1 第一控制点的 X 坐标
         * @param y1 第一控制点的 Y 坐标
         * @param x2 第二控制点的 X 坐标
         * @param y2 第二控制点的 Y 坐标
         * @return 匹配的预设或自定义 EasingType 实例
         */
        @JvmStatic
        fun fromCurve(x1: Double, y1: Double, x2: Double, y2: Double): EasingType {
            for (preset in PRESETS) {
                val c = preset.curve
                if (closeEnough(c.x1, x1) && closeEnough(c.y1, y1)
                    && closeEnough(c.x2, x2) && closeEnough(c.y2, y2)) {
                    return preset
                }
            }
            return custom(x1, y1, x2, y2)
        }

        /**
         * 从序列化数据反序列化缓动类型。
         * @param data 包含类型标识与控制点参数的数组
         * @return 反序列化后的 EasingType 实例
         */
        @JvmStatic
        fun deserialize(data: DoubleArray): EasingType {
            val ordinal = data[0].toInt()
            if (ordinal == STEP_ORDINAL) return NONE
            if (ordinal in PRESET_ARRAY.indices) {
                return PRESET_ARRAY[ordinal]
            }
            return custom(data[1], data[2], data[3], data[4])
        }

        private fun closeEnough(a: Double, b: Double): Boolean {
            return abs(a - b) < 1e-6
        }
    }
}
