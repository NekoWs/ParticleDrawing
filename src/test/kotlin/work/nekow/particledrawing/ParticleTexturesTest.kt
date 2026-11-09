package work.nekow.particledrawing

import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import work.nekow.particledrawing.api.ParticleTextures
import work.nekow.particledrawing.api.ParticleVisual
import work.nekow.particledrawing.api.particleVisual
import work.nekow.particledrawing.command.ParticleCommandOptionParser
import work.nekow.particledrawing.core.network.ParticleVisualCodec
import work.nekow.particledrawing.animation.script.Reg
import work.nekow.particledrawing.animation.script.compileFunctionObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ParticleTexturesTest {
    @Test
    fun `整图资源引用跨资源包分辨率保持世界尺寸而显式UV仍按像素取景`() {
        val visual = ParticleVisual().particleTexture("end_rod").anisoWorld(0.12f, 0.12f)
        val whole = assertNotNull(visual.toUvData(8, 8))
        assertEquals(listOf(0, 0), whole.uvSize.toList())
        for (resolution in listOf(8, 16, 32, 64)) {
            val scale = ParticleVisual.texScaleOf(whole, resolution, resolution)
            assertEquals(1f, scale)
            val axes = assertNotNull(visual.resolvedAniso(1f, scale))
            assertEquals(0.6f, axes[0], 1e-6f)
            assertEquals(0.6f, axes[1], 1e-6f)
        }
        val cropped = assertNotNull(visual.uv(0f, 0f, 8f, 8f).toUvData(32, 32))
        assertEquals(listOf(8, 8), cropped.uvSize.toList())
        assertEquals(0.5f, ParticleVisual.texScaleOf(cropped, 32, 32))
    }

    @Test
    fun `命令方块示例的81颗粒子落在完整一周期的sin曲线上`() {
        val code = "u=i/(n-1);x=100+8*u;y=65+sin(2*PI*u);z=100;"
        val compiled = assertNotNull(compileFunctionObject(code, emptyList()))
        val regs = compiled.allocRegs()
        val stack = compiled.allocStack()
        val expected = listOf(0 to (100.0 to 65.0), 20 to (102.0 to 66.0), 40 to (104.0 to 65.0),
            60 to (106.0 to 64.0), 80 to (108.0 to 65.0))
        for ((index, point) in expected) {
            compiled.eval(index.toDouble(), 81.0, 0.0, regs, stack)
            assertEquals(point.first, regs[Reg.X], 1e-9)
            assertEquals(point.second, regs[Reg.Y], 1e-9)
            assertEquals(100.0, regs[Reg.Z], 1e-9)
        }
    }

    @Test
    fun `粒子资源引用在命令和两种外观接口间保持一致`() {
        val expected = "particle:minecraft:end_rod"
        assertEquals(expected, ParticleTextures.of("end_rod"))
        assertEquals(expected, ParticleVisual().particleTexture("minecraft:end_rod").texture)
        assertEquals(expected, particleVisual { particleTexture("end_rod") }.texture)
        assertEquals(expected, ParticleCommandOptionParser.parse("texture=$expected").visual.texture)
        assertEquals("particle:example:magic#2", ParticleTextures.of("example:magic", 2))
        assertNull(ParticleTextures.parse("custom:ring"))
    }

    @Test
    fun `帧号按粒子定义列表的顺序解析而不是猜贴图名称`() {
        val reference = assertNotNull(ParticleTextures.parse("particle:minecraft:end_rod#1"))
        val resolved = ParticleTextures.resolve(reference) { definition ->
            assertEquals("minecraft:particles/end_rod.json", definition.toString())
            """{"textures":["minecraft:glitter_7","other:effects/spark"]}"""
        }
        assertEquals("other:textures/particle/effects/spark.png", resolved.toString())
    }

    @Test
    fun `非法引用缺帧和无贴图粒子均拒绝`() {
        for (name in listOf("particle:", "particle:Minecraft:end_rod", "particle:end_rod#-1", "particle:end_rod#x", "particle:end_rod#1#2")) {
            assertFailsWith<IllegalArgumentException>(name) { ParticleVisual().texture(name) }
        }
        assertFailsWith<IllegalArgumentException> { ParticleTextures.of("end_rod", -1) }
        val frame = assertNotNull(ParticleTextures.parse("particle:end_rod#3"))
        assertFailsWith<IllegalArgumentException> {
            ParticleTextures.resolve(frame) { """{"textures":["minecraft:glitter_7"]}""" }
        }
        val first = assertNotNull(ParticleTextures.parse("particle:end_rod"))
        assertFailsWith<IllegalArgumentException> { ParticleTextures.resolve(first) { "{}" } }
        assertFailsWith<IllegalArgumentException> { ParticleTextures.resolve(first) { """{"textures":[]}""" } }
    }

    @Test
    fun `资源引用使用既有外观协议并保留世界尺寸与UV`() {
        val original = ParticleVisual().particleTexture("end_rod", 3).uv(0f, 0f, 8f, 8f).anisoWorld(0.1f, 0.1f)
        val buf = FriendlyByteBuf(Unpooled.buffer())
        try {
            ParticleVisualCodec.write(buf, original)
            val decoded = assertNotNull(ParticleVisualCodec.read(buf))
            assertEquals(original.texture, decoded.texture)
            assertEquals(original.scaleW, decoded.scaleW)
            assertEquals(original.worldUnits, decoded.worldUnits)
            assertEquals(original.uvRect?.toList(), decoded.uvRect?.toList())
            assertEquals(0, buf.readableBytes())
        } finally {
            buf.release()
        }
    }
}
