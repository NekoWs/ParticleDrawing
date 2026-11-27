package work.nekow.particledrawing

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.PointFrame
import work.nekow.particledrawing.api.PointSets
import work.nekow.particledrawing.api.PointSource
import work.nekow.particledrawing.api.PointSourceContext
import work.nekow.particledrawing.api.PreparedPointSource
import work.nekow.particledrawing.platform.toMinecraft
import work.nekow.particledrawing.platform.toPointSet
import work.nekow.particledrawing.pointset.PointSet
import work.nekow.particledrawing.math.Vec3 as CoreVec3
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** API 层的点源准备、坐标系换算与命名点集注册表。 */
class ApiPointSourceTest {
    private val dimension = UUID.fromString("00000000-0000-0000-0000-000000000001")

    private fun source(points: List<CoreVec3>, frame: PointFrame = PointFrame.RELATIVE): PointSource =
        object : PointSource {
            override val count: Int? get() = points.size
            override val frame: PointFrame get() = frame
            override fun sample(context: PointSourceContext, fallbackCount: Int): PointSet = PointSet.of(points)
            override fun label(): String = "test"
        }

    @Test
    fun `相对源在世界模式加锚点、局域模式保持偏移`() {
        val prepared = PreparedPointSource(source(listOf(CoreVec3(1.0, 0.0, 0.0))), PointSet.of(listOf(CoreVec3(1.0, 0.0, 0.0))), 1)
        assertEquals(listOf(Vec3(11.0, 0.0, 0.0)), prepared.morphPoints(null, Vec3(10.0, 0.0, 0.0), local = false))
        assertEquals(listOf(Vec3(1.0, 0.0, 0.0)), prepared.morphPoints(null, Vec3(10.0, 0.0, 0.0), local = true))
    }

    @Test
    fun `世界源局域模式换算成相对锚点偏移`() {
        val prepared = PreparedPointSource(
            source(listOf(CoreVec3(12.0, 3.0, 0.0)), PointFrame.WORLD),
            PointSet.of(listOf(CoreVec3(12.0, 3.0, 0.0))), 1,
        )
        assertEquals(listOf(Vec3(12.0, 3.0, 0.0)), prepared.morphPoints(null, Vec3(10.0, 0.0, 0.0), local = false))
        assertEquals(listOf(Vec3(2.0, 3.0, 0.0)), prepared.morphPoints(null, Vec3(10.0, 0.0, 0.0), local = true))
    }

    @Test
    fun `引用源没有缓存点时必须给上下文`() {
        val prepared = PreparedPointSource(PointSource.Points(PointSet.EMPTY), null, 0)
        assertFailsWith<IllegalArgumentException> { prepared.morphPoints(null, Vec3.ZERO, local = false) }
    }

    @Test
    fun `命名点集按维度隔离并可覆盖与删除`() {
        PointSets.clear()
        val first = PointSets.define(dimension, "word", PointSet.of(listOf(CoreVec3(1.0, 2.0, 3.0))), "text")
        assertEquals(listOf("word"), PointSets.names(dimension))
        assertEquals(1, PointSets.find(dimension, "word")?.points?.size)
        val second = PointSets.define(dimension, "word", PointSet.of(listOf(CoreVec3(4.0, 5.0, 6.0), CoreVec3(7.0, 8.0, 9.0))), "formula")
        assertEquals(2, second.points.size)
        assertTrue(second.revision > first.revision)
        val other = UUID.fromString("00000000-0000-0000-0000-000000000002")
        assertNull(PointSets.find(other, "word"))
        assertFailsWith<IllegalArgumentException> { PointSets.get(dimension, "missing") }
        assertFailsWith<IllegalArgumentException> { PointSets.define(dimension, "empty", PointSet.EMPTY, "text") }
        PointSets.remove(dimension, listOf("word"))
        assertNull(PointSets.find(dimension, "word"))
        PointSets.clear()
    }

    @Test
    fun `点集在宿主坐标与核心坐标之间显式转换`() {
        val core = PointSet.of(listOf(CoreVec3(1.5, -2.5, 3.5)))
        assertEquals(core, core.toMinecraft().toPointSet())
    }
}
