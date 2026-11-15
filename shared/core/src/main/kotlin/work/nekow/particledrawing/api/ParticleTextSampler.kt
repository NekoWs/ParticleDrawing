package work.nekow.particledrawing.api

import work.nekow.particledrawing.math.Vec3
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.font.TextLayout
import java.awt.geom.AffineTransform
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import kotlin.math.ceil
import kotlin.math.sqrt

/** 采样文字的笔画，返回以字形包围盒为中心的 XY 平面坐标。 */
object ParticleTextSampler {
    private const val EM_PIXELS = 64f
    private const val TILE_SIZE = 4
    private const val MAX_TEXT_LENGTH = 512
    private const val MAX_RASTER_DIMENSION = 4096
    private const val MAX_RASTER_PIXELS = 1_048_576

    /**
     * 生成恰好 [count] 个采样点。[height] 是文字的目标高度，宽度按字形比例换算。
     * 换行分隔多行文字。每行居中，字体大小由 [height] 决定；[font] 决定字形与字重。
     * 默认使用系统无衬线粗体；缺少指定字符时会报错，可传入随模组提供的字体。
     */
    @JvmStatic
    @JvmOverloads
    fun sample(
        text: String,
        count: Int,
        height: Double,
        font: Font = Font(Font.SANS_SERIF, Font.BOLD, EM_PIXELS.toInt()),
    ): List<Vec3> {
        require(text.isNotBlank() && text.length <= MAX_TEXT_LENGTH) { "粒子文字需要 1 到 $MAX_TEXT_LENGTH 个 UTF-16 单元，且包含可见字符" }
        require(count in 1..ParticleRecipe.MAX_SEEDS) { "粒子文字数量必须在 1 到 ${ParticleRecipe.MAX_SEEDS} 之间" }
        require(height.isFinite() && height > 0.0) { "粒子文字高度必须是有限正数" }

        val outline = outlineOf(text, font.deriveFont(EM_PIXELS))
        val bounds = outline.bounds
        val width = bounds.width
        val rows = bounds.height
        require(width in 1..MAX_RASTER_DIMENSION && rows in 1..MAX_RASTER_DIMENSION && width.toLong() * rows <= MAX_RASTER_PIXELS) {
            "字形为空或过大，请缩短文字或拆分成多组"
        }
        require((height * (width.toDouble() / rows)).isFinite()) { "粒子文字宽度超出可表示范围" }

        val image = BufferedImage(width, rows, BufferedImage.TYPE_BYTE_GRAY)
        val graphics = image.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.translate(-bounds.x, -bounds.y)
            graphics.color = Color.WHITE
            graphics.fill(outline)
        } finally {
            graphics.dispose()
        }
        return sampleInk(image, count, height)
    }

    private fun outlineOf(text: String, font: Font): Path2D.Double {
        val context = FontRenderContext(null, true, true)
        val lineHeight = font.getLineMetrics("Hg", context).height.toDouble()
        val outline = Path2D.Double()
        for ((index, line) in text.lines().withIndex()) {
            if (line.isBlank()) continue
            val missing = font.canDisplayUpTo(line)
            require(missing == -1) {
                val code = line.codePointAt(missing).toString(16).uppercase().padStart(4, '0')
                "字体 ${font.fontName} 缺少 U+$code，请传入包含该字符的字体"
            }
            val layout = TextLayout(line, font, context)
            val offset = AffineTransform.getTranslateInstance(-layout.bounds.centerX, index * lineHeight)
            outline.append(layout.getOutline(offset), false)
        }
        return outline
    }

    private fun sampleInk(image: BufferedImage, count: Int, height: Double): List<Vec3> {
        val width = image.width
        val rows = image.height
        val raster = image.raster
        val ink = IntArray(width * rows)
        var inkCount = 0
        // 先扫完一小块再扫下一块，使相邻样本尽量来自同一片笔画。
        for (top in 0 until rows step TILE_SIZE) for (left in 0 until width step TILE_SIZE) {
            for (row in top until minOf(top + TILE_SIZE, rows)) for (col in left until minOf(left + TILE_SIZE, width)) {
                if (raster.getSample(col, row, 0) >= 128) ink[inkCount++] = row * width + col
            }
        }
        require(inkCount > 0) { "文字没有可采样的笔画" }
        // 文字很窄或请求的粒子很多时，把笔画像素细分，仍然给出不同的坐标。
        val sub = ceil(sqrt(count.toDouble() / inkCount)).toInt().coerceAtLeast(1)
        val perPixel = sub * sub
        val candidates = inkCount.toLong() * perPixel
        return List(count) { index ->
            val cell = (2L * index + 1) * candidates / (2L * count)
            val pixel = ink[(cell / perPixel).toInt()]
            val local = (cell % perPixel).toInt()
            val x = pixel % width + (local % sub + 0.5) / sub
            val y = pixel / width + (local / sub + 0.5) / sub
            Vec3(((x - width * 0.5) / rows) * height, ((rows * 0.5 - y) / rows) * height, 0.0)
        }
    }
}
