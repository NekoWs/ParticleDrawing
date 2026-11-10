package work.nekow.particledrawing.util

import work.nekow.particledrawing.api.ParticleStyle
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 内置形状贴图的像素生成：白底加 alpha 掩码，纯函数，不碰渲染层，便于单测。
 *
 * 两端生成同一份，内置形状不走网络下发，只传「用了哪个形状」。
 */
object BuiltinTextures {

    /**
     * 内置贴图边长，恒为 16：`BridgeParticle` 的贴图尺寸系数 = 最长边 / 16，
     * 16 让系数为 1，`scale` 与实际尺寸的换算不被内置贴图改变。
     */
    const val SIZE = 16

    /** 线段贴图上下柔边的半宽（占整高的比例）；越小线越细。 */
    private const val LINE_BAND = 0.5

    /** 线段贴图两端渐隐的长度（占整长的比例）。 */
    private const val LINE_END_FADE = 0.2

    /**
     * 生成 [style] 的 ARGB 像素，行主序，长度 size × size，格式 `0xAARRGGBB`，RGB 恒为白。
     * [ParticleStyle.SQUARE] 不生成贴图。
     */
    @JvmStatic
    @JvmOverloads
    fun pixels(style: ParticleStyle, size: Int = SIZE): IntArray {
        return when (style) {
            ParticleStyle.SQUARE -> IntArray(0)
            ParticleStyle.SOFT_DOT -> softDot(size)
            ParticleStyle.LINE -> line(size)
        }
    }

    /** 柔边圆点：半径方向 smoothstep 衰减，中心实、边缘到 0。 */
    private fun softDot(size: Int): IntArray {
        val px = IntArray(size * size)
        val center = (size - 1) / 2.0
        val radius = (size - 1) / 2.0
        for (y in 0 until size) {
            for (x in 0 until size) {
                val dx = x - center
                val dy = y - center
                val d = sqrt(dx * dx + dy * dy) / radius
                px[y * size + x] = argb(smooth(1.0 - d))
            }
        }
        return px
    }

    /**
     * 线段：长轴（X）两端渐隐，短轴（Y）中间实、上下柔边。
     * 两个方向都按「贴图边到边 = 0..1」归一化，首末像素为 0。
     */
    private fun line(size: Int): IntArray {
        val px = IntArray(size * size)
        val span = (size - 1).toDouble()
        for (y in 0 until size) {
            for (x in 0 until size) {
                val u = x / span
                val v = y / span
                // 两端渐隐（左右各 LINE_END_FADE 的过渡）
                val ends = smooth(u / LINE_END_FADE) * smooth((1.0 - u) / LINE_END_FADE)
                // 上下柔边：到中线的距离占半高的比例越小越实
                val band = smooth(1.0 - abs(v - 0.5) / LINE_BAND)
                px[y * size + x] = argb(ends * band)
            }
        }
        return px
    }

    /** 白色 + 给定 alpha 的 ARGB。 */
    private fun argb(alpha: Double): Int {
        val a = (alpha.coerceIn(0.0, 1.0) * 255.0).roundToInt()
        return (a shl 24) or 0xFFFFFF
    }

    /** 0→0、1→1 的平滑过渡（smoothstep），中间无硬拐点。 */
    private fun smooth(t: Double): Double {
        val c = t.coerceIn(0.0, 1.0)
        return c * c * (3.0 - 2.0 * c)
    }
}
