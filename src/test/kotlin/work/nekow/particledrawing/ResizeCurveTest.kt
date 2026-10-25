package work.nekow.particledrawing

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.api.CurveKey
import work.nekow.particledrawing.api.ParticleCurve
import work.nekow.particledrawing.api.ParticleLifeCurve
import work.nekow.particledrawing.core.client.RenderParticle
import work.nekow.particledrawing.core.easing.EasingType
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 运行期 resize 的尺寸基准：目标只在缓动结束落地，SCALE 寿命曲线乘当前缓动尺寸，
 * 各向异性两轴按比例收缩，重定向从当前实际值接续。
 */
class ResizeCurveTest {

    private fun particle(scale: Float = 1f, curve: ParticleLifeCurve? = null): RenderParticle =
        RenderParticle(
            UUID.randomUUID(), Vec3.ZERO, Color.WHITE, scale,
            false, 15, -1, null, curve,
        ).also { it.captureSpawnSize() }

    private fun anisoParticle(x: Float, y: Float, curve: ParticleLifeCurve? = null): RenderParticle =
        particle(curve = curve).also {
            it.setScaleArrayDirect(floatArrayOf(x, y, 1f))
            it.captureSpawnSize()
        }

    private fun advance(rp: RenderParticle, ticks: Int) {
        repeat(ticks) {
            rp.advanceEngine()
            rp.tick()
        }
    }

    private fun visual(rp: RenderParticle, partialTick: Float = 0f): FloatArray {
        val out = FloatArray(3)
        rp.visualScaleAt(partialTick, out)
        return out
    }

    @Test
    fun `无曲线各向异性 resize 从当前尺寸连续缩到零`() {
        val rp = anisoParticle(0.2f, 0.1f)
        rp.setTargetColorScale(null, 0f, EasingType.LINEAR, 400L)

        var v = visual(rp)
        assertEquals(0.2f, v[0], 1e-5f, "设置目标那一刻仍是当前尺寸")
        assertEquals(0.1f, v[1], 1e-5f)

        advance(rp, 1)
        v = visual(rp)
        assertEquals(0.175f, v[0], 1e-5f, "8t 中的第 1t 只走了 1/8")
        assertEquals(0.0875f, v[1], 1e-5f)
        assertTrue(rp.isScaleEasing())

        advance(rp, 7)
        v = visual(rp)
        assertEquals(0f, v[0], 1e-5f, "8t 后落到零")
        assertEquals(0f, v[1], 1e-5f)
    }

    @Test
    fun `各向异性 resize 保持两轴比例`() {
        val rp = anisoParticle(0.2f, 0.1f)
        rp.setTargetColorScale(null, 0f, EasingType.LINEAR, 400L)
        advance(rp, 2)
        val v = visual(rp)
        assertEquals(2f, v[0] / v[1], 1e-4f, "收缩中长轴/短轴比例不变")
    }

    @Test
    fun `SCALE 曲线叠加时 resize 使用当前缓动尺寸而不是目标零`() {
        // 出生曲线在常数 1 阶段：旧实现会因为“有曲线”直接读 setTarget 写下的目标 0
        val curve = ParticleLifeCurve.of(
            ParticleCurve.scale(CurveKey.at(0f, 1f)),
        )
        val rp = particle(scale = 0.2f, curve = curve)
        rp.setTargetColorScale(null, 0f, EasingType.LINEAR, 400L)

        assertEquals(0.2f, visual(rp)[0], 1e-5f, "第一帧不能直接归零")

        advance(rp, 4)
        assertEquals(0.1f, visual(rp)[0], 1e-5f, "半程是当前缓动尺寸乘曲线 1，而不是 0")
        advance(rp, 4)
        assertEquals(0f, visual(rp)[0], 1e-5f)
    }

    @Test
    fun `缩放缓动在 tick 之外也按 partialTick 求值`() {
        val rp = anisoParticle(0.2f, 0.1f)
        rp.setTargetColorScale(null, 0f, EasingType.LINEAR, 400L)
        rp.advanceEngine() // 只推进引擎时钟，不跑 tick

        val atTick = visual(rp, 0f)[0]
        val atHalf = visual(rp, 0.5f)[0]
        assertEquals(0.175f, atTick, 1e-5f)
        assertEquals(0.1625f, atHalf, 1e-5f, "半 tick 处再走 1/16")
    }

    @Test
    fun `缩放中重定向从当前尺寸接续`() {
        val rp = anisoParticle(0.2f, 0.1f)
        rp.setTargetColorScale(null, 0f, EasingType.LINEAR, 400L)
        advance(rp, 2)
        val before = visual(rp)

        // 同目标重定向：起点取当前，不得回跳
        rp.setTargetColorScale(null, 0f, EasingType.EASE_IN_OUT, 400L)
        val after = visual(rp)
        assertEquals(before[0], after[0], 1e-5f)
        assertEquals(before[1], after[1], 1e-5f)

        advance(rp, 1)
        assertTrue(visual(rp)[0] < before[0], "重定向后继续收缩")
    }

    @Test
    fun `缩放中只改颜色不打断缩放`() {
        val rp = anisoParticle(0.2f, 0.1f)
        rp.setTargetColorScale(null, 0f, EasingType.LINEAR, 400L)
        advance(rp, 2)
        val before = visual(rp)

        rp.setTargetColorScale(Color.RED, null, EasingType.LINEAR, 200L)
        assertEquals(before[0], visual(rp)[0], 1e-5f, "只改颜色不动尺寸，也不取消缩放缓动")

        advance(rp, 1)
        assertTrue(visual(rp)[0] < before[0], "缩放缓动仍在继续")
    }

    @Test
    fun `零时长 resize 立即落地`() {
        val curve = ParticleLifeCurve.of(ParticleCurve.scale(CurveKey.at(0f, 1f)))
        val rp = particle(scale = 0.2f, curve = curve)
        rp.setTargetColorScale(null, 0f, EasingType.LINEAR, 0L)
        assertEquals(0f, visual(rp)[0], 1e-5f, "0 时长按即时目标落地")
        assertTrue(!rp.isScaleEasing())
        assertTrue(!rp.consumeSnap(), "只改尺寸不该触发位置跳变")
    }
}
