package work.nekow.particledrawing

import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.api.Light
import work.nekow.particledrawing.api.ParticleSpawnSpec
import work.nekow.particledrawing.api.ParticleVisual
import work.nekow.particledrawing.api.particleVisual
import work.nekow.particledrawing.core.client.RenderParticle
import work.nekow.particledrawing.core.easing.EasingType
import work.nekow.particledrawing.core.network.LightShapePart
import work.nekow.particledrawing.core.network.ParticleLightShapePayload
import work.nekow.particledrawing.core.network.ParticleVisualCodec
import work.nekow.particledrawing.core.server.ParticleData
import work.nekow.particledrawing.light.LightFalloff
import work.nekow.particledrawing.light.LightShape
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
    fun `内外锥相等时保留硬边`() {
        val shape = LightShape(range = 8.0, outerDeg = 30.0, innerDeg = 30.0)
        assertEquals(30.0, shape.effectiveInnerDeg)
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

    @Test
    fun `光源配置可在批量和外观 DSL 之间复用`() {
        val beam = LightShape.spot(12.0, 35.0, 20.0, 2.0)
            .withDirection(-45.0, -35.0)
            .withFalloff(LightFalloff.SMOOTH)
        val focused = beam.withFocus(18.0)
        assertEquals(beam.copy(outerDeg = 18.0, innerDeg = 0.0), focused)
        assertEquals(10.8, focused.effectiveInnerDeg, 1e-9)

        val spec = ParticleSpawnSpec().lightShape(beam).lightOnly(true).lightIntensity(0.6f)
        val dsl = particleVisual { light = Light.only(15, 0.6f).withShape(beam) }
        assertEquals(beam, spec.visual?.lightShape)
        assertEquals(beam, dsl.lightShape)
        assertTrue(dsl.lightOnly)
        assertEquals(0.6f, dsl.lightIntensity)
        assertFalse(dsl.glowing)
    }

    @Test
    fun `形状更新完整过网络且支持清除`() {
        val id = UUID.randomUUID()
        val beam = LightShape.spot(12.0, 35.0, 20.0, 2.0)
            .withDirection(17.5, -42.5)
            .withFalloff(LightFalloff.INVERSE_SQUARE)
        val updates = listOf(
            ParticleLightShapePayload(id, beam),
            ParticleLightShapePayload(id, LightShape.point(6.0)),
            ParticleLightShapePayload(id, null),
            ParticleLightShapePayload(id, beam, LightShapePart.DIRECTION, 12, EasingType.EASE_IN_OUT),
            ParticleLightShapePayload(id, beam, LightShapePart.FOCUS, 20, EasingType.custom(0.2, 0.0, 0.9, 1.0)),
        )
        for (payload in updates) {
            val out = FriendlyByteBuf(Unpooled.buffer())
            try {
                ParticleLightShapePayload.STREAM_CODEC.encode(out, payload)
                assertEquals(payload, ParticleLightShapePayload.STREAM_CODEC.decode(out))
                assertEquals(0, out.readableBytes())
            } finally {
                out.release()
            }
        }
    }

    @Test
    fun `形状热更不污染复用外观并保留已开始的光强缓动`() {
        val beam = LightShape.spot(12.0, 35.0)
        val original = ParticleVisual().lightShape(beam).lightIntensity(0.8f)
        val id = UUID.randomUUID()
        val data = ParticleData.create(id, Vec3.ZERO, Color.WHITE, 1f, -1, null, false, 15, null, original)
        val render = RenderParticle(
            id, Vec3.ZERO, Color.WHITE, 1f, false, 15, -1, null, null, null,
            lightIntensity = 0.8f, lightShape = beam,
        ).also { it.captureSpawnSize() }

        val turned = beam.withDirection(75.0, -25.0).withFocus(18.0)
        data.setLightIntensity(0.4f)
        data.setLightShape(turned)
        render.applyLightIntensity(0.4f, 1000L, EasingType.LINEAR)
        render.setLightShape(turned)
        assertEquals(turned, data.visual()?.lightShape)
        assertEquals(turned, render.shape())
        assertEquals(0.4f, data.visual()?.lightIntensity)
        assertEquals(beam, original.lightShape)
        assertEquals(12f, render.lightIntensity(), 1e-5f)
        repeat(20) { render.advanceEngine() }
        assertEquals(6f, render.lightIntensity(), 1e-5f)

        data.setLightShape(null)
        render.setLightShape(null)
        assertNull(data.visual()?.lightShape)
        assertFalse(render.emitsLight())
        assertEquals(0f, render.lightIntensity())
    }

    private fun lamp(shape: LightShape) = RenderParticle(
        UUID.randomUUID(), Vec3.ZERO, Color.WHITE, 1f, false, 15, -1, lightShape = shape,
    )

    @Test
    fun `转向走最短偏航和聚焦各自按分数刻采样`() {
        val beam = LightShape.spot(12.0, 18.0).withDirection(170.0, -35.0)
        val turned = beam.withDirection(-170.0, -55.0)
        val focused = turned.withFocus(38.0, 22.0, 2.0)
        val render = lamp(beam)
        render.applyLightShape(turned, LightShapePart.DIRECTION, 100L, EasingType.LINEAR)
        render.applyLightShape(focused, LightShapePart.FOCUS, 200L, EasingType.EASE_IN)
        val frame = assertNotNull(render.shape(0.5f))
        assertEquals(175.0, frame.yawDeg, 1e-6)
        assertEquals(-40.0, frame.pitchDeg, 1e-6)
        val zoom = EasingType.EASE_IN.evaluate(0.125f)
        assertEquals(18.0 + 20.0 * zoom, frame.outerDeg, 1e-6)
        assertEquals(10.8 + 11.2 * zoom, frame.effectiveInnerDeg, 1e-6)
        assertEquals(1.0 + zoom, frame.edge, 1e-6)
        repeat(2) { render.advanceEngine() }
        val half = assertNotNull(render.shape())
        assertEquals(-170.0, half.yawDeg)
        assertTrue(half.outerDeg < 38.0)
        repeat(2) { render.advanceEngine() }
        assertEquals(focused, render.shape())
    }

    @Test
    fun `分数刻重定向连续且不影响另一项缓动`() {
        val beam = LightShape.spot(12.0, 20.0)
        val turned = beam.withDirection(80.0, -50.0)
        val focused = turned.withFocus(40.0, 30.0)
        val render = lamp(beam)
        render.applyLightShape(turned, LightShapePart.DIRECTION, 200L, EasingType.LINEAR)
        render.applyLightShape(focused, LightShapePart.FOCUS, 200L, EasingType.LINEAR)
        render.advanceEngine()
        val before = assertNotNull(render.shape(0.6f))
        val retargeted = focused.withDirection(12.0, -70.0)
        render.applyLightShape(retargeted, LightShapePart.DIRECTION, 100L, EasingType.LINEAR)
        assertEquals(before, render.shape(0.6f))
        val after = assertNotNull(render.shape(0.8f))
        assertTrue(after.yawDeg < before.yawDeg)
        assertTrue(after.outerDeg > before.outerDeg)

        val refocused = retargeted.withFocus(18.0)
        render.applyLightShape(refocused, LightShapePart.FOCUS, 100L, EasingType.LINEAR)
        val resumed = assertNotNull(render.shape(0.8f))
        assertEquals(after.yawDeg, resumed.yawDeg, 1e-9)
        assertEquals(after.outerDeg, resumed.outerDeg, 1e-9)
        assertEquals(after.effectiveInnerDeg, resumed.effectiveInnerDeg, 1e-9)
        val ranged = refocused.withFalloff(LightFalloff.SMOOTH, 16.0)
        render.applyLightShape(ranged, LightShapePart.FALLOFF, 0L, EasingType.LINEAR)
        val next = assertNotNull(render.shape(0.9f))
        assertTrue(next.yawDeg < resumed.yawDeg)
        assertTrue(next.outerDeg < resumed.outerDeg)
        assertEquals(16.0, next.range)

        repeat(2) { render.advanceEngine() }
        assertEquals(ranged, render.shape(0.8f))
    }
}
