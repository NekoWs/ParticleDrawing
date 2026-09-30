package work.nekow.particledrawing

import work.nekow.particledrawing.api.GroupClock
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 组时间轴换算：作者游标（`delay` 累加的毫秒）→ 程序时刻（客户端 `now` 域）。
 *
 * 钉住两条使用方最在意的语义：**同一 tick 内连续录制算一次会话**（会话里 delay 依次排开，
 * 于是「逐块延迟」是阶梯），**跨 tick 追加则从追加那一刻重新起算**（长寿组运行期追加的
 * `delay(1).fadeOut(5)` 不会被已经跑过的时长吞成「瞬间完成」）。
 */
class GroupClockTest {

    private val anchorTick = 1_000L

    /** 首次全量下发（程序起点）。 */
    private fun armed(): GroupClock = GroupClock().apply { onProgramStart(anchorTick) }

    @Test
    fun `建组时录制的指令按绝对游标下发`() {
        val clock = armed()
        assertEquals(0, clock.programTime(0, anchorTick))
        assertEquals(250, clock.programTime(250, anchorTick))
        clock.onEmit(anchorTick, 250)
        assertEquals(5, clock.ticksFromNow(250, anchorTick), "250ms = 5 tick")
    }

    @Test
    fun `运行中追加的 delay 从现在起算，不被已运行时长吞掉`() {
        val clock = armed()
        clock.onEmit(anchorTick, 0) // 建组时录完（游标停在 0）

        val later = anchorTick + 100 // 跑了 100 tick = 5000ms
        val at = clock.programTime(50, later) // delay(1) → 游标 50

        assertEquals(5050, at, "delay(1) 应该是「现在 + 1 tick」，而不是落在程序起点附近")
        assertEquals(5000, clock.elapsedMs(later))
        assertEquals(1, clock.ticksFromNow(at, later), "服务端销毁要按相对 now 排")
    }

    @Test
    fun `同一 tick 内连续录制属于一次会话，延迟依次累加`() {
        val clock = armed()
        clock.onEmit(anchorTick, 0)

        val later = anchorTick + 100
        val first = clock.programTime(250, later)
        clock.onEmit(later, 250)
        val second = clock.programTime(500, later)
        clock.onEmit(later, 500)
        val third = clock.programTime(750, later)

        assertEquals(5250, first)
        assertEquals(5500, second)
        assertEquals(5750, third, "同一会话里逐块 delay 必须是阶梯，不能都堆在同一时刻")
    }

    @Test
    fun `下一次会话以会话结束时的游标为基准`() {
        val clock = armed()
        clock.onEmit(anchorTick, 0)

        // 第一段追加：跑了 100 tick，作者游标 0 → 500
        val t1 = anchorTick + 100
        clock.programTime(250, t1)
        clock.onEmit(t1, 250)
        clock.programTime(500, t1)
        clock.onEmit(t1, 500)

        // 第二段追加：又跑了 50 tick（=2500ms），作者游标 500 → 750
        val t2 = t1 + 50
        val at = clock.programTime(750, t2)
        assertEquals(7750, at, "7500（已运行）+ 250（本段 delay）")
        assertEquals(5, clock.ticksFromNow(at, t2))
    }

    @Test
    fun `全量重发后时间轴从新的起点重算`() {
        val clock = armed()
        clock.onEmit(anchorTick, 0)
        val t1 = anchorTick + 100
        clock.programTime(500, t1)
        clock.onEmit(t1, 500)

        // 成员变化触发全量重发：程序在 t2 重新 arm，游标继续累积
        val t2 = t1 + 20
        clock.onProgramStart(t2)
        val at = clock.programTime(750, t2)

        assertEquals(250, at, "重发即新的程序起点，本会话的 delay 从 0 起算")
        assertEquals(750, clock.elapsedMs(t2 + 15), "已运行时长跟着新的起点走")
    }

    @Test
    fun `已经过期的销毁时刻按 1 tick 排，不排 0`() {
        val clock = armed()
        clock.onEmit(anchorTick, 0)
        val later = anchorTick + 100
        assertEquals(1, clock.ticksFromNow(0, later))
        assertEquals(1, clock.ticksFromNow(-5000, later))
    }
}
