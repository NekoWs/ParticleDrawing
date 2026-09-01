package work.nekow.particledrawing.animation

/**
 * 服务端权威的播放进度计算（客户端与服务器共用，保证所有玩家看到的帧一致）。
 *
 * 进度时钟 = 维度 gameTime（所有客户端与服务端一致）：
 * - 播放创建时记录 [ServerAnimationManager] 的 startGameTick；
 * - 任意时刻的进度 = wrap/clamp(elapsed = gameTime - startGameTick)。
 * 客户端每 game tick 用同一公式推目标 tick，而不是各自本地递增，
 * 因此无论初始接收、迟到加入还是重连/切维度重发，所有玩家帧号完全一致。
 */
object AnimationProgress {

    /**
     * elapsed tick 对应的时间轴 tick：
     * - [maxTick] <= 0（静态/无时间轴动画）：恒 0；
     * - 循环：elapsed % maxTick；
     * - 非循环：封顶到 maxTick - 1（客户端渲染到该帧后，下一 tick 判定结束）。
     */
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