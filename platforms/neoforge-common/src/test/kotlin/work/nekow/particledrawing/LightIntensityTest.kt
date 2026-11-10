package work.nekow.particledrawing

import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.api.CurveChannel
import work.nekow.particledrawing.api.CurveKey
import work.nekow.particledrawing.api.ParticleCurve
import work.nekow.particledrawing.api.ParticleLifeCurve
import work.nekow.particledrawing.api.ParticleSpawnSpec
import work.nekow.particledrawing.api.ParticleVisual
import work.nekow.particledrawing.core.client.RenderParticle
import work.nekow.particledrawing.core.easing.EasingType
import work.nekow.particledrawing.core.network.ParticleLightIntensityPayload
import work.nekow.particledrawing.core.network.ParticleSpawnPayload
import work.nekow.particledrawing.lighting.DynamicLightManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 对外光强：曲线通道、生命周期包络、分数年龄、运行时热更与动态光源签名。
 *
 * 光强是动态光照的独立通道，不参与片元颜色/透明度/尺寸；但它会跟着可见生命周期
 * （alpha、尺寸、出生与退场）连续收束，避免光源满亮开关。
 */
class LightIntensityTest {

    private fun particle(
        scale: Float = 1f,
        alpha: Float = 1f,
        lightLevel: Int = 15,
        lifetimeTicks: Int = 20,
        curve: ParticleLifeCurve? = null,
        lightIntensity: Float = 1f,
    ): RenderParticle = RenderParticle(
        UUID.randomUUID(), Vec3.ZERO, Color.of(1f, 1f, 1f, alpha), scale,
        true, lightLevel, lifetimeTicks, null, curve, null, lightIntensity,
    ).also { it.captureSpawnSize() }

    @Test
    fun `LIGHT 曲线只出现在光强通道，不改 alpha 与尺寸`() {
        val curve = ParticleLifeCurve.of(
            ParticleCurve.light(CurveKey.at(0, 1f), CurveKey.at(10, 0.25f)),
        )
        val rp = particle(curve = curve)
        val out = FloatArray(6)
        repeat(5) { rp.advanceEngine() }

        assertTrue(rp.curveMultipliers(out))
        assertEquals(1f, out[3], 1e-6f, "alpha 通道没有被光强曲线改写")
        assertEquals(1f, out[4], 1e-6f, "尺寸通道没有被光强曲线改写")
        assertEquals(0.625f, out[5], 1e-6f, "光强按自己的关键帧插值")
        assertEquals(1f, rp.effectiveAlpha(), 1e-6f)
        assertEquals(1f, rp.scale(), 1e-6f)
    }

    @Test
    fun `寿命曲线覆盖通道：重复给整套曲线不会叠乘或丢通道`() {
        val first = ParticleLifeCurve.of(
            ParticleCurve.alpha(CurveKey.at(0, 0.5f)),
            ParticleCurve.light(CurveKey.at(0, 0.5f)),
        )
        val second = ParticleLifeCurve.of(
            ParticleCurve.scale(CurveKey.at(0, 0.25f)),
        )
        val rp = particle(curve = first)
        val out = FloatArray(6)
        rp.curveMultipliers(out)

        // 覆盖是整份替换：新曲线里没有 alpha / light，取值回到缺省 1
        val replaced = particle(curve = second)
        replaced.curveMultipliers(out)
        assertEquals(1f, out[3], 1e-6f)
        assertEquals(0.25f, out[4], 1e-6f)
        assertEquals(1f, out[5], 1e-6f)
    }

    @Test
    fun `光强跟随 alpha、尺寸与曲线尺寸连续变化`() {
        val curve = ParticleLifeCurve.of(
            ParticleCurve.alpha(CurveKey.at(0, 1f), CurveKey.at(10, 0.5f)),
            ParticleCurve.scale(CurveKey.at(0, 1f), CurveKey.at(10, 0.5f)),
        )
        val rp = particle(scale = 2f, alpha = 0.5f, lightLevel = 12, curve = curve)
        assertEquals(6f, rp.lightIntensity(0f), 1e-5f, "12 × 0.5 alpha × 1 尺寸")
        repeat(5) { rp.advanceEngine() }
        assertEquals(3.375f, rp.lightIntensity(0f), 1e-5f, "半程 alpha 与曲线尺寸都到 0.75")

        rp.setScaleDirect(1f)
        assertEquals(1.6875f, rp.lightIntensity(0f), 1e-5f, "运行时缩到出生尺寸的一半")
    }

    @Test
    fun `出生尺寸为基准：组缩到零时亮度归零而不是残留满光`() {
        val rp = particle(scale = 3f, lightLevel = 15)
        assertEquals(15f, rp.lightIntensity(0f), 1e-5f)
        rp.setScaleDirect(1.5f)
        assertEquals(7.5f, rp.lightIntensity(0f), 1e-5f)
        rp.setScaleDirect(0f)
        assertEquals(0f, rp.lightIntensity(0f), 1e-5f)
    }

