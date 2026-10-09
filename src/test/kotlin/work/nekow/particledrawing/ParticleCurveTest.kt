package work.nekow.particledrawing

import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.CurveChannel
import work.nekow.particledrawing.api.CurveKey
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.api.LifeCurveSugar
import work.nekow.particledrawing.api.ParticleCurve
import work.nekow.particledrawing.api.ParticleLifeCurve
import work.nekow.particledrawing.api.ParticleSpawnSpec
import work.nekow.particledrawing.api.ParticleVisual
import work.nekow.particledrawing.core.client.RenderParticle
import work.nekow.particledrawing.core.easing.EasingType
import work.nekow.particledrawing.core.network.ParticleSpawnBatchPayload
import work.nekow.particledrawing.core.network.ParticleSpawnPayload
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 逐粒子寿命曲线（`Builder.fadeOut/shrinkTo/curve`）与批量生成（`ParticleBatch.spawnAll`）的协议契约：
 * - 曲线是「乘数随寿命变化」的声明式外观，随 spawn 包一次带过去（零逐帧带宽）；
 * - fadeOut/shrinkTo 锚在寿命末尾，必须在寿命已知时才换算，无限寿命明确报错（不悄悄降级）；
 * - 批量生成与逐颗生成编码完全同构，客户端落地走同一条路径。
 */
class ParticleCurveTest {

    private fun buf() = FriendlyByteBuf(Unpooled.buffer())

    private fun spawnPayload(
        lifeCurve: ParticleLifeCurve? = null,
        prev: Vec3? = null,
        visual: ParticleVisual? = null,
    ) = ParticleSpawnPayload(
        UUID.randomUUID(), 1.0, 2.0, 3.0, 1f, 0.5f, 0.25f, 1f, 0.4f, 20,
        null, false, 15, visual, lifeCurve, prev,
    )

    private fun encode(payload: ParticleSpawnPayload): Int {
        val out = buf()
        ParticleSpawnPayload.STREAM_CODEC.encode(out, payload)
        return out.readableBytes()
    }

    private fun spawnRoundTrip(payload: ParticleSpawnPayload): ParticleSpawnPayload {
        val out = buf()
        ParticleSpawnPayload.STREAM_CODEC.encode(out, payload)
        return ParticleSpawnPayload.STREAM_CODEC.decode(out)
    }

    // —— 取值语义 ——

    @Test
    fun `关键帧排序、端点外取端点值、段内用后一帧的缓动`() {
        val curve = ParticleCurve.alpha(
            CurveKey.at(10, 0f),
            CurveKey.at(0, 1f),               // 故意乱序：构造时应排序
        )
        assertEquals(listOf(0f, 10f), curve.keys.map { it.tTicks })
        assertEquals(1f, curve.valueAt(-5f), "生成前取首帧值")
        assertEquals(0f, curve.valueAt(999f), "寿命后取末帧值")
        assertEquals(0.5f, curve.valueAt(5f), "线性段中点")

        val step = ParticleCurve.alpha(
            CurveKey.at(0, 0f),
            CurveKey.step(10, 1f),            // 阶跃：保持到下一帧才跳变
        )
        assertEquals(0f, step.valueAt(9.9f), "阶跃段内不该插值")
        assertEquals(1f, step.valueAt(10f))
    }

    @Test
    fun `同通道多条曲线相乘、不同通道互不影响`() {
        val life = ParticleLifeCurve.of(
            ParticleCurve.alpha(CurveKey.at(0, 0.5f)),
            ParticleCurve.alpha(CurveKey.at(0, 0.4f)),
            ParticleCurve.scale(CurveKey.at(0, 3f)),
        )
        assertEquals(0.2f, life.valueAt(CurveChannel.ALPHA, 0f), "同通道相乘")
        assertEquals(3f, life.valueAt(CurveChannel.SCALE, 0f))
        assertEquals(1f, life.valueAt(CurveChannel.BLUE, 0f), "没有曲线的通道是 1（不改原外观）")
        assertTrue(life.has(CurveChannel.ALPHA) && !life.has(CurveChannel.GREEN))
    }

