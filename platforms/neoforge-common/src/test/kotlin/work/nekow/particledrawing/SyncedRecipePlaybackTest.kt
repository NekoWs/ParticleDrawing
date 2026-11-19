package work.nekow.particledrawing

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.core.client.AppearanceBlend
import work.nekow.particledrawing.core.client.RenderParticle
import work.nekow.particledrawing.core.client.TickFrameQueue
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncedRecipePlaybackTest {
    @Test
    fun `一刻空档后两包同到仍按相邻服务器刻播放`() {
        val queue = TickFrameQueue<Int>(capacity = 4, reserve = 1)
        queue.offer(0)
        assertEquals(0, queue.advance())
        queue.offer(1)
        assertNull(queue.advance())
        queue.offer(2)
        assertEquals(1, queue.advance())

        // 网络交替有一刻空档与两包同到，备用的一刻继续提供下一段。
        for (tick in 2..119) {
            if (tick % 2 == 1) {
                queue.offer(tick)
                queue.offer(tick + 1)
            }
            assertEquals(tick, queue.advance())
        }
        assertEquals(120, queue.advance())
        assertNull(queue.advance())
        queue.offer(121)
        assertNull(queue.advance())
        queue.offer(122)
        assertEquals(121, queue.advance())
    }

    @Test
    fun `溢出帧可供记录历史且空帧结束前仍会播放短突发`() {
        val queue = TickFrameQueue<Int>(capacity = 4, reserve = 1)
        val skipped = (0..6).mapNotNull(queue::offer)
        assertEquals(listOf(0, 1, 2), skipped)
        assertEquals(4, queue.size)
        assertEquals(listOf(3, 4, 5, 6), List(4) { queue.advance(drain = true) })
        assertNull(queue.advance())

        val burst = TickFrameQueue<List<Int>>(capacity = 4, reserve = 1)
        burst.offer(listOf(1))
        burst.offer(emptyList())
        assertEquals(listOf(1), burst.advance(drain = true))
        assertEquals(emptyList(), burst.advance(drain = true))
    }

    @Test
    fun `无新包时多个子帧保持位置寿命外观恢复后从保持点插值`() {
        var tick = 0L
        val blend = AppearanceBlend { tick }
        val particle = RenderParticle(UUID.randomUUID(), Vec3.ZERO, Color.WHITE, 1f, false, 0, 20)
        blend.initialize(1f, 1f, 1f, 1f, 1f, 1f)
        blend.setColor(1f, 1f, 1f, 1f)
        blend.setScale(1f, 1f)
        particle.syncRecipeAge(4, snap = true)

        tick++
        particle.setPositionDirect(Vec3(2.0, 0.0, 0.0))
        particle.syncRecipeAge(5, snap = false)
        blend.setColor(1f, 1f, 1f, 0.5f)
        blend.setScale(0.5f, 0.5f)
        val fractions = (0..8).map { it / 8f }
        assertTrue(fractions.map(particle::interpolatedX).zipWithNext().all { (a, b) -> a < b })
        assertEquals(4.5f, particle.ageTicks(0.5f))
        assertEquals(0.75f, blend.alphaAt(0.5f))

        particle.holdRecipeFrame()
        blend.hold()
        repeat(3) {
            tick++
            for (fraction in fractions) {
                assertEquals(2.0, particle.interpolatedX(fraction))
                assertEquals(5f, particle.ageTicks(fraction))
                assertEquals(0.5f, blend.alphaAt(fraction))
                assertEquals(0.5f, blend.widthAt(fraction))
            }
        }
        particle.setPositionDirect(Vec3(4.0, 0.0, 0.0))
        particle.syncRecipeAge(6, snap = false)
        blend.setColor(1f, 1f, 1f, 0f)
        blend.setScale(0.25f, 0.25f)
        assertEquals(3.0, particle.interpolatedX(0.5f))
        assertEquals(5.5f, particle.ageTicks(0.5f))
        assertEquals(0.25f, blend.alphaAt(0.5f))
        assertEquals(0.375f, blend.widthAt(0.5f))
    }
}
