package work.nekow.particledrawing.core.client

import net.minecraft.world.phys.Vec3

// 发射器的推进逻辑：纯数学，不碰渲染/网络，便于单测（与 TrackBuffer / ProcessClock 同一做法）。
// 里程口径要把一颗正好落在段中间的位置算出来，所以按「段内插值」而不是「段终点」发射。

/**
 * 按里程发射的推进器：喂进锚点每帧的插值位置，吐出这一帧该在哪些位置发射。
 *
 * 语义：锚点从 [from] 走到 [to]（一帧的位移），累计里程每满 [spacing] 格就发射一颗，
 * 位置严格落在段内的等距点上——所以「一跳一跳」不会出现，帧率高低也不改变粒子间距。
 */
internal class DistanceAdvance(private val spacing: Double) {

    init {
        require(spacing > 0.0) { "spacing 必须为正" }
    }

    private var carry = 0.0

    /** 未满一格攒下的里程（诊断/测试用）。 */
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
        // 余下的里程留到下一帧（恒 < spacing）
        carry = total - emitted * spacing
    }
}

/**
 * 按时间发射的推进器：喂进两帧之间的毫秒数，吐出这一帧该发射几颗。
 *
 * 时间粒度与渲染帧一致（不是 tick），所以 120fps 下也是均匀的；
 * 单帧时长超过一颗间隔时补齐多颗（掉帧/卡顿时不让尾迹断档）。
 */
internal class TimeAdvance(private val intervalMs: Double, private val maxPerFrame: Int = 64) {

    init {
        require(intervalMs > 0.0) { "intervalMs 必须为正" }
    }

    private var carry = 0.0

    fun carry(): Double = carry

    /** 推进 [deltaMs] 毫秒，返回本帧应发射的颗数（上限 [maxPerFrame]，超出部分舍弃而不是记账堆积）。 */
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
