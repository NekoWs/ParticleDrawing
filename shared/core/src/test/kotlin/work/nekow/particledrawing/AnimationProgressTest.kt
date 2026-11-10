package work.nekow.particledrawing

import work.nekow.particledrawing.animation.AnimationProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 服务端权威播放进度的纯数学回归测试（服务端与客户端共用同一口径）。
 * 时间轴单位为毫秒；elapsedTicks 为 game tick（1 tick = 50ms）。
 */
class AnimationProgressTest {

    @Test
    fun staticAnimationAlwaysMsZero() {
        assertEquals(0, AnimationProgress.msAt(0, 0, true))
        assertEquals(0, AnimationProgress.msAt(5, 0, false))
        assertEquals(0, AnimationProgress.msAt(-3, 0, true))
    }

    @Test
    fun loopingWrapsModulo() {
        assertEquals(0, AnimationProgress.msAt(0, 1000, true))
        assertEquals(250, AnimationProgress.msAt(5, 1000, true))
        assertEquals(0, AnimationProgress.msAt(20, 1000, true))
        assertEquals(350, AnimationProgress.msAt(47, 1000, true))
    }

    @Test
    fun nonLoopingClampsToLastRenderedMs() {
        assertEquals(0, AnimationProgress.msAt(0, 1000, false))
        assertEquals(950, AnimationProgress.msAt(19, 1000, false))
        assertEquals(950, AnimationProgress.msAt(20, 1000, false))
        assertEquals(950, AnimationProgress.msAt(999, 1000, false))
    }

    @Test
    fun negativeElapsedClampedToStart() {
        assertEquals(0, AnimationProgress.msAt(-10, 1000, true))
        assertEquals(0, AnimationProgress.msAt(-10, 1000, false))
    }

    @Test
    fun externalClockUsesSameLastFrame() {
        assertEquals(950, AnimationProgress.lastFrameMs(1000))
        assertEquals(0, AnimationProgress.lastFrameMs(30))
        // 非循环：外部时钟给到 maxMs-1 也只能落在末帧
        assertEquals(950, AnimationProgress.seekMs(950, 1000, false))
        assertEquals(950, AnimationProgress.seekMs(999, 1000, false))
        assertEquals(950, AnimationProgress.seekMs(1_000_000, 1000, false))
        assertEquals(0, AnimationProgress.seekMs(-5, 1000, false))
        // 循环：取余，负数按圈回卷
        assertEquals(0, AnimationProgress.seekMs(1000, 1000, true))
        assertEquals(250, AnimationProgress.seekMs(250, 1000, true))
        assertEquals(900, AnimationProgress.seekMs(-100, 1000, true))
        // 不限时长：只保证非负
        assertEquals(5000, AnimationProgress.seekMs(5000, 0, false))
        assertEquals(0, AnimationProgress.seekMs(-5, 0, false))
    }

    @Test
    fun finishedOnlyForNonLoopingWithPositiveLength() {
        assertFalse(AnimationProgress.isFinished(19, 1000, false))
        assertTrue(AnimationProgress.isFinished(20, 1000, false))
        assertTrue(AnimationProgress.isFinished(999, 1000, false))
        // 循环动画永不算结束
        assertFalse(AnimationProgress.isFinished(999, 1000, true))
        // 静态（无时间轴）动画播放到显式停止
        assertFalse(AnimationProgress.isFinished(999, 0, false))
        assertFalse(AnimationProgress.isFinished(999, 0, true))
    }
}