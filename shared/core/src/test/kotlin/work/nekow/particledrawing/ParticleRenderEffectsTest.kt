package work.nekow.particledrawing

import work.nekow.particledrawing.math.Vec3
import work.nekow.particledrawing.render.ParticleDistanceLod
import work.nekow.particledrawing.render.RibbonHistory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ParticleRenderEffectsTest {
    @Test
    fun `拖尾历史固定长度且传送切断旧轨迹`() {
        val trail = RibbonHistory(2, Vec3.ZERO)
        for (x in 1..4) trail.record(Vec3(x.toDouble(), 0.0, 0.0))
        assertEquals(3, trail.size)
        assertEquals(4.0, trail.sample(0).x)
        assertEquals(2.0, trail.sample(2).x)
        trail.reset(Vec3(100.0, 0.0, 0.0))
        assertEquals(1, trail.size)
        assertEquals(100.0, trail.sample(0).x)
    }

    @Test
    fun `曲线拖尾的段共享边并向尾端收窄`() {
        val trail = RibbonHistory(2, Vec3.ZERO)
        trail.record(Vec3(1.0, 0.0, 0.0))
        trail.record(Vec3(1.0, 1.0, 0.0))
        val segments = mutableListOf<Pair<RibbonHistory.Edge, RibbonHistory.Edge>>()
        trail.forEachSegment(trail.sample(0), Vec3(0.0, 0.0, 6.0), 0.2f, 1f) { a, b -> segments += a to b }
        assertEquals(2, segments.size)
        assertEquals(segments.first().second, segments.last().first)
        assertEquals(0.4, segments.first().first.left.distanceTo(segments.first().first.right), 1e-7)
        assertEquals(segments.last().second.left, segments.last().second.right)
    }

    @Test
    fun `距离抽样随距离单调减少并可完全关闭`() {
        val rank = 0.2
        assertTrue(ParticleDistanceLod.visible(16.0, 8.0, rank))
        assertTrue(ParticleDistanceLod.visible(256.0, 8.0, rank))
        assertFalse(ParticleDistanceLod.visible(400.0, 8.0, rank))
        assertFalse(ParticleDistanceLod.visible(1024.0, 8.0, 0.0))
        assertTrue(ParticleDistanceLod.visible(1024.0, 0.0, 0.99))
    }
}
