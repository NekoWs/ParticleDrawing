package work.nekow.particledrawing

import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import work.nekow.particledrawing.api.ParticleVisual
import work.nekow.particledrawing.core.network.ParticleVisualCodec
import work.nekow.particledrawing.light.LightFalloff
import work.nekow.particledrawing.light.LightShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 光源形状：协议往返、默认值推导与参数校验。 */
class LightShapeTest {

    private fun roundTrip(visual: ParticleVisual): ParticleVisual? {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        try {
            ParticleVisualCodec.write(buf, visual)
            return ParticleVisualCodec.read(buf)
        } finally {
            buf.release()
        }
    }

    @Test
    fun `射灯参数能完整过网络`() {
        val visual = ParticleVisual().apply {
            spotLightForTest()
        }
        val back = roundTrip(visual)
        assertNotNull(back)
        val shape = back.lightShape
        assertNotNull(shape, "形状应当随包回来")
        assertEquals(12.0, shape.range, 1e-9)
        assertEquals(35.0, shape.outerDeg, 1e-9)
        assertEquals(18.0, shape.innerDeg, 1e-9)
        assertEquals(2.5, shape.edge, 1e-9)
        assertEquals(90.0, shape.yawDeg, 1e-9)
        assertEquals(-45.0, shape.pitchDeg, 1e-9)
        assertEquals(LightFalloff.SMOOTH, shape.falloff)
    }

    private fun ParticleVisual.spotLightForTest() {
        lightShape = LightShape(12.0, 35.0, 18.0, 2.5, 90.0, -45.0, LightFalloff.SMOOTH)
    }

    @Test
    fun `没有形状时协议只写零标志`() {
        // glowing 属于生成包字段，不在外观里；用一个真会写进外观的字段来验「形状缺省」。
        val back = roundTrip(ParticleVisual().apply { additive = true })
        assertNotNull(back)
        assertNull(back.lightShape)
        assertTrue(back.additive)
    }

    @Test
    fun `内锥没给时按外锥六成推导`() {
        val shape = LightShape(range = 8.0, outerDeg = 40.0)
        assertEquals(24.0, shape.effectiveInnerDeg, 1e-9)
    }

    @Test
    fun `内锥不小于外锥时收一点避免除零`() {
        val shape = LightShape(range = 8.0, outerDeg = 30.0, innerDeg = 30.0)
        assertTrue(shape.effectiveInnerDeg < 30.0)
    }

    @Test
    fun `点光的方向是零向量`() {
        assertEquals(Triple(0.0, 0.0, 0.0), LightShape.POINT.direction())
        assertTrue(!LightShape.POINT.isSpot)
    }

    @Test
    fun `射灯方向是单位向量且朝下`() {
        val shape = LightShape(range = 10.0, outerDeg = 20.0, yawDeg = 0.0, pitchDeg = -90.0)
        val (x, y, z) = shape.direction()
        assertEquals(-1.0, y, 1e-9)
        assertEquals(0.0, x, 1e-9)
        assertEquals(0.0, z, 1e-9)
    }

    @Test
    fun `非法参数会被拒绝`() {
        assertFailsWith<IllegalArgumentException> { LightShape(range = -1.0).validated() }
        assertFailsWith<IllegalArgumentException> { LightShape(range = 8.0, outerDeg = 200.0).validated() }
        assertFailsWith<IllegalArgumentException> {
            LightShape(range = 8.0, outerDeg = 20.0, innerDeg = 40.0).validated()
        }
        assertFailsWith<IllegalArgumentException> { LightShape(range = 8.0, outerDeg = 20.0, edge = 0.5).validated() }
        assertFailsWith<IllegalArgumentException> { LightShape(range = 8.0, outerDeg = 20.0, pitchDeg = -120.0).validated() }
    }

    @Test
    fun `外观的链式设置会保留已有字段`() {
        val visual = ParticleVisual()
            .spotlight(10.0, 30.0, 12.0)
            .lightDirection(45.0, -30.0)
            .lightFalloff(LightFalloff.INVERSE_SQUARE)
        val shape = visual.lightShape
        assertNotNull(shape)
        assertEquals(30.0, shape.outerDeg, 1e-9)
        assertEquals(12.0, shape.innerDeg, 1e-9)
        assertEquals(45.0, shape.yawDeg, 1e-9)
        assertEquals(-30.0, shape.pitchDeg, 1e-9)
        assertEquals(LightFalloff.INVERSE_SQUARE, shape.falloff)
        assertEquals(10.0, shape.range, 1e-9)
    }

    @Test
    fun `设了形状的外观不再算默认外观`() {
        assertTrue(ParticleVisual().isPristine())
        assertTrue(!ParticleVisual().apply { spotlight(8.0, 25.0) }.isPristine())
    }
    @Test
    fun `命令参数能解析出射灯形状`() {
        val o = work.nekow.particledrawing.command.ParticleCommandOptionParser.parse(
            "glowing=true light=15 spot=40,22 light_range=34 light_dir=0,-90 size_world=0.3,0.3",
        )
        val shape = o.visual.lightShape
        assertNotNull(shape, "spot 参数应当解析成光源形状")
        assertEquals(40.0, shape.outerDeg, 1e-9)
        assertEquals(22.0, shape.innerDeg, 1e-9)
        assertEquals(34.0, shape.range, 1e-9)
        assertEquals(-90.0, shape.pitchDeg, 1e-9)
        assertEquals(0.0, shape.yawDeg, 1e-9)
    }

    @Test
    fun `只写外锥角也能解析`() {
        val o = work.nekow.particledrawing.command.ParticleCommandOptionParser.parse("spot=30 light_range=20")
        val shape = o.visual.lightShape
        assertNotNull(shape)
        assertEquals(30.0, shape.outerDeg, 1e-9)
        assertEquals(18.0, shape.effectiveInnerDeg, 1e-9)
    }

    @Test
    fun `未知衰减曲线会报错`() {
        assertFailsWith<IllegalArgumentException> {
            work.nekow.particledrawing.command.ParticleCommandOptionParser.parse("light_falloff=weird")
        }
    }
}
