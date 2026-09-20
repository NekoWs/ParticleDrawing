package work.nekow.particledrawing

import work.nekow.particledrawing.animation.AnimationProgress
import kotlin.math.max

/**
 * 原版计时器与渲染帧表的测试替身（逐行对照 MC 26.2 的 `DeltaTracker.Timer` 与 `Minecraft.runTick`）。
 *
 * 一帧的顺序是：`advanceGameTime(Util.getMillis())` → 跑它返回的那么多个 game tick → 渲染。
 * `advanceGameTime` 把「整毫秒差 ÷ max(50, tickRateManager.millisecondsPerTick())」累进 residual，
 * 取整数部分当本帧的 tick 数，剩下的小数就是 `getGameTimeDeltaPartialTick` 的返回值。
 * 所以原版 partialTick 是「tick 残余小数」，播放头毫秒是「跑过几个 tick × 50 ms」，
 * 两者拼起来才是渲染帧的 process 时刻——测试用这个序列，而不是自己编一个 partialTick。
 * `frozen` 帧（关卡不按常速跑）原版直接返回 1.0F，见 [frameSchedule] 的 frozenFrames。
 */
class VanillaFrameTimer(private val fps: Double) {

    private var lastMs = 0L
    private var wall = 0.0
    var residual = 0.0
        private set

    /** 推进一帧，返回本帧要跑的 game tick 数（原版 advanceGameTime 的返回值）。 */
    fun nextFrame(): Int {
        wall += 1000.0 / fps
        val now = wall.toLong()
        // targetMspt = max(defaultTickTargetMillis, tickRateManager.millisecondsPerTick())
        val deltaTicks = (now - lastMs) / max(50.0, DEFAULT_MS_PER_TICK)
        lastMs = now
        residual += deltaTicks
        val ticks = residual.toInt()
        residual -= ticks
        return ticks
    }

    private companion object {
        const val DEFAULT_MS_PER_TICK = 50.0
    }
}

/** 一帧的帧表项：本帧要跑的 game tick 数 + 帧内渲染时刻（播放头毫秒与 partialTick）。 */
class FrameStep(val ticks: Int, val baseMs: Int, val partialTick: Double)

/**
 * 生成 [frames] 帧的帧表。
 * @param frozenFrames 这些帧号模拟原版 frozen 帧：`getGameTimeDeltaPartialTick(false)` 返回 1.0F
 */
fun frameSchedule(
    fps: Double,
    frames: Int,
    maxMs: Int,
    loop: Boolean = false,
    frozenFrames: Set<Int> = emptySet(),
): List<FrameStep> {
    val timer = VanillaFrameTimer(fps)
    var gameTick = 0L
    val out = ArrayList<FrameStep>(frames)
    for (f in 0 until frames) {
        val ticks = timer.nextFrame()
        gameTick += ticks
        val base = AnimationProgress.msAt(gameTick, maxMs, loop)
        out.add(FrameStep(ticks, base, if (f in frozenFrames) 1.0 else timer.residual))
    }
    return out
}
