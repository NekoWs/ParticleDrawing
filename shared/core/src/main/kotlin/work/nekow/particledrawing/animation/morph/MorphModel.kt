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

/** 槽位坐标所在的坐标系。 */
enum class MorphSpace {
    /** 世界坐标：槽位就是最终的绝对位置。 */
    WORLD,
    /** 相对组锚点：槽位是相对锚点的偏移，锚点移动时目标跟着平移。 */
    LOCAL,
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
    /** 槽位与路径控制点按哪个坐标系解释。 */
    val space: MorphSpace = MorphSpace.WORLD,
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

/**
 * 分配策略实现：只依赖位置与序号，保证同输入同输出。
 *
 * 对外仍以「粒子 id → 槽位下标」的 map 呈现，内部走下标数组，避免大组时反复分配
 * UUID 映射。NEAREST 在小组上做精确贪心 + 2-opt 解交叉；大组（例如上万粒子）改用
 * 空间网格找最近邻、局部交换改进，把原来 O(n²)（再乘 8 轮解交叉）的开销压到近线性。
 */
object MorphAllocator {

    /** 下标版分配结果：[slotOfParticle] 按输入粒子顺序给出槽位下标，-1 表示没分到。 */
    class IndexedResult(val slotOfParticle: IntArray, val unmatchedSlots: IntArray)

    fun allocate(particles: List<MorphParticle>, slots: List<MorphSlot>, mode: MorphAllocation): MorphAllocationResult {
        val indexed = allocateIndexed(particles, slots, mode)
        val matched = indexed.slotOfParticle.count { it >= 0 }
        val map = LinkedHashMap<UUID, Int>(matched * 2)
        for (i in indexed.slotOfParticle.indices) {
            val slot = indexed.slotOfParticle[i]
            if (slot >= 0) map[particles[i].id] = slot
        }
        return MorphAllocationResult(map, indexed.unmatchedSlots.toList())
    }

    fun allocateIndexed(
        particles: List<MorphParticle>,
        slots: List<MorphSlot>,
        mode: MorphAllocation,
    ): IndexedResult {
        val slotOf = IntArray(particles.size) { -1 }
        val used = BooleanArray(slots.size)
        if (particles.isNotEmpty() && slots.isNotEmpty()) {
            when (mode) {
                MorphAllocation.INDEX -> {
                    val count = minOf(particles.size, slots.size)
                    for (i in 0 until count) {
                        slotOf[i] = i
                        used[i] = true
                    }
                }
                MorphAllocation.ANGLE -> allocateAngle(particles, slots, slotOf, used)
                MorphAllocation.NEAREST -> allocateNearest(particles, slots, slotOf, used)
            }
        }
        var unmatched = 0
        for (u in used) if (!u) unmatched++
        val unmatchedSlots = IntArray(unmatched)
        var at = 0
        for (i in used.indices) if (!used[i]) unmatchedSlots[at++] = i
        return IndexedResult(slotOf, unmatchedSlots)
    }

    private fun allocateAngle(
        particles: List<MorphParticle>,
        slots: List<MorphSlot>,
        slotOf: IntArray,
        used: BooleanArray,
    ) {
        // 两组各自按绕质心的极角排序，再按序配对：星形分布下天然不交叉。
        val pc = centroid(particles.map { it.position })
        val sc = centroid(slots.map { it.position })
        val particleOrder = (particles.indices).sortedWith(
            compareBy({ angleOf(particles[it].position, pc) }, { particles[it].order }, { it }),
        )
        val slotOrder = (slots.indices).sortedWith(compareBy({ angleOf(slots[it].position, sc) }, { it }))
        for ((rank, particleIndex) in particleOrder.withIndex()) {
            if (rank >= slotOrder.size) break
            val slot = slotOrder[rank]
            slotOf[particleIndex] = slot
            used[slot] = true
        }
    }

    /** 小组精确处理的上限：粒子数 × 槽位数。超过就切换到空间网格。 */
    private const val EXACT_NEAREST_LIMIT = 1 shl 16 // 256 × 256

    private fun allocateNearest(
        particles: List<MorphParticle>,
        slots: List<MorphSlot>,
        slotOf: IntArray,
        used: BooleanArray,
    ) {
        val order = (particles.indices).sortedWith(compareBy({ particles[it].order }, { it }))
        if (particles.size.toLong() * slots.size <= EXACT_NEAREST_LIMIT) {
            exactGreedy(particles, slots, order, slotOf, used)
            uncrossExact(particles, slots, order, slotOf)
        } else {
            gridGreedy(particles, slots, order, slotOf, used)
            uncrossWindowed(particles, slots, order, slotOf)
        }
    }

