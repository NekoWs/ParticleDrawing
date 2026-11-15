package work.nekow.particledrawing.animation.morph

import work.nekow.particledrawing.math.Vec3
import java.util.UUID
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.translation

/**
 * 目标槽位：位置，可选朝向、尺寸与路径控制点。
 *
 * 朝向与尺寸留 `Double.NaN` 表示「不指定」，此时粒子保持原有属性；控制点留空表示按
 * [MorphPath] 的默认直线或单点曲线走。每个槽位都能带自己的控制点，因此一颗粒子可以有
 * 与别人不同的绕行方向。
 */
data class MorphSlot(
    val position: Vec3,
    val yaw: Double = Double.NaN,
    val pitch: Double = Double.NaN,
    val size: Double = Double.NaN,
    val control1: Vec3? = null,
    val control2: Vec3? = null,
)

/** 一次形变的输入粒子：身份、当前位置与当前速度。 */
data class MorphParticle @JvmOverloads constructor(
    val id: UUID,
    val position: Vec3,
    val velocity: Vec3 = Vec3.ZERO,
    /** 调用方给的稳定序号，用于按序号分配与噪声；缺省用列表下标。 */
    val order: Int = -1,
    val yaw: Double = Double.NaN,
    val pitch: Double = Double.NaN,
    val size: Double = Double.NaN,
)

/** 路径形态。 */
enum class MorphPath { STRAIGHT, QUADRATIC, CUBIC }

/** 过渡中段的扰动形态，都会随进度衰减到 0，不影响最终收敛。 */
enum class MorphWobble { NONE, BELL, NOISE, VORTEX }

/** 粒子到槽位的分配策略。 */
enum class MorphAllocation { NEAREST, INDEX, ANGLE }

/** 缓动：入参和出参都在 0..1。 */
fun interface MorphEasing {
    fun ease(t: Double): Double

    companion object {
        val LINEAR = MorphEasing { it }
        val IN_QUAD = MorphEasing { it * it }
        val OUT_QUAD = MorphEasing { 1.0 - (1.0 - it) * (1.0 - it) }
        val IN_OUT_QUAD = MorphEasing { if (it < 0.5) 2 * it * it else 1 - 2 * (1 - it) * (1 - it) }
        val OUT_CUBIC = MorphEasing { val u = 1.0 - it; 1.0 - u * u * u }
        val IN_OUT_CUBIC = MorphEasing {
            if (it < 0.5) 4 * it * it * it else 1 - 4 * (1 - it) * (1 - it) * (1 - it)
        }
        val OUT_BACK = MorphEasing {
            val c1 = 1.70158; val c3 = c1 + 1.0; val u = it - 1.0
            1.0 + c3 * u * u * u + c1 * u * u
        }

        /** 按名字取预设，未知名字返回 null。 */
        fun byName(name: String): MorphEasing? = when (name.lowercase()) {
            "linear", "none" -> LINEAR
            "in_quad" -> IN_QUAD
            "out_quad" -> OUT_QUAD
            "in_out_quad" -> IN_OUT_QUAD
            "out_cubic" -> OUT_CUBIC
            "in_out_cubic" -> IN_OUT_CUBIC
            "out_back", "back" -> OUT_BACK
            else -> null
        }
    }
}

/**
 * 一次形变的全部可调项。
 *
 * [maxSpeed] / [maxAccel] 为 0 表示不限速；给定时若按 [ticks] 算出的速度或加速度超限，
 * 该粒子的时长会被自动拉长（[MorphRuntime.effectiveTicks] 可查询），保证单帧位移不超限。
 */
data class MorphOptions(
    val ticks: Int = 40,
    val staggerTicks: Int = 0,
    val maxSpeed: Double = 0.0,
    val maxAccel: Double = 0.0,
    val path: MorphPath = MorphPath.STRAIGHT,
    val easing: MorphEasing = MorphEasing.LINEAR,
    val wobble: MorphWobble = MorphWobble.NONE,
    val wobbleStrength: Double = 0.0,
    val wobblePeaks: Double = 1.0,
    val convergeEps: Double = 0.05,
    /** 0 = 不循环，正数 = 往返次数，-1 = 无限往返。 */
    val loop: Int = 0,
    val allocation: MorphAllocation = MorphAllocation.NEAREST,
    /** 到达后保留速度继续飞；默认停住。 */
    val keepVelocity: Boolean = false,
    /** 显式开启后，形变才会同时写朝向与尺寸（否则只动位置）。 */
    val writeAttributes: Boolean = false,
) {
    init {
        requireLocalized(ticks >= 0) { translation("error.ticks") }
        requireLocalized(staggerTicks >= 0) { translation("error.stagger") }
        requireLocalized(loop >= -1) { translation("error.morph.loop") }
        requireLocalized(convergeEps > 0.0) { translation("error.morph.convergence") }
        requireLocalized(maxSpeed >= 0.0 && maxAccel >= 0.0) { translation("error.morph.speed_limit") }
        requireLocalized(wobbleStrength >= 0.0) { translation("error.morph.wobble_strength") }
    }
}

/** 分配结果：粒子 id → 槽位下标。 */
data class MorphAllocationResult(val slotOfParticle: Map<UUID, Int>, val unmatchedSlots: List<Int>)

/** 分配策略实现：只依赖位置与序号，保证同输入同输出。 */
object MorphAllocator {

