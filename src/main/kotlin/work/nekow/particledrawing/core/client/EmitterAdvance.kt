package work.nekow.particledrawing.core.client

import net.minecraft.world.phys.Vec3

// 发射器的推进逻辑：纯数学，不碰渲染与网络，便于单测。
// 里程口径按段内插值发射，因为要的是一颗正好落在段中间的位置。

/**
 * 按里程发射的推进器：喂进锚点每帧的插值位置，吐出这一帧该在哪些位置发射。
 *
 * 锚点从 [from] 走到 [to]，累计里程每满 [spacing] 格发射一颗，位置落在段内的等距点上，
 * 所以粒子间距不受帧率影响。
 */
internal class DistanceAdvance(private val spacing: Double) {

    init {
        require(spacing > 0.0) { "spacing 必须为正" }
    }

    private var carry = 0.0

    /** 未满一格攒下的里程，诊断与测试用。 */
    fun carry(): Double = carry

    /** 沿 [from] → [to] 推进，返回本帧要发射的位置（0..n 个，按先后顺序）。 */
    fun advance(from: Vec3, to: Vec3, out: MutableList<Vec3>) {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val dz = to.z - from.z
        val dist = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        if (dist <= 0.0) return

        val total = carry + dist
        if (total < spacing) {
            carry = total
            return
        }
        // 本段里第一颗的位置：距段起点 (spacing - carry) 格，之后每 spacing 格一颗
        var offset = spacing - carry
        var emitted = 0
        while (offset <= dist) {
            val f = offset / dist
            out.add(Vec3(from.x + dx * f, from.y + dy * f, from.z + dz * f))
            emitted++
            offset += spacing
        }
        // 余下的里程留到下一帧，恒 < spacing
        carry = total - emitted * spacing
    }
}

/**
 * 按时间发射的推进器：喂进两帧之间的毫秒数，吐出这一帧该发射几颗。
 *
 * 时间粒度跟渲染帧走而不是 tick。单帧时长超过一个间隔时补齐多颗，掉帧或卡顿时尾迹不断档。
 */
internal class TimeAdvance(private val intervalMs: Double, private val maxPerFrame: Int = 64) {

    init {
        require(intervalMs > 0.0) { "intervalMs 必须为正" }
    }

    private var carry = 0.0

    fun carry(): Double = carry

    /** 推进 [deltaMs] 毫秒，返回本帧应发射的颗数；上限 [maxPerFrame]，超出部分舍弃而不记账堆积。 */
    fun advance(deltaMs: Double): Int {
        if (deltaMs <= 0.0) return 0
        carry += deltaMs
        var count = (carry / intervalMs).toInt()
        if (count <= 0) return 0
        carry -= count * intervalMs
        if (count > maxPerFrame) count = maxPerFrame
        return count
    }
}
