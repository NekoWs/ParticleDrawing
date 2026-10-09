package work.nekow.particledrawing

import work.nekow.particledrawing.animation.PlaybackClock
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 特效播放时钟的推进规则：每 tick 走「倍速」毫秒，暂停不动，恢复后从暂停位置接着走。
 */
class PlaybackClockTest {

    @Test
    fun advancesBySpeedWhenPlaying() {
        val clock = PlaybackClock(position = 0.0, playing = true, speed = 1.5)
        clock.advance()
        clock.advance()
        assertEquals(3.0, clock.position)
    }

    @Test
    fun pausedClockDoesNotAdvance() {
        val clock = PlaybackClock(position = 10.0, playing = false, speed = 2.0)
        clock.advance()
        assertEquals(10.0, clock.position)
    }

    @Test
    fun resumeContinuesFromPausedPosition() {
        val clock = PlaybackClock(position = 5.0, playing = false, speed = 1.0)
        clock.advance()
        clock.playing = true
        clock.advance()
        assertEquals(6.0, clock.position)
    }
}