package work.nekow.particledrawing.api

/**
 * 组时间轴换算：把作者游标（`ParticleGroup.delay` 累加的毫秒）换算成程序时刻
 * （客户端求值用的 now 域，以程序下发那一刻为 0），以及服务端定时销毁要等的 tick 数。
 *
 * 每次作者会话（同一 tick 内连续录制的指令算一次会话）的起点游标映射到此刻，
 * 会话内仍按作者写下的先后依次排开，运行期追加的 `delay(n).xxx()` 读作「n tick 后」。
 *
 * 全量重发（成员变化触发的重新 arm）会让已下发的指令在客户端作废，换算从新的起点重来。
 */
internal class GroupClock {

    /** 程序起点（服务端 gameTime）；未下发时为 -1。每次全量下发前重设。 */
    private var anchorTick = -1L

    /** 上一次下发时的 tick 与游标：判定会话边界，并作为新会话的换算基准。 */
    private var lastEmitTick = Long.MIN_VALUE
    private var lastEmitCursor = 0

    /** 当前会话的起点游标与「游标 → 程序时刻」偏移（会话内固定）。 */
    private var sessionBaseCursor = 0
    private var sessionShiftMs = 0

    /** 当前会话的偏移（把待发指令整批改写时用）。 */
    val shiftMs: Int get() = sessionShiftMs

    /** 服务端下发新程序时调用：程序时刻从这一刻重新起算。 */
    fun onProgramStart(nowTick: Long) {
        anchorTick = nowTick
    }

    /** 程序已运行毫秒（未下发时为 0）。 */
    fun elapsedMs(nowTick: Long): Long =
        if (anchorTick < 0) 0L else (nowTick - anchorTick).coerceAtLeast(0L) * 50

    /** 作者游标 → 程序时刻；跨 tick 的第一条指令开启新会话。 */
    fun programTime(cursorMs: Int, nowTick: Long): Int {
        if (nowTick != lastEmitTick) {
            sessionBaseCursor = lastEmitCursor
            sessionShiftMs = (elapsedMs(nowTick) - sessionBaseCursor).toInt()
        }
        return cursorMs + sessionShiftMs
    }

    /** 一次下发完成：记下 tick 与游标，下一次跨 tick 的录制从它起算。 */
    fun onEmit(nowTick: Long, cursorMs: Int) {
        lastEmitTick = nowTick
        lastEmitCursor = cursorMs
    }

    /** 程序时刻 → 从此刻起还需等多少 tick（定时销毁用）；已经过期的按 1 tick 排，不排 0。 */
    fun ticksFromNow(programTimeMs: Int, nowTick: Long): Int =
        ((programTimeMs - elapsedMs(nowTick)) / 50).coerceAtLeast(1L).toInt()
}