    private fun exactGreedy(
        particles: List<MorphParticle>,
        slots: List<MorphSlot>,
        order: List<Int>,
        slotOf: IntArray,
        used: BooleanArray,
    ) {
        val free = IntArray(slots.size) { it }
        var freeCount = free.size
        for (particleIndex in order) {
            if (freeCount == 0) break
            val position = particles[particleIndex].position
            var bestPos = 0
            var bestDist = Double.MAX_VALUE
            for (i in 0 until freeCount) {
                val d = position.distanceToSqr(slots[free[i]].position)
                if (d < bestDist) {
                    bestDist = d
                    bestPos = i
                }
            }
            val slot = free[bestPos]
            free[bestPos] = free[--freeCount]
            slotOf[particleIndex] = slot
            used[slot] = true
        }
    }

    /**
     * 大组：把槽位装进均匀网格，按粒子顺序在邻域里找最近的空闲槽。
     * 环形搜索一旦确认更远的格子不可能更近就停；极端聚簇时退回全量扫描，结果仍确定。
     */
    private fun gridGreedy(
        particles: List<MorphParticle>,
        slots: List<MorphSlot>,
        order: List<Int>,
        slotOf: IntArray,
        used: BooleanArray,
    ) {
        var minX = Double.MAX_VALUE; var minZ = Double.MAX_VALUE; var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE; var maxZ = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for (slot in slots) {
            val p = slot.position
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.z < minZ) minZ = p.z
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
            if (p.z > maxZ) maxZ = p.z
        }
        var cells = Math.cbrt(slots.size.toDouble()).toInt().coerceIn(1, 128)
        while (cells > 1 && cells.toLong() * cells * cells > slots.size.toLong() * 8) cells--
        val size = maxOf(maxX - minX, maxOf(maxY - minY, maxZ - minZ), 1e-6) / cells
        val cellCount = cells * cells * cells
        val slotCell = IntArray(slots.size)
        val counts = IntArray(cellCount + 1)
        for (s in slots.indices) {
            val cell = cellOf(slots[s].position, minX, minY, minZ, size, cells)
            slotCell[s] = cell
            counts[cell + 1]++
        }
        for (c in 1..cellCount) counts[c] += counts[c - 1]
        val startAt = counts.copyOf()
        val writeAt = counts.copyOf()
        val sortedSlots = IntArray(slots.size)
        for (s in slots.indices) sortedSlots[writeAt[slotCell[s]]++] = s

        fun scanCell(cell: Int, nearest: DoubleArray) {
            val end = startAt[cell + 1]
            for (i in startAt[cell] until end) {
                val slot = sortedSlots[i]
                if (used[slot]) continue
                val p = slots[slot].position
                val dx = p.x - nearest[1]; val dy = p.y - nearest[2]; val dz = p.z - nearest[3]
                val d = dx * dx + dy * dy + dz * dz
                if (d < nearest[0]) {
                    nearest[0] = d
                    nearest[4] = slot.toDouble()
                }
            }
        }

        val nearest = DoubleArray(5)
        for (particleIndex in order) {
            val p = particles[particleIndex].position
            val cx = coord(p.x, minX, size, cells)
            val cy = coord(p.y, minY, size, cells)
            val cz = coord(p.z, minZ, size, cells)
            nearest[0] = Double.MAX_VALUE
            nearest[1] = p.x
            nearest[2] = p.y
            nearest[3] = p.z
            nearest[4] = -1.0
            // 半径按格子增大；确认更远格子的最近距离也超过当前最优就停。
            var ring = 0
            var found = false
            val maxRing = cells
            while (ring <= maxRing) {
                scanShell(cx, cy, cz, ring, cells) { cell -> scanCell(cell, nearest) }
                // 未扫描的格子至少在 ring+1 圈外，最近距离也大于 ring × 格宽；
                // 当前最优不超过这个下界时就不必再扩大搜索。
                if (nearest[4] >= 0.0 && (ring == maxRing || nearest[0] <= sq(ring * size))) {
                    found = true
                    break
                }
                ring++
            }
            if (!found || nearest[4] < 0.0) {
                // 极端分布兜底：扫一遍全部空闲槽，保证能分到。
                for (slot in slots.indices) {
                    if (used[slot]) continue
                    val q = slots[slot].position
                    val dx = q.x - p.x; val dy = q.y - p.y; val dz = q.z - p.z
                    val d = dx * dx + dy * dy + dz * dz
                    if (d < nearest[0]) {
                        nearest[0] = d
                        nearest[4] = slot.toDouble()
                    }
                }
            }
            val slot = nearest[4].toInt()
            if (slot < 0) break
            slotOf[particleIndex] = slot
            used[slot] = true
        }
    }

