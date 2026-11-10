package work.nekow.particledrawing.api

import net.minecraft.world.phys.Vec3
import java.io.ByteArrayInputStream
import java.io.InputStream
import javax.imageio.ImageIO

/** CPU 图片采样；先检查尺寸再解码，透明像素不生成粒子，图片中心就是锚点原点。 */
object ParticleImageSampler {
    enum class Plane { XY, XZ, YZ }
    data class Options(val stride: Int = 1, val spacing: Double = 0.05, val alpha: Double = 0.0, val plane: Plane = Plane.XY)

    @JvmStatic
    @JvmOverloads
    fun sample(input: InputStream, options: Options = Options()): List<ParticleSeed> {
        require(options.stride in 1..256 && options.spacing.isFinite() && options.spacing > 0 && options.alpha in 0.0..1.0) { "图片 stride 需要 1..256，spacing > 0，alpha 需要 0..1" }
        val bytes = input.readNBytes(MAX_BYTES + 1)
        require(bytes.size <= MAX_BYTES) { "图片不能超过 8 MiB" }
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { stream ->
            requireNotNull(stream) { "无法读取图片" }
            val readers = ImageIO.getImageReaders(stream)
            require(readers.hasNext()) { "无法识别图片格式" }
            val reader = readers.next()
            try {
                reader.input = stream
                require(reader.getNumImages(true) == 1) { "图片采样需要单帧图片；请用 motion 让点阵运动，或用粒子贴图播放帧动画" }
                val width = reader.getWidth(0); val height = reader.getHeight(0)
                require(width in 1..4096 && height in 1..4096 && width.toLong() * height <= 1_048_576L) { "图片最多 4096×4096 且总像素不超过 1048576" }
                val image = reader.read(0)
                val seeds = arrayListOf<ParticleSeed>()
                for (row in 0 until height step options.stride) for (col in 0 until width step options.stride) {
                    val pixel = image.getRGB(col, row)
                    val alpha = ((pixel ushr 24) and 255) / 255.0
                    if (alpha <= options.alpha) continue
                    require(seeds.size < ParticleRecipe.MAX_SEEDS) { "图片有效样本超过 ${ParticleRecipe.MAX_SEEDS}，请增大 image stride" }
                    val x = (col - (width - 1) * 0.5) * options.spacing
                    val y = ((height - 1) * 0.5 - row) * options.spacing
                    val pos = when (options.plane) { Plane.XY -> Vec3(x, y, 0.0); Plane.XZ -> Vec3(x, 0.0, y); Plane.YZ -> Vec3(0.0, x, y) }
                    seeds += ParticleSeed(pos, Color.ofInt((pixel ushr 16) and 255, (pixel ushr 8) and 255, pixel and 255, (pixel ushr 24) and 255),
                        if (width == 1) 0.0 else col.toDouble() / (width - 1))
                }
                require(seeds.isNotEmpty()) { "图片没有达到 alpha 阈值的采样点" }
                return seeds
            } finally { reader.dispose() }
        }
    }

    private const val MAX_BYTES = 8 * 1024 * 1024
}