    @Test
    fun `分数年龄采样：短寿命粒子能走到中间值与零末端`() {
        // 1t 寿命：0t 时 alpha 1，1t 时 0
        val curve = ParticleLifeCurve.of(
            ParticleCurve.alpha(CurveKey.at(0, 1f), CurveKey.at(1, 0f)),
            ParticleCurve.scale(CurveKey.at(0, 1f), CurveKey.at(1, 0f)),
        )
        val rp = particle(lightLevel = 10, lifetimeTicks = 1, curve = curve)
        val out = FloatArray(6)

        rp.curveMultipliers(out, 0.5f)
        assertEquals(0.5f, out[3], 1e-5f, "半 tick 处 alpha 到中段")
        assertEquals(0.5f, out[4], 1e-5f, "半 tick 处尺寸到中段")
        assertEquals(2.5f, rp.lightIntensity(0.5f), 1e-5f, "alpha 与尺寸同时到中段，亮度是两轴相乘")
        assertTrue(rp.needsFinalFrame(), "末端为零的曲线值得多留一帧")

        rp.advanceEngine()
        assertEquals(0f, rp.effectiveAlpha(0f), 1e-5f, "1t 时 alpha 到零")
        assertEquals(0f, rp.lightIntensity(0f), 1e-5f)
    }

    @Test
    fun `末端不为零的曲线不再多留一帧`() {
        val curve = ParticleLifeCurve.of(
            ParticleCurve.alpha(CurveKey.at(0, 1f), CurveKey.at(1, 0.4f)),
        )
        val rp = particle(lifetimeTicks = 1, curve = curve)
        assertFalse(rp.needsFinalFrame(), "末端还有可见 alpha，按原寿命移除")

        val lightOnly = particle(
            lifetimeTicks = 1,
            curve = ParticleLifeCurve.of(ParticleCurve.light(CurveKey.at(0, 1f), CurveKey.at(1, 0f))),
        )
        assertFalse(lightOnly.needsFinalFrame(), "只有光强曲线不延长可见寿命")
    }

    @Test
    fun `出生那一 tick 不推进年龄，收尾帧状态可查询`() {
        val rp = particle()
        rp.markBorn(7)
        assertFalse(rp.shouldAdvance(7), "出生在同一 tick 序号：先让渲染看到 age 0")
        assertTrue(rp.shouldAdvance(8), "下一 tick 才开始计年龄")
        assertFalse(rp.isExpiring())
        rp.markExpiring()
        assertTrue(rp.isExpiring())
    }

    @Test
    fun `只读观察的尺寸按分数年龄求值，接管外观时不叠乘曲线`() {
        val curve = ParticleLifeCurve.of(
            ParticleCurve.scale(CurveKey.at(0, 1f), CurveKey.at(10, 0.5f)),
        )
        val rp = particle(scale = 2f, curve = curve)
        val out = FloatArray(3)
        repeat(5) { rp.advanceEngine() }
        rp.visualScaleAt(0f, out)
        assertEquals(1.5f, out[0], 1e-5f)
        assertEquals(1.5f, out[1], 1e-5f)
        assertEquals(1f, out[2], 1e-5f, "Z 只存储，不参与广告牌渲染")

        rp.visualScaleAt(0f, out, applyCurve = false)
        assertEquals(2f, out[0], 1e-5f, "程序直写接管外观时曲线不再叠乘")
    }

    @Test
    fun `渲染帧内出生的粒子从出生 partialTick 起算 age`() {
        val curve = ParticleLifeCurve.of(
            ParticleCurve.alpha(CurveKey.at(0, 0f), CurveKey.at(1, 1f)),
        )
        val rp = particle(lifetimeTicks = 1, curve = curve)
        rp.markSpawnedDuringRender(0.6f)
        val out = FloatArray(6)
        rp.curveMultipliers(out, 0.6f)
        assertEquals(0f, out[3], 1e-5f, "出生那一帧就是 age 0")
        rp.curveMultipliers(out, 1f)
        assertEquals(0.4f, out[3], 1e-5f, "本 tick 末尾走到 0.4t")
    }

    @Test
    fun `只有光强曲线时不重写桥接外观`() {
        val lightOnly = particle(
            curve = ParticleLifeCurve.of(ParticleCurve.light(CurveKey.at(0, 1f))),
        )
        assertFalse(lightOnly.hasAppearanceCurve())
        val withAlpha = particle(
            curve = ParticleLifeCurve.of(ParticleCurve.alpha(CurveKey.at(0, 1f))),
        )
        assertTrue(withAlpha.hasAppearanceCurve())
    }

