package work.nekow.particledrawing

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.core.client.MovableAnchorSamples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MovableAnchorSamplesTest {
    @Test
    fun `两条样本之间按 partialTick 线性插值`() {
        val s = MovableAnchorSamples()
        val t0 = 1_000_000_000L
        s.onSample(Vec3(0.0, 0.0, 0.0), t0)
        s.onSample(Vec3(2.0, 0.0, 0.0), t0 + 50_000_000L)

        assertEquals(0.0, s.resolve(0f, t0 + 50_000_000L)!!.x, 1e-9, "partialTick=0 时在上一位置")
        assertEquals(1.0, s.resolve(0.5f, t0 + 50_000_000L)!!.x, 1e-9)
        assertEquals(2.0, s.resolve(1f, t0 + 50_000_000L)!!.x, 1e-9, "partialTick=1 时在当前位置")
    }

    @Test
    fun `瞬移按跳变处理：两条样本不再拉开一段扫掠`() {
        val s = MovableAnchorSamples()
        val t = 2_000_000_000L
        s.onSample(Vec3(0.0, 0.0, 0.0), t)
        s.onSample(Vec3(500.0, 0.0, 0.0), t + 50_000_000L)

        assertEquals(500.0, s.resolve(0f, t + 50_000_000L)!!.x, 1e-9, "partialTick=0 就该在新位置")
        assertEquals(500.0, s.resolve(1f, t + 50_000_000L)!!.x, 1e-9)
    }

    @Test
    fun `断流时停在最后位置，恢复后的第一条按跳变处理`() {
        val s = MovableAnchorSamples()
        val t = 3_000_000_000L
        s.onSample(Vec3(0.0, 0.0, 0.0), t)
        s.onSample(Vec3(1.0, 0.0, 0.0), t + 50_000_000L)

        // 断流 500ms：不外推、停在 1.0
        val staleNow = t + 50_000_000L + 500_000_000L
        assertEquals(1.0, s.resolve(0.9f, staleNow)!!.x, 1e-9)

        // 恢复：新样本不再从旧的 1.0 扫过去
        s.onSample(Vec3(1.6, 0.0, 0.0), staleNow)
        assertEquals(1.6, s.resolve(0f, staleNow)!!.x, 1e-9)
        assertEquals(1.6, s.resolve(1f, staleNow)!!.x, 1e-9)
    }

    @Test
    fun `还没有样本时得不到发射位置`() {
        val s = MovableAnchorSamples()
        assertNull(s.resolve(0.5f, 0L))
        val first = Vec3(2.0, 0.0, 0.0)
        s.onSample(first, 1L)
        assertEquals(first, s.resolve(0.5f, 1L))
    }
}
