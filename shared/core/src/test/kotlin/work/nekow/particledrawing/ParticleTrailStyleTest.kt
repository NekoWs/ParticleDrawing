package work.nekow.particledrawing

import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.api.ParticleTrailStyle
import work.nekow.particledrawing.math.Vec3
import work.nekow.particledrawing.render.RibbonHistory
import work.nekow.particledrawing.render.TrailAfterlife
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ParticleTrailStyleTest {
    @Test fun `丝带和管状拖尾按几何长度共用两端样式`() {
        val start = Vec3.ZERO
        val head = Vec3(4.0, 0.0, 0.0)
        val path = RibbonHistory(8, start).apply {
            record(Vec3(1.0, 0.0, 0.0))
            record(head)
        }
        val style = ParticleTrailStyle(tailWidth = 0.5f, headWidth = 2f, tailColor = Color.RED, headColor = Color.BLUE)
        val edge = arrayListOf<RibbonHistory.Edge>()
        path.forEachSegment(head, Vec3(0.0, 0.0, 10.0), 1f, 1f, style) { _, b -> edge.add(b) }
        assertEquals(listOf(0.25f, 0f), edge.map { it.progress })
        assertEquals(1.75, edge[0].left.distanceTo(edge[0].right), 1e-8)
        assertEquals(Color.of(0.75f, 0f, 0.25f, 1f), style.colorAt(Color.WHITE, edge[0].progress))
        val ring = arrayListOf<RibbonHistory.Ring>()
        path.forEachTubeSegment(head, 1f, 1f, 4, style) { _, b -> ring.add(b) }
        assertEquals(edge.map { it.progress }, ring.map { it.progress })
        assertEquals(1.75, ring[0].vertices[0].distanceTo(ring[0].vertices[2]), 1e-8)
    }

    @Test fun `脱离粒子后的透明度可插值，挂起时保留年龄`() {
        val clock = TrailAfterlife(4)
        assertEquals(0.875f, clock.opacity(0.5f))
        clock.paused = true
        repeat(5) { assertTrue(clock.advance()) }
        assertEquals(0, clock.age)
        clock.paused = false
        repeat(3) { assertTrue(clock.advance()) }
        assertEquals(0.25f, clock.opacity(0f))
        assertFalse(clock.advance())
        assertEquals(0f, clock.opacity(0f))
    }
}
