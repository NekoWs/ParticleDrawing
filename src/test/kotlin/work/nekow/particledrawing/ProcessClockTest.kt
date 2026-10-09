package work.nekow.particledrawing

import work.nekow.particledrawing.animation.ProcessClock
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 渲染帧 process 时刻的单调游标：同一播放头基准下只前进、不回退。
 *
 * process 时刻 = 播放头毫秒 + partialTick × 每 tick 毫秒，两个来源不是同一套时钟，
 * 原版在 frozen 帧、暂停恢复、tickrate 变化时都可能给出倒退的 partialTick。
 * 播放头自身回退（循环回卷 / seek）是合法倒退，此时游标跟着倒退。
 *
 * 帧表由 [frameSchedule] 按原版计时器生成。
 */
class ProcessClockTest {

    private fun raw(baseMs: Int, p: Double, step: Double, maxMs: Int): Double {
        var t = baseMs + p * step
        if (maxMs > 0) t = minOf(t, (maxMs - 1).toDouble())
        return t
    }

    @Test
    fun `正常帧率下 process 时刻本来就单调，单调游标不改动它`() {
        for (fps in listOf(60.0, 144.0, 30.0)) {
            val clock = ProcessClock()
            var prev = Double.NEGATIVE_INFINITY
            var backward = 0
            for (step in frameSchedule(fps, 600, 300_000)) {
                val t = clock.next(step.baseMs, step.partialTick, 50.0, 300_000)
                // 正常路径上保护是空操作：输出与原始表达式逐帧相等
                assertEquals(raw(step.baseMs, step.partialTick, 50.0, 300_000), t, "正常路径不该被游标改动（${fps}fps）")
                if (t < prev) backward++
                prev = t
            }
            assertEquals(0, backward, "${fps}fps 下正常路径不该出现回退")
        }
    }

    @Test
    fun `partialTick 异常回落时 process 时刻不回退`() {
        val clock = ProcessClock()
        val schedule = frameSchedule(60.0, 200, 300_000, frozenFrames = setOf(60, 61, 120))
        var prev = Double.NEGATIVE_INFINITY
        var maxBackward = 0.0
        var maxHold = 0.0
        for (step in schedule) {
            val t = clock.next(step.baseMs, step.partialTick, 50.0, 300_000)
            maxBackward = max(maxBackward, prev - t)
            // 保护生效时 t 高于原始表达式：钳掉的量 = t - raw
            maxHold = max(maxHold, t - raw(step.baseMs, step.partialTick, 50.0, 300_000))
            prev = t
        }
        assertEquals(0.0, maxBackward, "单调游标不许让 process 时刻回退")
        // frozen 帧之后那一帧被钳住
        assertTrue(maxHold > 1.0, "冻结帧之后的回落应当被钳住，实际钳掉 ${maxHold}ms")
    }

    @Test
    fun `播放头回退（循环回卷 seek）时 process 时刻跟着倒退`() {
        val clock = ProcessClock()
        for (base in 0..600 step 50) {
            assertEquals(base + 37.5, clock.next(base, 0.75, 50.0, 300_000), 1e-9, "前进段应当等于原始表达式")
        }
        // 回卷到 100ms：播放头回退是合法倒退，游标跟着回去
        assertEquals(100.0, clock.next(100, 0.0, 50.0, 300_000), 1e-9, "回卷后 process 时刻必须跟着倒退")
        assertEquals(150.0, clock.next(150, 0.0, 50.0, 300_000), 1e-9)
    }

    @Test
    fun `时间轴末端封顶后仍然单调，非法 partialTick 不漏 NaN`() {
        val clock = ProcessClock()
        assertEquals(999.0, clock.next(950, 1.0, 50.0, 1000), 1e-9)
        assertEquals(999.0, clock.next(950, 1.0, 50.0, 1000), 1e-9)
        // 非有限 partialTick：退回本 tick 播放头，不把 NaN 交给脚本
        assertEquals(999.0, clock.next(999, Double.NaN, 50.0, 1000), 1e-9)
        assertEquals(999.0, clock.next(999, 0.5, 50.0, 1000), 1e-9)
    }

    @Test
    fun `循环回卷（播放头从末端回到圈首）时游标跟着回到圈首`() {
        // maxMs=1000、每 tick 50ms：播放头 950 → 0
        val clock = ProcessClock()
        assertEquals(975.0, clock.next(950, 0.5, 50.0, 1000), 1e-9)
        assertEquals(0.0, clock.next(0, 0.0, 50.0, 1000), 1e-9, "回卷后第一帧应回到圈首，不能被钳在 975")
        assertEquals(25.0, clock.next(0, 0.5, 50.0, 1000), 1e-9)
        assertEquals(50.0, clock.next(50, 0.0, 50.0, 1000), 1e-9)
    }
}