    fun allocate(particles: List<MorphParticle>, slots: List<MorphSlot>, mode: MorphAllocation): MorphAllocationResult {
        if (particles.isEmpty() || slots.isEmpty()) {
            return MorphAllocationResult(emptyMap(), slots.indices.toList())
        }
        val map = LinkedHashMap<UUID, Int>(particles.size)
        val used = BooleanArray(slots.size)
        when (mode) {
            MorphAllocation.INDEX -> {
                for ((i, p) in particles.withIndex()) {
                    if (i < slots.size) {
                        map[p.id] = i
                        used[i] = true
                    }
                }
            }
            MorphAllocation.ANGLE -> {
                // 两组各自按绕质心的极角排序，再按序配对：星形分布下天然不交叉。
                val pc = centroid(particles.map { it.position })
                val sc = centroid(slots.map { it.position })
                val pOrder = particles.sortedWith(compareBy({ angleOf(it.position, pc) }, { it.order }))
                val sOrder = slots.indices.sortedWith(compareBy({ angleOf(slots[it].position, sc) }, { it }))
                for ((i, p) in pOrder.withIndex()) {
                    if (i < sOrder.size) {
                        val s = sOrder[i]
                        map[p.id] = s
                        used[s] = true
                    }
                }
            }
            MorphAllocation.NEAREST -> {
                nearestWithUncrossing(particles, slots, map, used)
            }
        }
        val unmatched = ArrayList<Int>()
        for (i in slots.indices) if (!used[i]) unmatched += i
        return MorphAllocationResult(map, unmatched)
    }

    /**
     * 最近邻贪心，再做若干轮 2-opt 解交叉：交换两条边后总长变短就交换，
     * 让“就近”与“不交叉”同时成立。轮数有上限，保证 N 大时不退化。
     */
    private fun nearestWithUncrossing(
        particles: List<MorphParticle>,
        slots: List<MorphSlot>,
        map: MutableMap<UUID, Int>,
        used: BooleanArray,
    ) {
        val ordered = particles.sortedBy { it.order }
        val free = ArrayDeque(slots.indices.toList())
        for (p in ordered) {
            if (free.isEmpty()) break
            var bestPos = 0
            var bestDist = Double.MAX_VALUE
            for ((i, s) in free.withIndex()) {
                val d = p.position.distanceToSqr(slots[s].position)
                if (d < bestDist) {
                    bestDist = d
                    bestPos = i
                }
            }
            val slot = free.removeAt(bestPos)
            map[p.id] = slot
            used[slot] = true
        }
        uncross(particles, slots, map, MAX_UNCROSS_PASSES)
    }

    private const val MAX_UNCROSS_PASSES = 8

    private fun uncross(particles: List<MorphParticle>, slots: List<MorphSlot>, map: MutableMap<UUID, Int>, passes: Int) {
        if (map.size < 4) return
        val ids = map.keys.toList()
        repeat(passes) {
            var improved = false
            for (i in ids.indices) {
                val pa = particles.firstOrNull { it.id == ids[i] } ?: continue
                val sa = slots[map[ids[i]]!!].position
                for (j in i + 1 until ids.size) {
                    val pb = particles.firstOrNull { it.id == ids[j] } ?: continue
                    val sb = slots[map[ids[j]]!!].position
                    val current = pa.position.distanceToSqr(sa) + pb.position.distanceToSqr(sb)
                    val swapped = pa.position.distanceToSqr(sb) + pb.position.distanceToSqr(sa)
                    if (swapped + 1e-12 < current && crosses(pa.position, sa, pb.position, sb)) {
                        val ia = map[ids[i]]!!
                        val ib = map[ids[j]]!!
                        map[ids[i]] = ib
                        map[ids[j]] = ia
                        improved = true
                    }
                }
            }
            if (!improved) return
        }
    }

    /** 二维（XZ 平面）线段相交判定；端点相接不算交叉。 */
    private fun crosses(a0: Vec3, a1: Vec3, b0: Vec3, b1: Vec3): Boolean {
        fun orient(p: Vec3, q: Vec3, r: Vec3): Double =
            (q.x - p.x) * (r.z - p.z) - (q.z - p.z) * (r.x - p.x)
        val o1 = orient(a0, a1, b0)
        val o2 = orient(a0, a1, b1)
        val o3 = orient(b0, b1, a0)
        val o4 = orient(b0, b1, a1)
        return o1 * o2 < 0 && o3 * o4 < 0
    }

    private fun centroid(points: List<Vec3>): Vec3 {
        var x = 0.0; var y = 0.0; var z = 0.0
        for (p in points) { x += p.x; y += p.y; z += p.z }
        val n = points.size.toDouble()
        return Vec3(x / n, y / n, z / n)
    }

    private fun angleOf(p: Vec3, center: Vec3): Double = kotlin.math.atan2(p.z - center.z, p.x - center.x)
}

/** 确定性哈希噪声：同一 id 与同一格点永远得到同一个值，落在 -1..1。 */
internal object MorphNoise {
    fun at(id: UUID, cell: Long, salt: Int): Double {
        var h = id.mostSignificantBits * 0x9E3779B97F4A7C15uL.toLong()
        h = h xor (id.leastSignificantBits + cell * 0xBF58476D1CE4E5B9uL.toLong() + salt * 0x94D049BB133111EBuL.toLong())
        h = h xor (h ushr 33)
        h *= -0xae502812aa7333L
        h = h xor (h ushr 29)
        h *= -0x3b314601e57a13adL
        h = h xor (h ushr 32)
        return ((h ushr 11).toDouble() / (1L shl 52).toDouble()) * 2.0 - 1.0
    }
}
