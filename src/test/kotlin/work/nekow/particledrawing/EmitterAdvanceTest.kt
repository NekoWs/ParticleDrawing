package work.nekow.particledrawing

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.core.client.DistanceAdvance
import work.nekow.particledrawing.core.client.TimeAdvance
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 运行时发射器的推进逻辑（纯数学）：里程口径必须**落在段内的等距点上**——
 * 这正是「服务端每 tick 采样、逐颗下发」做不到的部分（出现时刻被 tick 量化，高刷下一跳一跳地长）。
 */
class EmitterAdvanceTest {

    private fun emitAlong(advance: DistanceAdvance, from: Vec3, to: Vec3): List<Vec3> {
        val out = ArrayList<Vec3>()
        advance.advance(from, to, out)
        return out
    }

    @Test
    fun `里程口径：一帧走多远都按等距切分，位置落在段内`() {
        val advance = DistanceAdvance(0.25)
        val points = emitAlong(advance, Vec3.ZERO, Vec3(1.0, 0.0, 0.0))
        assertEquals(listOf(0.25, 0.5, 0.75, 1.0), points.map { it.x }, "1 格走完正好 4 颗，位置严格等距")

        // 下一帧继续走：间距从上一帧的余量接着算，不重不漏
        val more = emitAlong(advance, Vec3(1.0, 0.0, 0.0), Vec3(1.5, 0.0, 0.0))
        assertEquals(listOf(1.25, 1.5), more.map { it.x })
    }

    @Test
    fun `里程口径：慢速时攒里程、跨帧补发，间距不随帧率变`() {
        val advance = DistanceAdvance(0.5)
        // 每帧 0.2 格：第 1、2 帧不发射，第 3 帧发射一颗（累计 0.6 → 0.5 处）
        assertTrue(emitAlong(advance, Vec3.ZERO, Vec3(0.2, 0.0, 0.0)).isEmpty())
        assertTrue(emitAlong(advance, Vec3(0.2, 0.0, 0.0), Vec3(0.4, 0.0, 0.0)).isEmpty())
        val third = emitAlong(advance, Vec3(0.4, 0.0, 0.0), Vec3(0.6, 0.0, 0.0))
        assertEquals(1, third.size)
        assertEquals(0.5, third[0].x, 1e-9, "第一颗必须落在累计 0.5 格处，而不是段终点")
        assertEquals(0.1, advance.carry(), 1e-9, "余量留到下一帧")
    }

    @Test
    fun `里程口径：锚点不动不发射，方向任意时按真实距离切分`() {
        val advance = DistanceAdvance(1.0)
        assertTrue(emitAlong(advance, Vec3(5.0, 5.0, 5.0), Vec3(5.0, 5.0, 5.0)).isEmpty(), "原地不动不产出")

        val points = emitAlong(advance, Vec3.ZERO, Vec3(3.0, 4.0, 0.0))
        assertEquals(5, points.size, "长度 5 的段、间距 1 → 5 颗")
        assertEquals(0.6, points[0].x, 1e-9, "沿直线按真实长度 1 格处发射")
        assertEquals(0.8, points[0].y, 1e-9)
        assertEquals(1.0, sqrt(points[0].x * points[0].x + points[0].y * points[0].y), 1e-9)
        assertEquals(3.0, points[4].x, 1e-9, "最后一颗落在段终点")
    }

    @Test
    fun `时间口径：按毫秒累积、单帧补齐多颗但不超过上限`() {
        val advance = TimeAdvance(10.0)
        assertEquals(0, advance.advance(4.0))
        assertEquals(4.0, advance.carry(), 1e-9)
        assertEquals(1, advance.advance(7.0), "累计 11ms → 1 颗")
        assertEquals(1.0, advance.carry(), 1e-9, "余 1ms 留到下一帧")
        assertEquals(3, advance.advance(30.0), "累计 31ms → 3 颗")

        val capped = TimeAdvance(1.0, maxPerFrame = 5)
        assertEquals(5, capped.advance(1000.0), "掉帧补发有上限，不堆积成一帧炸出几百颗")
        assertEquals(0, capped.advance(0.0), "零时长不发射")
    }
}