    private inline fun scanShell(
        cx: Int, cy: Int, cz: Int, ring: Int, cells: Int, action: (Int) -> Unit,
    ) {
        val x0 = cx - ring; val x1 = cx + ring
        val y0 = cy - ring; val y1 = cy + ring
        val z0 = cz - ring; val z1 = cz + ring
        for (x in x0..x1) {
            if (x < 0 || x >= cells) continue
            for (y in y0..y1) {
                if (y < 0 || y >= cells) continue
                val xEdge = x == x0 || x == x1
                val yEdge = y == y0 || y == y1
                for (z in z0..z1) {
                    if (z < 0 || z >= cells) continue
                    // 只扫这一圈的壳，内部的格子上一圈已经看过。
                    if (!xEdge && !yEdge) {
                        if (z != z0 && z != z1) continue
                    }
                    action(x + y * cells + z * cells * cells)
                }
            }
        }
    }

    private fun cellOf(p: Vec3, minX: Double, minY: Double, minZ: Double, size: Double, cells: Int): Int =
        coord(p.x, minX, size, cells) + coord(p.y, minY, size, cells) * cells +
            coord(p.z, minZ, size, cells) * cells * cells

    private fun coord(value: Double, min: Double, size: Double, cells: Int): Int =
        ((value - min) / size).toInt().coerceIn(0, cells - 1)

    private fun sq(value: Double): Double = value * value

    private fun uncrossExact(
        particles: List<MorphParticle>,
        slots: List<MorphSlot>,
        order: List<Int>,
        slotOf: IntArray,
    ) {
        val ids = order.filter { slotOf[it] >= 0 }
        if (ids.size < 4) return
        repeat(MAX_UNCROSS_PASSES) {
            var improved = false
            for (i in ids.indices) {
                val pa = particles[ids[i]].position
                val sa = slots[slotOf[ids[i]]].position
                for (j in i + 1 until ids.size) {
                    val pb = particles[ids[j]].position
                    val sb = slots[slotOf[ids[j]]].position
                    val current = pa.distanceToSqr(sa) + pb.distanceToSqr(sb)
                    val swapped = pa.distanceToSqr(sb) + pb.distanceToSqr(sa)
                    if (swapped + 1e-12 < current && crosses(pa, sa, pb, sb)) {
                        val ia = slotOf[ids[i]]
                        slotOf[ids[i]] = slotOf[ids[j]]
                        slotOf[ids[j]] = ia
                        improved = true
                    }
                }
            }
            if (!improved) return
        }
    }

    /**
     * 大组只做局部改进：按起点的空间网格顺序排列，每个粒子与邻近窗口里的粒子比较，
     * 交换后总路程变短就换。工作量 O(n × 窗口)，不再有 O(n²) 的解交叉。
     */
    private fun uncrossWindowed(
        particles: List<MorphParticle>,
        slots: List<MorphSlot>,
        order: List<Int>,
        slotOf: IntArray,
    ) {
        val ids = order.filter { slotOf[it] >= 0 }
        if (ids.size < 4) return
        val spatial = ids.sortedWith(compareBy(
            { particles[it].position.x },
            { particles[it].position.z },
            { particles[it].position.y },
        ))
        val window = 16
        for (pass in 0 until 2) {
            var improved = false
            for (i in spatial.indices step 1) {
                val a = spatial[i]
                val pa = particles[a].position
                val sa = slots[slotOf[a]].position
                val end = minOf(spatial.size, i + 1 + window)
                for (j in i + 1 until end) {
                    val b = spatial[j]
                    val pb = particles[b].position
                    val sb = slots[slotOf[b]].position
                    val current = pa.distanceToSqr(sa) + pb.distanceToSqr(sb)
                    val swapped = pa.distanceToSqr(sb) + pb.distanceToSqr(sa)
                    if (swapped + 1e-12 < current) {
                        val ia = slotOf[a]
                        slotOf[a] = slotOf[b]
                        slotOf[b] = ia
                        improved = true
                    }
                }
            }
            if (!improved) return
        }
    }

    private const val MAX_UNCROSS_PASSES = 8

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
