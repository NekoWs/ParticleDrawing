package work.nekow.particledrawing

import work.nekow.particledrawing.light.LightOcclusion
import work.nekow.particledrawing.light.LightOcclusionCache
import work.nekow.particledrawing.math.Vec3
import work.nekow.particledrawing.render.WorldBounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LightOcclusionTest {
    @Test fun `逐点查找遮挡并保留朝向光源的墙面`() {
        val obstacle = WorldBounds(Vec3(1.0, -0.2, -0.2), Vec3(2.0, 0.25, 0.2))
        val distant = List(16) { i ->
            WorldBounds(Vec3(i.toDouble(), 3.0, 0.0), Vec3(i + 0.8, 4.0, 1.0))
        }
        val index = LightOcclusion.from(distant + obstacle)
        assertTrue(index.blocks(Vec3.ZERO, 3.0, 0.0, 0.0))
        assertFalse(index.blocks(Vec3.ZERO, 1.0, 0.0, 0.0))
        assertFalse(index.blocks(Vec3.ZERO, 3.0, 1.0, 0.0))
        val tangent = Vec3(0.0, 0.25, 0.0)
        assertFalse(index.blocks(tangent, 3.0, 0.25, 0.0))
        assertFalse(LightOcclusion.from(emptyList()).blocks(Vec3.ZERO, 3.0, 0.0, 0.0))
    }

    @Test fun `移动时复用覆盖区也会按当前光源位置计算遮挡`() {
        val cache = LightOcclusionCache()
        val obstacle = WorldBounds(Vec3(1.0, -0.25, -0.25), Vec3(2.0, 0.25, 0.25))
        var reads = 0
        val read: (WorldBounds) -> List<WorldBounds> = {
            reads++
            listOf(obstacle)
        }
        val before = cache.indexAt(Vec3.ZERO, 4.0, read)
        val moved = Vec3(0.0, 0.0, 2.0)
        val after = cache.indexAt(moved, 4.0, read)

        assertSame(before, after)
        assertEquals(1, reads)
        assertTrue(before.blocks(Vec3.ZERO, 3.0, 0.0, 0.0))
        assertFalse(after.blocks(moved, 3.0, 0.0, 0.0))
        cache.indexAt(Vec3(0.0, 0.0, 4.0), 4.0, read)
        assertEquals(2, reads)
    }

    @Test fun `相关的方块和区块变化在下一次查询时刷新`() {
        val cache = LightOcclusionCache()
        val obstacle = WorldBounds(Vec3(1.0, -0.25, -0.25), Vec3(2.0, 0.25, 0.25))
        var boxes = listOf(obstacle)
        var reads = 0
        val read: (WorldBounds) -> List<WorldBounds> = {
            reads++
            boxes
        }
        val before = cache.indexAt(Vec3.ZERO, 4.0, read)
        cache.blockChanged(24, 0, 0)
        cache.chunkChanged(16, 31, -16, -1)
        assertSame(before, cache.indexAt(Vec3.ZERO, 4.0, read))
        assertEquals(1, reads)

        boxes = emptyList()
        cache.blockChanged(1, 0, 0)
        assertFalse(cache.indexAt(Vec3.ZERO, 4.0, read).blocks(Vec3.ZERO, 3.0, 0.0, 0.0))
        assertTrue(before.blocks(Vec3.ZERO, 3.0, 0.0, 0.0))

        boxes = listOf(obstacle)
        cache.chunkChanged(0, 15, 0, 15)
        assertTrue(cache.indexAt(Vec3.ZERO, 4.0, read).blocks(Vec3.ZERO, 3.0, 0.0, 0.0))
        assertEquals(3, reads)
    }
}
