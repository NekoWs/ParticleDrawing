package work.nekow.particledrawing.animation

// 服务端权威的播放进度计算（客户端与服务器共用，保证所有玩家帧一致）。
// 进度 = wrap/clamp(elapsedMs = (gameTime - startGameTick) * 50)；客户端每 game tick 用同一公式推目标毫秒，不各自递增。
object AnimationProgress {

    /** elapsed game tick 对应的时间轴毫秒：maxMs<=0 恒 0；循环取余；非循环封顶 maxMs-50。 */
    @JvmStatic
    fun msAt(elapsedTicks: Long, maxMs: Int, loop: Boolean): Int {
        if (maxMs <= 0) return 0
        val eMs = (elapsedTicks.coerceAtLeast(0L) * 50)
        return if (loop) (eMs % maxMs).toInt() else minOf(eMs, (maxMs - 50).toLong().coerceAtLeast(0L)).toInt()
    }

    /**
     * 非循环动画是否已走完（elapsedMs >= maxMs）。
     * maxMs <= 0 视为恒播放（静态动画播放到显式停止为止）。
     */
    @JvmStatic
    fun isFinished(elapsedTicks: Long, maxMs: Int, loop: Boolean): Boolean =
        !loop && maxMs > 0 && elapsedTicks * 50 >= maxMs
}