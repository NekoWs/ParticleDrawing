package work.nekow.particledrawing

import work.nekow.particledrawing.math.Vec3
import work.nekow.particledrawing.pointset.PointAxis
import work.nekow.particledrawing.pointset.PointSet
import work.nekow.particledrawing.pointset.PointTransform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/** 点集的纯数学操作：变换不修改原对象、筛选公式、排序与选点上限。 */
class PointSetTest {
    private fun points(count: Int, radius: Double = 1.0): List<Vec3> = List(count) { i ->
        val angle = 2 * Math.PI * i / count
        Vec3(Math.cos(angle) * radius, 0.0, Math.sin(angle) * radius)
    }

    @Test
    fun `变换返回新对象且原对象不变`() {
        val original = PointSet.of(points(4))
        val moved = original.translated(Vec3(1.0, 2.0, 3.0))
        assertNotSame(original, moved)
        assertEquals(PointSet.of(points(4)), original)
        assertEquals(Vec3(2.0, 2.0, 3.0), moved.points.first())
        assertEquals(original.size, moved.size)
    }

    @Test
    fun `绕 y 轴正向旋转把 x 转到负 z`() {
        val set = PointSet.of(listOf(Vec3(1.0, 0.0, 0.0)))
        val rotated = set.rotated(PointAxis.Y, 90.0)
        assertEquals(0.0, rotated.points[0].x, 1e-9)
        assertEquals(-1.0, rotated.points[0].z, 1e-9)
    }

    @Test
    fun `镜像与缩放缩放的是原点距离`() {
        val set = PointSet.of(listOf(Vec3(1.5, -2.0, 0.0)))
        assertEquals(Vec3(-1.5, -2.0, 0.0), set.mirrored(PointAxis.X).points[0])
        assertEquals(Vec3(3.0, -4.0, 0.0), set.scaled(2.0).points[0])
    }

    @Test
    fun `拼接保留顺序并被选点上限拦住`() {
        val first = PointSet.of(listOf(Vec3(0.0, 0.0, 0.0)))
        val second = PointSet.of(listOf(Vec3(1.0, 0.0, 0.0)))
        assertEquals(listOf(Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0)), first.concatenated(second).points)
        val full = PointSet.of(points(PointSet.MAX_POINTS))
        assertFailsWith<IllegalArgumentException> { full.concatenated(second) }
    }

    @Test
    fun `公式筛选按坐标与序号保留子集`() {
        val set = PointSet.of(points(8))
        val right = set.filteredByFormula("x > 0")
        assertTrue(right.points.isNotEmpty())
        assertTrue(right.points.all { it.x > 0 })
        val everyOther = set.filteredByFormula("mod(i, 2) < 0.5")
        assertEquals(4, everyOther.size)
        assertFailsWith<IllegalArgumentException> { set.filteredByFormula("x +") }
        assertFailsWith<IllegalArgumentException> { set.filteredByFormula("x * 0 + 0 / 0") }
    }

    @Test
    fun `排序与固定种子洗牌稳定可复现`() {
        val set = PointSet.of(points(6))
        val byZ = set.sortedBy { _, point -> point.z }
        assertEquals(byZ.points.sortedBy { it.z }, byZ.points)
        assertEquals(set.shuffled(42).points, set.shuffled(42).points)
    }
}
