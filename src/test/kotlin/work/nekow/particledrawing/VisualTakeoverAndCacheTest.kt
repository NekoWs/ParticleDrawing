package work.nekow.particledrawing

import work.nekow.particledrawing.core.client.LightCachePolicy
import work.nekow.particledrawing.core.client.ParticleTakeover
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 两条「接管 / 缓存」语义的纯逻辑：
 * - **位置接管 ≠ 外观接管**：`track` 只接管位置，寿命曲线必须继续逐帧驱动外观；
 * - **光照缓存失效**：动态光变了立刻重查，世界光变化用分摊超时兜住；
 * - **光照缓存失效条件**（动态光版本、方块、分摊超时）。
 */
class VisualTakeoverAndCacheTest {

    // —— 位置/外观接管 ——

    @Test
    fun `track 只接管位置：外观仍归寿命曲线管`() {
        val takeover = ParticleTakeover()
        val tracked = UUID.randomUUID()

        takeover.markPosition(tracked)

        assertTrue(takeover.hasPosition(tracked), "位置归直写管：分批缓动与速度积分要让位")
        assertFalse(takeover.hasAppearance(tracked), "外观不该被一起接管——否则尺寸曲线冻结在出生那一帧")
    }

    @Test
    fun `动画与程序的完整直写同时接管位置与外观`() {
        val takeover = ParticleTakeover()
        val direct = UUID.randomUUID()
        takeover.markAppearance(direct)
        assertTrue(takeover.hasPosition(direct))
        assertTrue(takeover.hasAppearance(direct))
    }

    @Test
    fun `交还接管权后两者都清掉（缓动或速度重新接手）`() {
        val takeover = ParticleTakeover()
        val id = UUID.randomUUID()
        takeover.markAppearance(id)
        takeover.markPosition(id)
        takeover.clear(id)
        assertFalse(takeover.hasPosition(id))
        assertFalse(takeover.hasAppearance(id))
    }

    // —— 光照缓存 ——

    @Test
    fun `动态光版本一变就重查，与超时无关`() {        val now = 1_000_000_000L
        val due = now + LightCachePolicy.REFRESH_NANOS
        assertTrue(
            LightCachePolicy.shouldQuery(true, blockChanged = false, lightVersionChanged = true, dueNanos = due, nowNanos = now),
            "光源移动/销毁/关掉：下一帧就得恢复正确亮度",
        )
        assertFalse(
            LightCachePolicy.shouldQuery(true, blockChanged = false, lightVersionChanged = false, dueNanos = due, nowNanos = now),
            "什么都没变就复用缓存（5w 粒子不能每帧全量查）",
        )
    }

    @Test
    fun `方块变了立刻重查，没有缓存也重查`() {
        val now = 5_000L
        val due = now + LightCachePolicy.REFRESH_NANOS
        assertTrue(LightCachePolicy.shouldQuery(true, blockChanged = true, lightVersionChanged = false, dueNanos = due, nowNanos = now))
        assertTrue(LightCachePolicy.shouldQuery(false, blockChanged = false, lightVersionChanged = false, dueNanos = 0L, nowNanos = now))
    }

    @Test
    fun `世界光变化：到点重查，且重查时刻按粒子散开`() {
        val now = 10_000_000L
        val due = now - 1L
        assertTrue(
            LightCachePolicy.shouldQuery(true, blockChanged = false, lightVersionChanged = false, dueNanos = due, nowNanos = now),
            "超时到点必须重查，否则插了火把/昼夜变化的片元永远不变",
        )

        val span = LightCachePolicy.REFRESH_NANOS
        val offsets = (0 until 64).map { LightCachePolicy.jitterNanos(it * 7919) }
        assertTrue(offsets.all { it >= 0L && it < span }, "抖动必须落在 [0, 周期) 内")
        assertTrue(offsets.distinct().size > 32, "不同粒子应错开到期时刻（否则同帧几万次查询）")

        val nextDue = LightCachePolicy.nextDueNanos(now, 12345)
        assertTrue(nextDue >= now + span && nextDue < now + 2 * span, "下次重查落在 [周期, 2×周期) 内")
    }

}

