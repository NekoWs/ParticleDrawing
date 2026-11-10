package work.nekow.particledrawing

import work.nekow.particledrawing.api.ParticleStyle
import work.nekow.particledrawing.util.BuiltinTextures
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 内置形状贴图的人眼复核预览：把像素写成 PNG（`build/preview/`），跑测试时顺带产出。
 *
 * 不覆盖朝向与混合（由渲染层负责），只看贴图本身：白方块是硬边、柔边圆点有没有过渡、
 * 线段两端有没有渐隐；`thread_preview.png` 再用同一套贴图合成一条丝，复核观感方向。
 */
class BuiltinTexturePreviewTest {

    private val outDir = File("build/preview")

    @Test
    fun `写出内置贴图与丝线合成预览`() {
        outDir.mkdirs()
        writeContactSheet(File(outDir, "builtin_textures.png"))
        writeThreadPreview(File(outDir, "thread_preview.png"))
        assertTrue(File(outDir, "builtin_textures.png").length() > 0)
        assertTrue(File(outDir, "thread_preview.png").length() > 0)
    }

    /** 三联图：默认白方块 / 柔边圆点 / 线段，各放大 8 倍（最近邻，便于看像素）。 */
    private fun writeContactSheet(file: File) {
        val scale = 8
        val size = BuiltinTextures.SIZE
        val gap = 8
        val w = size * scale * 3 + gap * 4
        val h = size * scale + gap * 2
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)

