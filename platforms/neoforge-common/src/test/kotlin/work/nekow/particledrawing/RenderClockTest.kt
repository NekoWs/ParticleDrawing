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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 渲染粒子的时钟：寿命与缓动按引擎 tick 推进，不按墙钟走。
 *
 * 关卡不 tick 时（单人暂停）引擎时钟不推进。
 */
class RenderClockTest {

    private fun particle(lifetimeTicks: Int = 0, curve: ParticleLifeCurve? = null) =
        RenderParticle(UUID.randomUUID(), Vec3.ZERO, Color.WHITE, 1f, false, 15, lifetimeTicks, null, curve)

    @Test
    fun `寿命按引擎 tick 计：不推进就不流逝`() {
        val rp = particle(lifetimeTicks = 3)
        assertTrue(rp.isAlive())
        repeat(2) { rp.advanceEngine() }
        assertTrue(rp.isAlive(), "3 tick 的寿命走到 2 tick 还活着")
        rp.advanceEngine()
        assertTrue(rp.isDead(), "第 3 tick 才到点")
    }

    @Test
    fun `永久粒子（lifetime 非正）永远不到点`() {
        val rp = particle(lifetimeTicks = -1)
        repeat(1000) { rp.advanceEngine() }
        assertTrue(rp.isAlive())
    }

    @Test
    fun `缓动按引擎时钟推进：引擎不走，位置就不动`() {
        val rp = particle()
        rp.setPositionTarget(4.0, 0.0, 0.0, EasingType.LINEAR, 100)

        rp.tick()
        assertEquals(0.0, rp.x(), 1e-9, "时钟没走：仍在起点（暂停期间不该继续缓动）")

        rp.advanceEngine()
        rp.tick()
        assertEquals(2.0, rp.x(), 1e-9, "走半个时长到中点")

        rp.advanceEngine()
        rp.tick()
        assertEquals(4.0, rp.x(), 1e-9, "走满到终点")
    }

    @Test
    fun `寿命曲线按引擎 tick 的年龄取值`() {
        // 第 2 tick 时 alpha 乘数 0.5，之后不再变
        val curve = ParticleLifeCurve.of(
            ParticleCurve.alpha(CurveKey.at(0, 1f), CurveKey.at(2, 0.5f)),
        )
        val rp = particle(curve = curve)
        val out = FloatArray(5)

        rp.advanceEngine()
        assertTrue(rp.curveMultipliers(out))
        assertEquals(0.75f, out[3], 1e-4f, "1 tick = 全程一半")

        rp.advanceEngine()
        rp.curveMultipliers(out)
        assertEquals(0.5f, out[3], 1e-4f)

        repeat(10) { rp.advanceEngine() }
        rp.curveMultipliers(out)
        assertEquals(0.5f, out[3], 1e-4f, "尾端点之后取端点值")
    }

    @Test
    fun `重设寿命从当下重新计`() {
        val rp = particle(lifetimeTicks = 2)
        rp.advanceEngine()
        rp.setLifetimeTicks(5)
        repeat(4) { rp.advanceEngine() }
        assertTrue(rp.isAlive(), "重新计之后还差 1 tick")
        rp.advanceEngine()
        assertTrue(rp.isDead())
        assertFalse(rp.isAlive())
    }
}