    @Test
    fun `fadeOut 与 shrinkTo 锚在寿命末尾，无限寿命明确报错`() {
        val life = ParticleLifeCurve.EMPTY
            .plus(LifeCurveSugar.fadeOut(20, 5, EasingType.LINEAR))
            .plus(LifeCurveSugar.shrinkTo(20, 0.25f, 5, EasingType.LINEAR))

        assertEquals(1f, life.valueAt(CurveChannel.ALPHA, 14f), "淡出开始前保持满值")
        assertEquals(0.5f, life.valueAt(CurveChannel.ALPHA, 17.5f))
        assertEquals(0f, life.valueAt(CurveChannel.ALPHA, 20f), "寿命末尾淡到 0")
        assertEquals(0.25f, life.valueAt(CurveChannel.SCALE, 20f), "收缩到目标倍率")

        assertFailsWith<IllegalArgumentException> { LifeCurveSugar.fadeOut(-1, 5, EasingType.LINEAR) }
        assertFailsWith<IllegalArgumentException> { LifeCurveSugar.shrinkTo(0, 0.5f, 5, EasingType.LINEAR) }
    }

    @Test
    fun `批量规格展开出的曲线与逐颗同一份关键帧`() {
        val life = ParticleSpawnSpec().lifetime(20).fadeOut(5).shrinkTo(0.25f, 5).resolvedLifeCurve()
            ?: error("fadeOut/shrinkTo 必须解析出曲线")
        val direct = ParticleLifeCurve.EMPTY
            .plus(LifeCurveSugar.fadeOut(20, 5, EasingType.EASE_IN))
            .plus(LifeCurveSugar.shrinkTo(20, 0.25f, 5, EasingType.EASE_IN))

        assertEquals(direct.curves.size, life.curves.size)
        for (i in direct.curves.indices) {
            val a = direct.curves[i]
            val b = life.curves[i]
            assertEquals(a.channel, b.channel)
            assertEquals(a.keys.map { it.tTicks to it.value }, b.keys.map { it.tTicks to it.value })
            assertEquals(a.keys.map { it.easing }, b.keys.map { it.easing })
        }
    }

    // —— 协议 ——

    @Test
    fun `未用到的可选字段只占标记字节，用到才付代价`() {
        val plain = encode(spawnPayload())
        val withCurve = encode(spawnPayload(
            lifeCurve = ParticleLifeCurve.of(
                ParticleCurve.alpha(CurveKey.at(0, 1f), CurveKey.at(20, 0f, EasingType.EASE_IN)),
            ),
        ))
        val curveBytes = withCurve - plain
        assertTrue(curveBytes in 10..40, "曲线按「通道 + 关键帧」紧凑写，实际多 $curveBytes 字节")

        val withPrev = encode(spawnPayload(prev = Vec3(1.0, 2.0, 3.0)))
        assertEquals(24, withPrev - plain, "prev 只在给了的时候占 3 个 double")
    }

    @Test
    fun `寿命曲线随 spawn 包往返，通道与关键帧一致`() {
        val life = ParticleLifeCurve.of(
            ParticleCurve.alpha(CurveKey.at(0, 1f), CurveKey.at(20, 0f, EasingType.EASE_IN)),
            ParticleCurve.scale(CurveKey.at(10, 1f), CurveKey.at(20, 0.2f, EasingType.custom(0.1, 0.2, 0.3, 0.4))),
            ParticleCurve.blue(CurveKey.step(5, 0.5f)),
        )
        val decoded = spawnRoundTrip(spawnPayload(lifeCurve = life)).lifeCurve ?: error("曲线丢了")
        assertEquals(life.curves.map { it.channel }, decoded.curves.map { it.channel })
        for (i in life.curves.indices) {
            val expect = life.curves[i].keys
            val actual = decoded.curves[i].keys
            assertEquals(expect.size, actual.size)
            for (j in expect.indices) {
                assertEquals(expect[j].tTicks, actual[j].tTicks)
                assertEquals(expect[j].value, actual[j].value)
                assertEquals(expect[j].easing, actual[j].easing, "自定义缓动与阶跃都要逐点往返")
            }
        }
        assertNull(spawnRoundTrip(spawnPayload()).lifeCurve)
    }