        // 背景：中灰棋盘，透明处一看就分得出（直接 alpha 混合）
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = if (((x / 8) + (y / 8)) % 2 == 0) 0xFF3A3A3A.toInt() else 0xFF4A4A4A.toInt()
                img.setRGB(x, y, c)
            }
        }

        // 第一格：默认全白方块（8×8 拉成 16×16 的观感对比）
        val white = IntArray(size * size) { 0xFFFFFFFF.toInt() }
        blit(img, white, size, gap, gap, scale)
        blit(img, BuiltinTextures.pixels(ParticleStyle.SOFT_DOT), size, gap * 2 + size * scale, gap, scale)
        blit(img, BuiltinTextures.pixels(ParticleStyle.LINE), size, gap * 3 + size * scale * 2, gap, scale)

        ImageIO.write(img, "png", file)
    }

    /**
     * 合成预览：黑色柔边核心 + 三条折线丝（两端渐隐、端部收细、加法混合），
     * 用的就是内置 SOFT_DOT / LINE 的像素。
     */
    private fun writeThreadPreview(file: File) {
        val w = 560
        val h = 320
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until h) {
            for (x in 0 until w) img.setRGB(x, y, 0xFF0D0D12.toInt())
        }

        val dot = BuiltinTextures.pixels(ParticleStyle.SOFT_DOT)
        val line = BuiltinTextures.pixels(ParticleStyle.LINE)

        val cx = w / 2.0
        val cy = h / 2.0

        // 背景的暖色辉光（加法混合铺一层大软斑），黑核要有背景可压才看得出是黑核
        for (i in 0 until 40) {
            val r = 40.0 + i * 6.0
            softBlob(img, dot, cx, cy, r * 2.2, 0x14FF8A3C, additive = true)
        }

        // 黑核：柔边圆点密铺成实心球（间距远小于斑径，不该出现方块感或空隙）
        val blob = 30.0
        val step = blob * 0.28
        var ring = 0
        while (ring * step <= 92.0) {
            val rr = ring * step
            val n = if (ring == 0) 1 else maxOf(8, (2 * Math.PI * rr / step).toInt())
            for (i in 0 until n) {
                val a = 2 * Math.PI * i / n
                softBlob(img, dot, cx + Math.cos(a) * rr, cy + Math.sin(a) * rr, blob, 0xFF05060A.toInt(), additive = false)
            }
            ring++
        }

        // 三条丝：折线 + 两端收细（与 Draw.polyline 同一套宽度系数）
        val pts = listOf(
            listOf(40.0 to 250.0, 180.0 to 120.0, 330.0 to 210.0, 520.0 to 60.0),
            listOf(60.0 to 60.0, 200.0 to 200.0, 350.0 to 90.0, 500.0 to 250.0),
            listOf(30.0 to 160.0, 250.0 to 170.0, 530.0 to 150.0),
        )
        val colors = intArrayOf(0x66D8FF, 0xB98CFF, 0xFF9AD5)
        for ((pi, path) in pts.withIndex()) {
            var total = 0.0
            for (i in 0 until path.size - 1) total += hypot(path[i + 1].first - path[i].first, path[i + 1].second - path[i].second)
            var arc = 0.0
            for (i in 0 until path.size - 1) {
                val (ax, ay) = path[i]
                val (bx, by) = path[i + 1]
                val len = hypot(bx - ax, by - ay)
                val t = (arc + len / 2.0) / total
                arc += len
                // Draw.polyline 的收细系数：端部 0.25 → 中段 1
                val widthF = (0.25 + 0.75 * kotlin.math.sin(Math.PI * t)).toFloat()
                val width = 12.0 * widthF * (1.0 + pi * 0.12)
                ribbon(img, line, ax, ay, bx, by, width, colors[pi])
            }
        }

        // 塌成的亮点：黑核上再叠加法亮核
        softBlob(img, dot, cx, cy, 150.0, 0x4D6FE8FF, additive = true)
        softBlob(img, dot, cx, cy, 70.0, 0x99FFFFFF.toInt(), additive = true)

        ImageIO.write(img, "png", file)
    }

    /** 把一张内置贴图按目标直径贴成一个软边圆斑（alpha 混合或加法混合）。 */
    private fun softBlob(
        img: BufferedImage, tex: IntArray, cx: Double, cy: Double, diameter: Double,
        argb: Int, additive: Boolean
    ) {
        val size = BuiltinTextures.SIZE
        val r = diameter / 2.0
        val tr = (argb ushr 16) and 0xFF
        val tg = (argb ushr 8) and 0xFF
        val tb = argb and 0xFF
        val x0 = maxOf(0, (cx - r).toInt())
        val x1 = minOf(img.width - 1, (cx + r).toInt())
        val y0 = maxOf(0, (cy - r).toInt())
        val y1 = minOf(img.height - 1, (cy + r).toInt())
        for (y in y0..y1) {
            for (x in x0..x1) {
                val u = (x - cx + r) / diameter
                val v = (y - cy + r) / diameter
                if (u < 0 || u >= 1 || v < 0 || v >= 1) continue
                val texel = tex[(v * size).toInt().coerceIn(0, size - 1) * size + (u * size).toInt().coerceIn(0, size - 1)]
                val a = ((texel ushr 24) and 0xFF) / 255.0
                blend(img, x, y, tr, tg, tb, a, additive)
            }
        }
    }

    /** 一段丝：沿 (ax,ay)→(bx,by) 拉长 LINE 贴图，宽 [widthPx]，加法混合。 */
    private fun ribbon(
        img: BufferedImage, tex: IntArray, ax: Double, ay: Double, bx: Double, by: Double,
        widthPx: Double, rgb: Int
    ) {
        val size = BuiltinTextures.SIZE
        val dx = bx - ax
        val dy = by - ay
        val len = hypot(dx, dy)
        if (len < 1e-6) return
        val ux = dx / len
        val uy = dy / len
        val vx = -uy
        val vy = ux
        val r = (rgb ushr 16) and 0xFF
        val g = (rgb ushr 8) and 0xFF
        val b = rgb and 0xFF

        val pad = widthPx / 2 + 1
        val x0 = maxOf(0, (minOf(ax, bx) - pad).toInt())
        val x1 = minOf(img.width - 1, (maxOf(ax, bx) + pad).toInt())
        val y0 = maxOf(0, (minOf(ay, by) - pad).toInt())
        val y1 = minOf(img.height - 1, (maxOf(ay, by) + pad).toInt())
        for (y in y0..y1) {
            for (x in x0..x1) {
                val rx = x - ax
                val ry = y - ay
                val u = (rx * ux + ry * uy) / len              // 沿段 0..1
                val v = (rx * vx + ry * vy) / widthPx + 0.5    // 跨段 0..1
                if (u < 0 || u >= 1 || v < 0 || v >= 1) continue
                val texel = tex[(v * size).toInt().coerceIn(0, size - 1) * size + (u * size).toInt().coerceIn(0, size - 1)]
                val a = ((texel ushr 24) and 0xFF) / 255.0
                blend(img, x, y, r, g, b, a, additive = true)
            }
        }
    }

    private fun blend(img: BufferedImage, x: Int, y: Int, r: Int, g: Int, b: Int, a: Double, additive: Boolean) {
        if (a <= 0.0) return
        val dst = img.getRGB(x, y)
        val dr = (dst ushr 16) and 0xFF
        val dg = (dst ushr 8) and 0xFF
        val db = dst and 0xFF
        val nr: Int
        val ng: Int
        val nb: Int
        if (additive) {
            nr = minOf(255, dr + (r * a).toInt())
            ng = minOf(255, dg + (g * a).toInt())
            nb = minOf(255, db + (b * a).toInt())
        } else {
            nr = (r * a + dr * (1 - a)).toInt()
            ng = (g * a + dg * (1 - a)).toInt()
            nb = (b * a + db * (1 - a)).toInt()
        }
        img.setRGB(x, y, (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb)
    }

    /** 把 [size]×[size] 的贴图放大 [scale] 倍贴到 (ox, oy)（最近邻）。 */
    private fun blit(img: BufferedImage, tex: IntArray, size: Int, ox: Int, oy: Int, scale: Int) {
        for (y in 0 until size * scale) {
            for (x in 0 until size * scale) {
                val texel = tex[(y / scale) * size + (x / scale)]
                val a = ((texel ushr 24) and 0xFF) / 255.0
                if (a <= 0.0) continue
                blend(img, ox + x, oy + y, (texel ushr 16) and 0xFF, (texel ushr 8) and 0xFF, texel and 0xFF, a, false)
            }
        }
    }
}