    @Test
    fun `运行时热更从当前实际亮度接续，不先跳回满光`() {
        val rp = particle(lightLevel = 10)
        rp.applyLightIntensity(0.5f, 1000L, EasingType.LINEAR)
        assertEquals(10f, rp.lightIntensity(0f), 1e-5f, "改目标那一刻亮度不变")

        rp.advanceEngine()
        assertEquals(9.75f, rp.lightIntensity(0f), 1e-5f, "1 tick 后走过 5% 的过渡")
        repeat(19) { rp.advanceEngine() }
        assertEquals(5f, rp.lightIntensity(0f), 1e-5f, "过渡结束落到目标乘数")

        rp.applyLightIntensity(2f, 0L, EasingType.LINEAR)
        assertEquals(20f, rp.lightIntensity(0f), 1e-5f, "零时长立即生效")

        // 目标为 0 时也要从当前亮度接续，不能直接掉到黑
        rp.applyLightIntensity(0f, 1000L, EasingType.LINEAR)
        assertEquals(20f, rp.lightIntensity(0f), 1e-5f)
        repeat(20) { rp.advanceEngine() }
        assertEquals(0f, rp.lightIntensity(0f), 1e-5f)
    }

    @Test
    fun `ParticleSpawnSpec 与外观规格往返保留光强乘数`() {
        val spec = ParticleSpawnSpec().lightIntensity(0.25f)
        assertEquals(0.25f, spec.visual!!.lightIntensity, 1e-6f)
        assertFalse(spec.visual!!.isPristine(), "只设光强也算设过外观")

        val visual = ParticleVisual().lightIntensity(0.25f)
        val out = FriendlyByteBuf(Unpooled.buffer())
        ParticleSpawnPayload.STREAM_CODEC.encode(
            out,
            ParticleSpawnPayload(
                UUID.randomUUID(), 0.0, 0.0, 0.0, 1f, 1f, 1f, 1f, 1f, -1, null, true, 15, visual,
            ),
        )
        val decoded = ParticleSpawnPayload.STREAM_CODEC.decode(out).visual!!
        assertEquals(0.25f, decoded.lightIntensity, 1e-6f)
    }

    @Test
    fun `光强更新载荷往返`() {
        val out = FriendlyByteBuf(Unpooled.buffer())
        val payload = ParticleLightIntensityPayload(
            UUID.randomUUID(), 0.5f, 12, EasingType.EASE_IN_OUT,
        )
        ParticleLightIntensityPayload.STREAM_CODEC.encode(out, payload)
        val decoded = ParticleLightIntensityPayload.STREAM_CODEC.decode(out)
        assertEquals(payload.particleId, decoded.particleId)
        assertEquals(payload.multiplier, decoded.multiplier)
        assertEquals(payload.durationTicks, decoded.durationTicks)
        assertEquals(payload.easing, decoded.easing)
    }

    @Test
    fun `动态光源签名保留小数亮度且与列表顺序无关`() {
        val id1 = UUID.randomUUID()
        val id2 = UUID.randomUUID()
        val dim = DynamicLightManager.LightSource(id1, 0.0, 0.0, 0.0, 15.2)
        val brighter = DynamicLightManager.LightSource(id1, 0.0, 0.0, 0.0, 15.8)
        val same = DynamicLightManager.LightSource(id1, 0.0, 0.0, 0.0, 15.2)
        val other = DynamicLightManager.LightSource(id2, 0.0, 0.0, 0.0, 8.0)

        assertNotEquals(DynamicLightManager.signatureOf(listOf(dim)), DynamicLightManager.signatureOf(listOf(brighter)),
            "同整数等级内的小数亮度变化也要改变签名")
        assertEquals(DynamicLightManager.signatureOf(listOf(dim)), DynamicLightManager.signatureOf(listOf(same)))
        // 低于光图 1/16 精度的抖动不抬版本号，避免静止区块每帧重建
        val near = DynamicLightManager.LightSource(id1, 0.0, 0.0, 0.0, 15.2001)
        assertEquals(dim.lightQ, near.lightQ)
        assertEquals(
            DynamicLightManager.signatureOf(listOf(dim, other)),
            DynamicLightManager.signatureOf(listOf(other, dim)),
            "签名不能因遍历顺序抖动而每帧失效",
        )
    }

    @Test
    fun `光强曲线随 spawn 包往返，通道与关键帧一致`() {
        // LIGHT 枚举序号是协议的一部分，这里守住通道往返
        val curve = ParticleLifeCurve.of(ParticleCurve.light(CurveKey.at(0, 0.2f), CurveKey.at(5, 1f)))
        assertEquals(CurveChannel.LIGHT, curve.curves.single().channel)
        assertEquals(0.6f, curve.valueAt(CurveChannel.LIGHT, 2.5f), 1e-6f)

        val out = FriendlyByteBuf(Unpooled.buffer())
        ParticleSpawnPayload.STREAM_CODEC.encode(
            out,
            ParticleSpawnPayload(
                UUID.randomUUID(), 0.0, 0.0, 0.0, 1f, 1f, 1f, 1f, 1f, 10, null, true, 15,
                null, curve,
            ),
        )
        val decoded = ParticleSpawnPayload.STREAM_CODEC.decode(out).lifeCurve!!
        assertEquals(CurveChannel.LIGHT, decoded.curves.single().channel)
        assertEquals(0.6f, decoded.valueAt(CurveChannel.LIGHT, 2.5f), 1e-6f)
    }
}
