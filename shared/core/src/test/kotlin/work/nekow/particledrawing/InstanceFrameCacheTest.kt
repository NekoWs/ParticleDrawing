package work.nekow.particledrawing

import work.nekow.particledrawing.render.InstanceFrameCache
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstanceFrameCacheTest {
    @Test fun `相同刻复用数据，成员或采样变化触发重打包`() {
        val cache = InstanceFrameCache<Any>()
        val a = Any()
        val b = Any()
        val visible = arrayListOf(a, b)
        assertTrue(cache.needsPacking(4, 1, visible, false))
        assertFalse(cache.needsPacking(4, 1, visible, false))
        visible.reverse()
        assertTrue(cache.needsPacking(4, 1, visible, false))
        assertFalse(cache.needsPacking(4, 1, visible, false))
        assertTrue(cache.needsPacking(5, 1, visible, false))
        assertTrue(cache.needsPacking(5, 2, visible, false))
        assertTrue(cache.needsPacking(5, 2, visible, true))
        assertFalse(cache.needsPacking(5, 2, visible, false))
        cache.clear()
        assertTrue(cache.needsPacking(5, 2, visible, false))
    }
}
