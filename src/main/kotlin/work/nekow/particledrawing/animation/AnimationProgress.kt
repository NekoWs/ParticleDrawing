package work.nekow.particledrawing.animation

// 服务端权威的播放进度计算（客户端与服务器共用，保证所有玩家帧一致）。
// 进度 = wrap/clamp(elapsed = gameTime - startGameTick)；客户端每 tick 用同一公式推目标 tick，不各自递增。
object AnimationProgress {

    /** elapsed tick 对应的时间轴 tick：maxTick<=0 恒 0；循环取余；非循环封顶 maxTick-1。 */
    @JvmStatic
    fun tickAt(elapsedTicks: Long, maxTick: Int, loop: Boolean): Int {
        if (maxTick <= 0) return 0
        val e = elapsedTicks.coerceAtLeast(0L)
        return if (loop) (e % maxTick).toInt() else minOf(e, (maxTick - 1).toLong()).toInt()
    }

    /**
     * 非循环动画是否已走完（elapsed >= maxTick）。
     * maxTick <= 0 视为恒播放（静态动画播放到显式停止为止）。
     */
    @JvmStatic
    fun isFinished(elapsedTicks: Long, maxTick: Int, loop: Boolean): Boolean =
        !loop && maxTick > 0 && elapsedTicks >= maxTick
}