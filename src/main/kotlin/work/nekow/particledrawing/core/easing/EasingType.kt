package work.nekow.particledrawing.core.easing

@Suppress("unused")
class EasingType private constructor(
    val name: String?,
    val ordinal: Int,
    val curve: EasingCurve
) {

    fun evaluate(t: Float): Float = curve.evaluate(t)

    fun isPreset(): Boolean = ordinal >= 0

    fun serialize(): DoubleArray {
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
        if (isPreset()) return "EasingType.$name"
        return String.format("EasingType.cubic-bezier(%.2f,%.2f,%.2f,%.2f)",
            curve.x1, curve.y1, curve.x2, curve.y2)
    }

    companion object {
        val LINEAR       = EasingType("LINEAR",             0, EasingCurve(0.0,   0.0,   1.0,  1.0))
        val EASE_IN      = EasingType("EASE_IN",            1, EasingCurve(0.42,  0.0,   1.0,  1.0))
        val EASE_OUT     = EasingType("EASE_OUT",           2, EasingCurve(0.0,   0.0,   0.58, 1.0))
        val EASE_IN_OUT  = EasingType("EASE_IN_OUT",        3, EasingCurve(0.42,  0.0,   0.58, 1.0))
        val EASE_IN_QUAD    = EasingType("EASE_IN_QUAD",    4, EasingCurve(0.55,  0.085, 0.68, 0.53))
        val EASE_OUT_QUAD   = EasingType("EASE_OUT_QUAD",   5, EasingCurve(0.25,  0.46,  0.45, 0.94))
        val EASE_IN_OUT_QUAD= EasingType("EASE_IN_OUT_QUAD",6, EasingCurve(0.455, 0.03,  0.515,0.955))
        val EASE_IN_CUBIC   = EasingType("EASE_IN_CUBIC",   7, EasingCurve(0.55,  0.055, 0.675,0.19))
        val EASE_OUT_CUBIC  = EasingType("EASE_OUT_CUBIC",  8, EasingCurve(0.215, 0.61,  0.355,1.0))
        val EASE_IN_OUT_CUBIC= EasingType("EASE_IN_OUT_CUBIC",9, EasingCurve(0.645, 0.045, 0.355,1.0))
        val EASE_IN_BOUNCE  = EasingType("EASE_IN_BOUNCE", 10, EasingCurve(0.71,  0.01,  0.53, 1.61))
        val EASE_OUT_BOUNCE = EasingType("EASE_OUT_BOUNCE",11, EasingCurve(0.29, -0.61,  0.47, 0.99))
        val EASE_IN_ELASTIC = EasingType("EASE_IN_ELASTIC",12, EasingCurve(0.56,  0.01,  0.73, 1.61))
        val EASE_OUT_ELASTIC= EasingType("EASE_OUT_ELASTIC",13, EasingCurve(0.25, -0.61,  0.44, 0.99))

        val PRESETS: List<EasingType> = listOf(
            LINEAR, EASE_IN, EASE_OUT, EASE_IN_OUT,
            EASE_IN_QUAD, EASE_OUT_QUAD, EASE_IN_OUT_QUAD,
            EASE_IN_CUBIC, EASE_OUT_CUBIC, EASE_IN_OUT_CUBIC,
            EASE_IN_BOUNCE, EASE_OUT_BOUNCE,
            EASE_IN_ELASTIC, EASE_OUT_ELASTIC
        )
        private val PRESET_ARRAY: Array<EasingType> = PRESETS.toTypedArray()

        fun custom(x1: Double, y1: Double, x2: Double, y2: Double): EasingType {
            return EasingType(null, -1, EasingCurve(x1, y1, x2, y2))
        }

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

        fun deserialize(data: DoubleArray): EasingType {
            val ordinal = data[0].toInt()
            if (ordinal in 0 until PRESET_ARRAY.size) {
                return PRESET_ARRAY[ordinal]
            }
            return custom(data[1], data[2], data[3], data[4])
        }

        private fun closeEnough(a: Double, b: Double): Boolean {
            return Math.abs(a - b) < 1e-6
        }
    }
}
