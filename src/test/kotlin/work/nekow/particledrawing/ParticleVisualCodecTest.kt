package work.nekow.particledrawing

import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.ParticleStyle
import work.nekow.particledrawing.api.ParticleVisual
import work.nekow.particledrawing.core.TextureRegistry
import work.nekow.particledrawing.core.network.ParticleSpawnPayload
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 校验生成载荷里外观字段的协议契约：默认外观不额外占字节，贴图按 id 引用，
 * 未登记的名字内联下发，未知 id 回落成无贴图。
 */
class ParticleVisualCodecTest {

    private val seq = AtomicInteger()

    private fun buf() = FriendlyByteBuf(Unpooled.buffer())

    private fun payload(visual: ParticleVisual?) = ParticleSpawnPayload(
        UUID.randomUUID(), 1.0, 2.0, 3.0, 1f, 1f, 1f, 1f, 1f, -1, null, false, 15, visual,
    )

    private fun roundTrip(visual: ParticleVisual?): ParticleSpawnPayload {
        val out = buf()
        ParticleSpawnPayload.STREAM_CODEC.encode(out, payload(visual))
        return ParticleSpawnPayload.STREAM_CODEC.decode(out)
    }

    private fun registerTexture(): Pair<String, Int> {
        val name = "test:codec_${seq.incrementAndGet()}"
        val entry = TextureRegistry.register(name, byteArrayOf(1, 2, 3, 4)) ?: error("登记失败：$name")
        return name to entry.id
    }

    @Test
    fun `默认外观与无外观的载荷一样大`() {
        val none = buf()
        ParticleSpawnPayload.STREAM_CODEC.encode(none, payload(null))
        val pristine = buf()
        ParticleSpawnPayload.STREAM_CODEC.encode(pristine, payload(ParticleVisual()))
        assertEquals(none.readableBytes(), pristine.readableBytes(), "全默认外观不该多占字节")
        assertNull(roundTrip(null).visual)
    }

    @Test
    fun `整张内置贴图：按 id 引用，1 字节字段`() {
        val out = buf()
        ParticleSpawnPayload.STREAM_CODEC.encode(out, payload(ParticleVisual().style(ParticleStyle.LINE)))
        val withoutTexture = buf()
        ParticleSpawnPayload.STREAM_CODEC.encode(withoutTexture, payload(null))
        assertTrue(out.readableBytes() - withoutTexture.readableBytes() <= 4, "内置贴图应只多一个 flags + 一个 varInt id")
        assertEquals("particledrawing:builtin/line", roundTrip(ParticleVisual().style(ParticleStyle.LINE)).visual?.texture)
    }

    @Test
    fun `已登记贴图：往返后名字一致（不写名字，只写 id）`() {
        val (name, id) = registerTexture()
        assertTrue(id >= 100, "用户贴图 id 应从 100 起（1..99 留给内置形状）")
        val decoded = roundTrip(ParticleVisual().texture(name)).visual
        assertEquals(name, decoded?.texture)
    }

    @Test
    fun `服务端没登记过的名字：内联下发，客户端仍按名查`() {
        val name = "test:never_registered_${seq.incrementAndGet()}"
        assertEquals(name, roundTrip(ParticleVisual().texture(name)).visual?.texture)
    }

    @Test
    fun `未知 id：回落无贴图而不是丢粒子`() {
        assertNull(TextureRegistry.nameOf(987654))
        // 直接构造「只带贴图位、id 未知」的字段块
        val b = buf()
        b.writeVarInt(1)          // ParticleVisualCodec 的 FLAG_TEXTURE
        b.writeVarInt(987654)
        val visual = work.nekow.particledrawing.core.network.ParticleVisualCodec.read(b)
        assertNull(visual!!.texture, "未知 id 应回落成无贴图（纯白方块）")
    }

    @Test
    fun `各向异性 + 世界单位 + 子矩形 UV + 朝向 + 加色 往返一致`() {
        val (name, _) = registerTexture()
        val src = ParticleVisual()
            .texture(name)
            .uv(2f, 3f, 18f, 19f)
            .anisoWorld(3.0f, 0.125f)
            .alignTo(Vec3(1.0, 2.0, 3.0), Vec3(4.0, 6.0, 3.0))
            .additive(true)

        val v = roundTrip(src).visual!!
        assertEquals(name, v.texture)
        assertEquals(2f, v.uvRect!![0], 1e-6f)
        assertEquals(3f, v.uvRect!![1], 1e-6f)
        assertEquals(18f, v.uvRect!![2], 1e-6f)
        assertEquals(19f, v.uvRect!![3], 1e-6f)
        assertTrue(v.worldUnits, "世界格口径必须原样带回")
        assertEquals(3.0f, v.scaleW, 1e-6f)
        assertEquals(0.125f, v.scaleH, 1e-6f)
        assertFalse(v.billboard, "alignTo 关掉的广告牌要带过去")
        assertTrue(v.spinLocal)
        assertEquals(src.spinXDeg, v.spinXDeg, 1e-3)
        assertEquals(src.spinYDeg, v.spinYDeg, 1e-3)
        assertEquals(src.spinZDeg, v.spinZDeg, 1e-3)
        assertTrue(v.additive)
    }

    @Test
    fun `编辑器单位各向异性与自转、广告牌开关往返一致`() {
        val src = ParticleVisual().aniso(2f, 0.5f).spin(0.0, 0.0, Math.PI / 3, false)
        val v = roundTrip(src).visual!!
        assertFalse(v.worldUnits)
        assertEquals(2f, v.scaleW, 1e-6f)
        assertEquals(0.5f, v.scaleH, 1e-6f)
        assertTrue(v.billboard, "没关广告牌就该保持广告牌")
        assertFalse(v.spinLocal, "外旋标记要带过去")
        assertEquals(60.0, v.spinZDeg, 1e-2)
    }

    @Test
    fun `广告牌下不给自转字段（省字节）`() {
        val withSpin = buf()
        ParticleSpawnPayload.STREAM_CODEC.encode(withSpin, payload(ParticleVisual().spin(1.0)))
        val withoutSpin = buf()
        ParticleSpawnPayload.STREAM_CODEC.encode(withoutSpin, payload(ParticleVisual().billboard(false)))
        // 自转多 3 个 float；广告牌位只多 1 个 flag 位
        assertTrue(withSpin.readableBytes() - withoutSpin.readableBytes() >= 12)
    }

    @Test
    fun `各字段都不写：只有 flags 那一个 varInt`() {
        val v = ParticleVisual().texture("test:inline_${seq.incrementAndGet()}")
        val b = buf()
        ParticleSpawnPayload.STREAM_CODEC.encode(b, payload(v))
        val none = buf()
        ParticleSpawnPayload.STREAM_CODEC.encode(none, payload(null))
        // flags(1) + 内联名（1 字节长度 + 字节数）
        assertEquals(2 + v.texture!!.toByteArray(Charsets.UTF_8).size, b.readableBytes() - none.readableBytes())
    }
}
