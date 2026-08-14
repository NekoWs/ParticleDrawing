package work.nekow.particledrawing.api

/**
 * 不可变 RGBA 颜色，四个分量均为 `[0, 1]` 范围内的 [Float]。
 * 提供预设常量与工厂方法（[of]、[ofInt]、[ofPacked]、[ofHsb]）。
 */
@Suppress("unused")
class Color private constructor(
    val r: Float,
    val g: Float,
    val b: Float,
    val a: Float
) {
    val rInt get() = (r * 255).toInt()
    val gInt get() = (g * 255).toInt()
    val bInt get() = (b * 255).toInt()
    val aInt get() = (a * 255).toInt()

    fun withAlpha(alpha: Float) = Color(r, g, b, alpha)

    fun multiply(factor: Float) = Color(r * factor, g * factor, b * factor, a)

    fun lerp(target: Color, t: Float): Color {
        val ct = clamp(t)
        return Color(
            r + (target.r - r) * ct,
            g + (target.g - g) * ct,
            b + (target.b - b) * ct,
            a + (target.a - a) * ct
        )
    }

    fun packABGR(): Int {
        val ai = clampToInt(a * 255)
        val bi = clampToInt(b * 255)
        val gi = clampToInt(g * 255)
        val ri = clampToInt(r * 255)
        return (ai shl 24) or (bi shl 16) or (gi shl 8) or ri
    }

    fun packARGB(): Int {
        val ai = clampToInt(a * 255)
        val ri = clampToInt(r * 255)
        val gi = clampToInt(g * 255)
        val bi = clampToInt(b * 255)
        return (ai shl 24) or (ri shl 16) or (gi shl 8) or bi
    }

    fun luminance() = 0.2126f * r + 0.7152f * g + 0.0722f * b

    fun isOpaque() = a >= 1.0f

    fun isTransparent() = a <= 0.0f

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Color) return false
        return r.compareTo(other.r) == 0
            && g.compareTo(other.g) == 0
            && b.compareTo(other.b) == 0
            && a.compareTo(other.a) == 0
    }

    override fun hashCode(): Int {
        var result = r.hashCode()
        result = 31 * result + g.hashCode()
        result = 31 * result + b.hashCode()
        result = 31 * result + a.hashCode()
        return result
    }

    override fun toString() = "Color(r=%.3f, g=%.3f, b=%.3f, a=%.3f)".format(r, g, b, a)

    companion object {
        // 预设颜色常量
        val WHITE = Color(1.0f, 1.0f, 1.0f, 1.0f)
        val BLACK = Color(0.0f, 0.0f, 0.0f, 1.0f)
        val RED = Color(1.0f, 0.0f, 0.0f, 1.0f)
        val GREEN = Color(0.0f, 1.0f, 0.0f, 1.0f)
        val BLUE = Color(0.0f, 0.0f, 1.0f, 1.0f)
        val YELLOW = Color(1.0f, 1.0f, 0.0f, 1.0f)
        val CYAN = Color(0.0f, 1.0f, 1.0f, 1.0f)
        val MAGENTA = Color(1.0f, 0.0f, 1.0f, 1.0f)
        val ORANGE = Color(1.0f, 0.5f, 0.0f, 1.0f)
        val TRANSPARENT = Color(0.0f, 0.0f, 0.0f, 0.0f)

        /** 由浮点分量创建不透明颜色，分量范围 [0,1]。 */
        fun of(r: Float, g: Float, b: Float) = Color(r, g, b, 1.0f)

        /** 由浮点分量创建颜色（含透明度），分量范围 [0,1]。 */
        fun of(r: Float, g: Float, b: Float, a: Float) = Color(r, g, b, a)

        /** 由整数分量创建不透明颜色，分量范围 [0,255]。 */
        fun ofInt(r: Int, g: Int, b: Int) = Color(r / 255f, g / 255f, b / 255f, 1.0f)

        /** 由整数分量创建颜色（含透明度），分量范围 [0,255]。 */
        fun ofInt(r: Int, g: Int, b: Int, a: Int) = Color(r / 255f, g / 255f, b / 255f, a / 255f)

        /** 从 ABGR 打包整型还原颜色。 */
        fun ofPacked(abgr: Int): Color {
            val a = ((abgr shr 24) and 0xFF) / 255f
            val b = ((abgr shr 16) and 0xFF) / 255f
            val g = ((abgr shr 8) and 0xFF) / 255f
            val r = (abgr and 0xFF) / 255f
            return Color(r, g, b, a)
        }

        /** 由 HSB（色相/饱和度/亮度）创建不透明颜色。hue 自动取模，sat/bri 自动 clamp 到 [0,1]。 */
        fun ofHsb(hue: Float, saturation: Float, brightness: Float): Color {
            val rgb = java.awt.Color.HSBtoRGB(hue % 1.0f, clamp(saturation), clamp(brightness))
            return Color(
                ((rgb shr 16) and 0xFF) / 255f,
                ((rgb shr 8) and 0xFF) / 255f,
                (rgb and 0xFF) / 255f,
                1.0f
            )
        }

        /** 由 HSB（色相/饱和度/亮度）创建颜色（含透明度）。 */
        fun ofHsb(hue: Float, saturation: Float, brightness: Float, alpha: Float): Color {
            val c = ofHsb(hue, saturation, brightness)
            return Color(c.r, c.g, c.b, alpha)
        }

        private fun clamp(v: Float) = v.coerceIn(0f, 1f)

        private fun clampToInt(v: Float) = v.toInt().coerceIn(0, 255)
    }
}
