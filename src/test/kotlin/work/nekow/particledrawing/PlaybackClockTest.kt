package work.nekow.particledrawing

import work.nekow.particledrawing.animation.PlaybackClock
import kotlin.test.Test
import kotlin.test.assertEquals

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