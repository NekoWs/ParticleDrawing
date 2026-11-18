package work.nekow.particledrawing

import work.nekow.particledrawing.math.Vec3
import work.nekow.particledrawing.render.SurfaceDecal
import work.nekow.particledrawing.render.WorldBounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SurfaceDecalTest {
    @Test fun `印记跨两级台阶保持连续 UV 并裁掉被遮挡的面`() {
        val upper = WorldBounds(Vec3(-1.0, -1.0, -1.0), Vec3(0.0, 0.0, 1.0))
        val lower = WorldBounds(Vec3(-0.5, -1.0, -1.0), Vec3(1.0, -0.25, 1.0))
        val tooFar = WorldBounds(Vec3(-1.0, -3.0, -1.0), Vec3(1.0, -2.0, 1.0))
        val patches = SurfaceDecal.project(Vec3.ZERO, Vec3(0.0, 1.0, 0.0), 0.75, listOf(lower, upper, tooFar))
        assertEquals(2, patches.size)
        val high = patches[0].vertices
        val low = patches[1].vertices
        assertTrue(high.all { it.position.x <= 0.0 && it.position.y == 0.001 })
        assertTrue(low.all { it.position.x >= 0.0 && it.position.y == -0.249 })
        assertTrue(high.any { it.u == 0.5f } && low.any { it.u == 0.5f })
    }
}
