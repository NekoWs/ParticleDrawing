package work.nekow.particledrawing

import work.nekow.particledrawing.math.Vec3
import work.nekow.particledrawing.render.SurfaceDecal
import work.nekow.particledrawing.render.SurfaceDecalWrap
import work.nekow.particledrawing.render.WorldBounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.math.abs

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

    @Test fun `印记沿外露棱边从台阶顶绕到竖面和地板`() {
        val step = WorldBounds(Vec3.ZERO, Vec3(1.0, 0.5, 1.0))
        val floor = WorldBounds(Vec3(-1.0, -1.0, -1.0), Vec3(2.0, 0.0, 2.0))
        val isolated = WorldBounds(Vec3(-0.7, 0.2, 0.35), Vec3(-0.3, 0.35, 0.65))
        val hit = Vec3(0.1, 0.5, 0.5)
        val patches = SurfaceDecalWrap.project(hit, Vec3(0.0, 1.0, 0.0), 0.85, listOf(isolated, floor, step),
            relativeTo = Vec3.ZERO)
        fun near(a: Double, b: Double) = abs(a - b) <= 1e-9
        val top = patches.first().vertices
        val side = patches.map { it.vertices }.single { vertices -> vertices.all { near(it.position.x, -0.001) } }
        val upperEdge = top.filter { near(it.position.x, 0.0) }.map { it.u to it.v }.toSet()
        val sideUpper = side.filter { near(it.position.y, 0.5) }.map { it.u to it.v }.toSet()
        assertEquals(2, upperEdge.size)
        assertEquals(upperEdge, sideUpper)
        assertTrue(side.any { near(it.position.y, 0.0) })
        assertTrue(patches.any { it.vertices.all { vertex -> near(vertex.position.y, 0.001) } &&
            it.vertices.any { vertex -> vertex.position.x < 0.0 } })
        assertTrue(patches.none { it.vertices.all { vertex -> near(vertex.position.y, 0.351) } })
    }
}
