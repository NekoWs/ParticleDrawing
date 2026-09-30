package work.nekow.particledrawing

import work.nekow.particledrawing.api.ParticleStyle
import work.nekow.particledrawing.util.BuiltinTextures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 内置形状贴图的像素生成：柔边、对称、尺寸恰好 16（尺寸系数 = 1，`scale` 的换算不被贴图放大）。
 */
class BuiltinTexturesTest {

    private fun alpha(px: IntArray, x: Int, y: Int, size: Int = BuiltinTextures.SIZE): Int =
        (px[y * size + x] ushr 24) and 0xFF

    @Test
    fun `边长 16：贴图尺寸系数恰为 1`() {
        assertEquals(16, BuiltinTextures.SIZE)
        assertEquals(16 * 16, BuiltinTextures.pixels(ParticleStyle.SOFT_DOT).size)
        assertEquals(16 * 16, BuiltinTextures.pixels(ParticleStyle.LINE).size)
    }

    @Test
    fun `方块无贴图`() {
        assertEquals(0, BuiltinTextures.pixels(ParticleStyle.SQUARE).size)
    }

    @Test
    fun `柔边圆点：中心最实、边缘到 0、径向单调`() {
        val px = BuiltinTextures.pixels(ParticleStyle.SOFT_DOT)
        val size = BuiltinTextures.SIZE
        val c = size / 2
        assertTrue(alpha(px, c, c) >= 240, "中心应接近完全不透明（实际 ${alpha(px, c, c)}）")
        assertEquals(0, alpha(px, 0, 0), "角落应完全透明")
        assertEquals(0, alpha(px, size - 1, 0))
        // 沿半径向外单调不增
        var prev = alpha(px, c, c)
        for (d in 1 until c) {
            val a = alpha(px, c + d, c)
            assertTrue(a <= prev, "半径 $d 处 alpha（$a）不应大于内侧（$prev）")
            prev = a
        }
        // 中心行左右对称
        for (x in 0 until size) {
            assertEquals(alpha(px, x, c), alpha(px, size - 1 - x, c), "x=$x 左右应镜像")
        }
    }

    @Test
    fun `线段：两端渐隐、中线最实、上下对称`() {
        val px = BuiltinTextures.pixels(ParticleStyle.LINE)
        val size = BuiltinTextures.SIZE
        val mid = size / 2
        assertEquals(0, alpha(px, 0, mid), "左端应渐隐到 0")
        assertEquals(0, alpha(px, size - 1, mid), "右端应渐隐到 0")
        assertTrue(alpha(px, mid, mid) > 200, "中线应接近全实")
        assertTrue(alpha(px, 0, mid) < alpha(px, mid, mid))
        // 上下对称
        for (y in 0 until size) {
            assertEquals(alpha(px, mid, y), alpha(px, mid, size - 1 - y), "y=$y 上下应镜像")
        }
        // 沿线方向：中线上的 alpha 从端部向中心不降
        var prev = alpha(px, 0, mid)
        for (x in 1..mid) {
            val a = alpha(px, x, mid)
            assertTrue(a >= prev, "x=$x 处 alpha 不应低于更靠端部的位置")
            prev = a
        }
    }
}
