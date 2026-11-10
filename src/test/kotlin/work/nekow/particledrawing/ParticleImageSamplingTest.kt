package work.nekow.particledrawing

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.ParticleImageSampler
import work.nekow.particledrawing.api.ParticleSampling
import work.nekow.particledrawing.command.CommandParticleRecipes
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

class ParticleImageSamplingTest {
    private fun png(image: BufferedImage) = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()

    @Test fun `像素颜色透明度中心和上下方向正确`() {
        val img = BufferedImage(3, 3, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, 0xffff0000.toInt()); img.setRGB(2, 2, 0x800000ff.toInt())
        val seeds = ParticleImageSampler.sample(ByteArrayInputStream(png(img)), ParticleImageSampler.Options(spacing = 0.5))
        assertEquals(2, seeds.size)
        assertEquals(Vec3(-0.5, 0.5, 0.0), seeds[0].position)
        assertEquals(Vec3(0.5, -0.5, 0.0), seeds[1].position)
        assertEquals(1f, seeds[0].color.r); assertEquals(1f, seeds[1].color.b)
        assertEquals(128f / 255f, seeds[1].color.a)
    }

    @Test fun `步幅改变密度而不改变坐标尺度`() {
        val img = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)
        for (y in 0..3) for (x in 0..3) img.setRGB(x, y, 0xffffffff.toInt())
        val seeds = ParticleImageSampler.sample(ByteArrayInputStream(png(img)), ParticleImageSampler.Options(2, 1.0, plane = ParticleImageSampler.Plane.XZ))
        assertEquals(4, seeds.size); assertEquals(Vec3(-1.5, 0.0, 1.5), seeds.first().position)
        assertEquals(Vec3(0.5, 0.0, -0.5), seeds.last().position)
    }

    @Test fun `alpha 筛选和 YZ 平面可配置`() {
        val img = BufferedImage(1, 2, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, 0xff00ff00.toInt()); img.setRGB(0, 1, 0x400000ff)
        val seeds = ParticleImageSampler.sample(ByteArrayInputStream(png(img)), ParticleImageSampler.Options(1, 1.0, 0.5, ParticleImageSampler.Plane.YZ))
        assertEquals(1, seeds.size); assertEquals(Vec3(0.0, 0.0, 0.5), seeds.single().position)
    }

    @Test fun `坏图片全透明图与过密采样明确报错`() {
        assertFailsWith<IllegalArgumentException> { ParticleImageSampler.sample(ByteArrayInputStream(byteArrayOf(0, 1, 2))) }
        assertFailsWith<IllegalArgumentException> { ParticleImageSampler.sample(ByteArrayInputStream(png(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)))) }
        val image = BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB)
        assertFailsWith<IllegalArgumentException> { ParticleImageSampler.sample(ByteArrayInputStream(png(image))) }
        assertEquals(2500, ParticleImageSampler.sample(ByteArrayInputStream(png(image)), ParticleImageSampler.Options(stride = 2)).size)
    }

    @Test fun `路径不会越过图片根目录`() {
        val root = Files.createTempDirectory("pd-image-path")
        try {
            assertEquals(root.resolve("a.png"), CommandParticleRecipes.safeImagePath(root, "a.png"))
            assertFailsWith<IllegalArgumentException> { CommandParticleRecipes.safeImagePath(root, "../outside.png") }
            assertFailsWith<IllegalArgumentException> { CommandParticleRecipes.safeImagePath(root, root.resolve("a.png").toString()) }
        } finally { Files.delete(root) }
    }

    @Test fun `参数范围端点步长和网格上限`() {
        assertEquals(listOf(-1.0, -0.5, 0.0, 0.5, 1.0), ParticleSampling.range(-1.0, 1.0, 0.5).map { it.u })
        assertEquals(11, ParticleSampling.range(0.0, 1.0, 0.1).size)
        assertEquals(9, ParticleSampling.grid(Vec3(-1.0, 0.0, -1.0), Vec3(1.0, 0.0, 1.0), 1.0).size)
        assertFailsWith<IllegalArgumentException> { ParticleSampling.grid(Vec3(-100.0, -100.0, -100.0), Vec3(100.0, 100.0, 100.0), 0.1) }
        assertFailsWith<IllegalArgumentException> { ParticleSampling.range(0.0, 1.0, 0.0) }
    }

    @Test fun `多帧图不会静默丢掉后续帧`() {
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        val bytes = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("gif").next()
        try {
            ImageIO.createImageOutputStream(bytes).use { stream ->
                writer.output = stream
                writer.prepareWriteSequence(null)
                repeat(2) { writer.writeToSequence(javax.imageio.IIOImage(image, null, null), null) }
                writer.endWriteSequence()
            }
        } finally { writer.dispose() }
        assertFailsWith<IllegalArgumentException> { ParticleImageSampler.sample(ByteArrayInputStream(bytes.toByteArray())) }
    }
}
