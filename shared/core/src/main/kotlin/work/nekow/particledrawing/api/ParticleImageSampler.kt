package work.nekow.particledrawing.api

import work.nekow.particledrawing.math.Vec3
import java.io.ByteArrayInputStream
import java.io.InputStream
import javax.imageio.ImageIO
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.requireNotNullLocalized
import work.nekow.particledrawing.i18n.translation

/** CPU 图片采样；先检查尺寸再解码，透明像素不生成粒子，图片中心就是锚点原点。 */
object ParticleImageSampler {
    enum class Plane { XY, XZ, YZ }
    data class Options(val stride: Int = 1, val spacing: Double = 0.05, val alpha: Double = 0.0, val plane: Plane = Plane.XY)

    @JvmStatic
    @JvmOverloads
    fun sample(input: InputStream, options: Options = Options()): List<ParticleSeed> {
        requireLocalized(options.stride in 1..256 && options.spacing.isFinite() && options.spacing > 0 && options.alpha in 0.0..1.0) { translation("error.image.options") }
        val bytes = input.readNBytes(MAX_BYTES + 1)
        requireLocalized(bytes.size <= MAX_BYTES) { translation("error.image.file_size") }
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { stream ->
            requireNotNullLocalized(stream) { translation("error.image.read") }
            val readers = ImageIO.getImageReaders(stream)
            requireLocalized(readers.hasNext()) { translation("error.image.format") }
            val reader = readers.next()
            try {
                reader.input = stream
                requireLocalized(reader.getNumImages(true) == 1) { translation("error.image.single_frame") }
                val width = reader.getWidth(0); val height = reader.getHeight(0)
                requireLocalized(width in 1..4096 && height in 1..4096 && width.toLong() * height <= 1_048_576L) { translation("error.image.dimensions") }
                val image = reader.read(0)
                val seeds = arrayListOf<ParticleSeed>()
                for (row in 0 until height step options.stride) for (col in 0 until width step options.stride) {
                    val pixel = image.getRGB(col, row)
                    val alpha = ((pixel ushr 24) and 255) / 255.0
                    if (alpha <= options.alpha) continue
                    requireLocalized(seeds.size < ParticleRecipe.MAX_SEEDS) { translation("error.image.sample_limit", ParticleRecipe.MAX_SEEDS) }
                    val x = (col - (width - 1) * 0.5) * options.spacing
                    val y = ((height - 1) * 0.5 - row) * options.spacing
                    val pos = when (options.plane) { Plane.XY -> Vec3(x, y, 0.0); Plane.XZ -> Vec3(x, 0.0, y); Plane.YZ -> Vec3(0.0, x, y) }
                    seeds += ParticleSeed(pos, Color.ofInt((pixel ushr 16) and 255, (pixel ushr 8) and 255, pixel and 255, (pixel ushr 24) and 255),
                        if (width == 1) 0.0 else col.toDouble() / (width - 1))
                }
                requireLocalized(seeds.isNotEmpty()) { translation("error.image.no_samples") }
                return seeds
            } finally { reader.dispose() }
        }
    }

    private const val MAX_BYTES = 8 * 1024 * 1024
}
