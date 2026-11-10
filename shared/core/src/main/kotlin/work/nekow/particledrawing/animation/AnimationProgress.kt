package work.nekow.particledrawing.animation

// 服务端权威的播放进度计算，客户端与服务器共用同一公式。
// 进度 = wrap/clamp((gameTime - startGameTick) * 50)；客户端每 game tick 用同一公式推目标毫秒。
// 特效 API 的外部时钟直接给毫秒，走 seekMs 的同一套取余/封顶，两条路径的末帧一致。
object AnimationProgress {

    /** 非循环动画的末帧时刻：maxMs-50（末帧之后不满一个 game tick，播放头停在这里等结束）。 */
    @JvmStatic
    fun lastFrameMs(maxMs: Int): Int = (maxMs - 50).coerceAtLeast(0)

    /** elapsed game tick 对应的时间轴毫秒：maxMs<=0 恒 0；循环取余；非循环封顶 [lastFrameMs]。 */
    @JvmStatic
    fun msAt(elapsedTicks: Long, maxMs: Int, loop: Boolean): Int {
        if (maxMs <= 0) return 0
        val eMs = (elapsedTicks.coerceAtLeast(0L) * 50)
        return if (loop) (eMs % maxMs).toInt() else minOf(eMs, lastFrameMs(maxMs).toLong()).toInt()
    }

    /**
     * 外部播放时钟（特效 API / seek）定位播放头：时刻由调用方直接给，封顶与取余跟 [msAt] 同一套口径。
     * maxMs<=0（不限时长）只保证非负；循环取余（负数按圈回卷）；非循环封顶 [lastFrameMs]。
     */
    @JvmStatic
    fun seekMs(targetMs: Int, maxMs: Int, loop: Boolean): Int {
        if (maxMs <= 0) return targetMs.coerceAtLeast(0)
        return if (loop) ((targetMs % maxMs) + maxMs) % maxMs else targetMs.coerceIn(0, lastFrameMs(maxMs))
    }

    /**
     * 非循环动画是否已走完（elapsedMs >= maxMs）。
     * maxMs <= 0 视为恒播放（静态动画播放到显式停止为止）。
     */
    @JvmStatic
    fun isFinished(elapsedTicks: Long, maxMs: Int, loop: Boolean): Boolean =
        !loop && maxMs > 0 && elapsedTicks * 50 >= maxMs
}