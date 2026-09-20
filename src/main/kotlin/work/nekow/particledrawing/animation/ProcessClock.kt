package work.nekow.particledrawing.animation

import kotlin.math.min

/**
 * 渲染帧 process 时刻的单调游标。
 *
 * process 的目标时刻 =「播放头毫秒（每 game tick 前进一次）+ partialTick × 每 tick 毫秒」。
 * 两个来源不是同一套时钟，凑不出单调序列：原版 DeltaTracker 在关卡不按常速跑时直接把 partialTick
 * 给成 1.0（frozen 帧）、暂停恢复时把残余量回填成暂停前的值、tickrate 与固定 50ms 口径不一致时
 * 帧内推进量对不上。process 的语义是「只向前推进一帧」，往回跳会让脚本攒的环形缓冲/包络/累计量
 * 倒着重算（迹线在一个 tick 内来回抖一帧）。所以这里只放行前进：同一播放头基准下取历史最大值。
 *
 * 播放头自身回退是合法倒退（循环回卷、seek），此时丢下游标重新起算。
 */
class ProcessClock {

    private var lastBaseMs = Int.MIN_VALUE
    private var lastMs = Double.NEGATIVE_INFINITY

    /**
     * 本帧的 process 时刻。
     * @param baseMs 播放头毫秒（本 tick 的权威时刻）
     * @param partialTick 渲染帧在 tick 内的进度（0..1）
     * @param msPerTick 每 game tick 的时间轴毫秒（倍速播放时不是 50）
     * @param maxMs 时间轴长度；>0 时封顶到 maxMs-1，与播放头口径一致
     */
    fun next(baseMs: Int, partialTick: Double, msPerTick: Double, maxMs: Int): Double {
        // 播放头回退 = 循环回卷 / seek：合法倒退，丢掉游标重新起算
        if (baseMs < lastBaseMs) lastMs = Double.NEGATIVE_INFINITY
        var t = baseMs + partialTick * msPerTick
        if (maxMs > 0) t = min(t, (maxMs - 1).toDouble())
        // 非法 partialTick 别把 NaN 交给脚本：退回本 tick 的播放头
        if (!t.isFinite()) t = baseMs.toDouble()
        // 同一播放头基准下只放行前进
        if (t > lastMs) lastMs = t
        lastBaseMs = baseMs
        return lastMs
    }
}
