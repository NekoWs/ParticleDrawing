package work.nekow.particledrawing

import work.nekow.particledrawing.core.client.ParticleLightIndex
import work.nekow.particledrawing.core.client.lightsUp
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 发光索引与判定规则：动态光照每帧只遍历这批 id，漏一个光源就是画面里少一块光，
 * 多一个则白跑一次采样，因此把规则和集合翻转都钉住。
 */
class ParticleLightIndexTest {

    @Test
    fun `只有显式发光且等级大于零才算光源`() {
        assertFalse(lightsUp(emitsLight = false, lightLevel = 15), "glow 只免光照，不再自动投光")
        assertTrue(lightsUp(emitsLight = true, lightLevel = 1))
        assertFalse(lightsUp(emitsLight = true, lightLevel = 0))
        assertFalse(lightsUp(emitsLight = false, lightLevel = 0))
    }

    @Test
    fun `判定翻转时才改动集合`() {
        val index = ParticleLightIndex()
        val id = UUID.randomUUID()

        index.refresh(id, wasLight = false, nowLight = true)
        assertTrue(id in index)
        assertFalse(index.isEmpty)

        // 仍然是光源：集合保持原样
        index.refresh(id, wasLight = true, nowLight = true)
        assertTrue(id in index)

        index.refresh(id, wasLight = true, nowLight = false)
        assertFalse(id in index)
        assertTrue(index.isEmpty)
    }

    @Test
    fun `重复加入与移除不放大集合`() {
        val index = ParticleLightIndex()
        val id = UUID.randomUUID()
        index.add(id)
        index.add(id)
        assertTrue(index.size == 1)

        index.remove(id)
        index.remove(id)
        assertTrue(index.isEmpty)
    }

    @Test
    fun `清空后不再遍历任何 id`() {
        val index = ParticleLightIndex()
        val seen = ArrayList<UUID>()
        repeat(3) { index.add(UUID.randomUUID()) }
        index.forEach { seen.add(it) }
        assertTrue(seen.size == 3)

        index.clear()
        seen.clear()
        index.forEach { seen.add(it) }
        assertTrue(seen.isEmpty())
    }
}
