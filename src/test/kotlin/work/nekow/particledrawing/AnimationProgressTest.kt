package work.nekow.particledrawing

import work.nekow.particledrawing.animation.AnimationProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 服务端权威播放进度的纯数学回归测试（服务端与客户端共用同一口径）。
 */
class AnimationProgressTest {

    @Test
    fun staticAnimationAlwaysTickZero() {
        assertEquals(0, AnimationProgress.tickAt(0, 0, true))
        assertEquals(0, AnimationProgress.tickAt(5, 0, false))
        assertEquals(0, AnimationProgress.tickAt(-3, 0, true))
    }

    @Test
    fun loopingWrapsModulo() {
        assertEquals(0, AnimationProgress.tickAt(0, 20, true))
        assertEquals(5, AnimationProgress.tickAt(5, 20, true))
        assertEquals(0, AnimationProgress.tickAt(20, 20, true))
        assertEquals(7, AnimationProgress.tickAt(47, 20, true))
    }

    @Test
    fun nonLoopingClampsToLastRenderedTick() {
        assertEquals(0, AnimationProgress.tickAt(0, 20, false))
        assertEquals(19, AnimationProgress.tickAt(19, 20, false))
        assertEquals(19, AnimationProgress.tickAt(20, 20, false))
        assertEquals(19, AnimationProgress.tickAt(999, 20, false))
    }

    @Test
    fun negativeElapsedClampedToStart() {
        assertEquals(0, AnimationProgress.tickAt(-10, 20, true))
        assertEquals(0, AnimationProgress.tickAt(-10, 20, false))
    }

    @Test
    fun finishedOnlyForNonLoopingWithPositiveLength() {
        assertFalse(AnimationProgress.isFinished(19, 20, false))
        assertTrue(AnimationProgress.isFinished(20, 20, false))
        assertTrue(AnimationProgress.isFinished(999, 20, false))
        // 循环动画永不算结束
        assertFalse(AnimationProgress.isFinished(999, 20, true))
        // 静态（无时间轴）动画播放到显式停止
        assertFalse(AnimationProgress.isFinished(999, 0, false))
        assertFalse(AnimationProgress.isFinished(999, 0, true))
    }
}