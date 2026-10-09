package work.nekow.particledrawing.animation

import kotlin.math.min

/**
 * 渲染帧 process 时刻的单调游标：同一播放头基准下只前进、不回退。
 *
 * process 时刻 = 播放头毫秒 + partialTick × 每 tick 毫秒，两个来源不是同一套时钟，
 * 原版在 frozen 帧、暂停恢复、tickrate 变化时都可能给出倒退的 partialTick。
 * 播放头自身回退（循环回卷 / seek）是合法倒退，此时丢下游标重新起算。
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
        // 非法 partialTick 退回本 tick 的播放头，不把 NaN 交给脚本
        if (!t.isFinite()) t = baseMs.toDouble()
        // 同一播放头基准下只放行前进
        if (t > lastMs) lastMs = t
        lastBaseMs = baseMs
        return lastMs
    }
}