    @Test
    fun `首帧插值端点随包往返；不给就是 null（跳变出生）`() {
        val withPrev = spawnRoundTrip(spawnPayload(prev = Vec3(1.5, 2.5, 3.5)))
        assertEquals(Vec3(1.5, 2.5, 3.5), withPrev.prev)
        assertNull(spawnRoundTrip(spawnPayload()).prev)
    }

    // —— 客户端渲染落地 ——

    @Test
    fun `渲染粒子按曲线给乘数；没有曲线时走原路径`() {
        val life = ParticleLifeCurve.of(
            ParticleCurve.alpha(CurveKey.at(0, 0.5f)),
            ParticleCurve.scale(CurveKey.at(0, 2f)),
            ParticleCurve.red(CurveKey.at(0, 0.25f)),
        )
        val rp = RenderParticle(
            UUID.randomUUID(), Vec3.ZERO, Color.of(0.8f, 0.6f, 0.4f, 1f), 1.5f,
            false, 15, 20, null, life,
        )
        val out = FloatArray(5)
        assertTrue(rp.curveMultipliers(out), "有曲线时给出乘数")
        assertEquals(0.25f, out[0], "红通道")
        assertEquals(1f, out[1], "没给曲线的通道是 1")
        assertEquals(1f, out[2])
        assertEquals(0.5f, out[3], "透明度")
        assertEquals(2f, out[4], "尺寸")
        assertEquals(0.5f, rp.effectiveAlpha(), 1e-6f, "发光可见性判断用的是调制后的 alpha")

        val plain = RenderParticle(UUID.randomUUID(), Vec3.ZERO, Color.WHITE, 1f, false, 15, 0)
        assertFalse(plain.curveMultipliers(out), "没有曲线时返回 false，调用方走原路径")
        assertEquals(1f, plain.effectiveAlpha())
    }

    @Test
    fun `首帧插值端点：给了 prev 就从它扫到当前位置，不给则原地出生`() {
        val moving = RenderParticle(
            UUID.randomUUID(), Vec3(2.0, 0.0, 0.0), Color.WHITE, 1f, false, 15, 0, null, null,
            Vec3(0.0, 0.0, 0.0),
        )
        assertEquals(0.0, moving.interpolatedX(0f), 1e-9)
        assertEquals(1.0, moving.interpolatedX(0.5f), 1e-9, "半个 tick 处正好在段中点")
        assertEquals(2.0, moving.interpolatedX(1f), 1e-9)

        val snapped = RenderParticle(UUID.randomUUID(), Vec3(2.0, 0.0, 0.0), Color.WHITE, 1f, false, 15, 0)
        assertEquals(2.0, snapped.interpolatedX(0.5f), 1e-9, "没有 prev 时不插值（跳变出生）")
    }

    @Test
    fun `批量生成：整批一包，逐条与单发同构，超限直接报错`() {
        val first = spawnPayload(lifeCurve = ParticleLifeCurve.of(ParticleCurve.alpha(CurveKey.at(0, 1f))))
        val second = spawnPayload(prev = Vec3(4.0, 5.0, 6.0), visual = ParticleVisual().additive(true))
        val entries = listOf(first, second)

        val out = buf()
        ParticleSpawnBatchPayload.STREAM_CODEC.encode(out, ParticleSpawnBatchPayload(entries))
        val decoded = ParticleSpawnBatchPayload.STREAM_CODEC.decode(out).entries

        assertEquals(entries.size, decoded.size)
        for (i in entries.indices) {
            val a = entries[i]
            val b = decoded[i]
            assertEquals(a.particleId, b.particleId)
            assertEquals(a.position(), b.position())
            assertEquals(a.color(), b.color())
            assertEquals(a.lifeCurve?.curves?.size, b.lifeCurve?.curves?.size)
            assertEquals(a.prev, b.prev)
            assertEquals(a.visual?.additive, b.visual?.additive)
            assertEquals(a.visual == null, b.visual == null)
        }

        assertFailsWith<IllegalArgumentException> {
            ParticleSpawnBatchPayload(List(ParticleSpawnBatchPayload.MAX_BATCH + 1) { spawnPayload() })
        }
    }
}
