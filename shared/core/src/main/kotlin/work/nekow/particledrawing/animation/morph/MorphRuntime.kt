package work.nekow.particledrawing.animation.morph

import work.nekow.particledrawing.math.Vec3
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 一次编队变换的运行时：分配、逐粒子过渡、限速、扰动、循环与查询都集中在这里。
 *
 * 平台层（客户端程序求值）每 tick 调 [tick]，再把 [positionOf] 等结果写到粒子上。
 * 这里不碰任何 Minecraft 类型，护栏 G1–G10 里的数值部分都能在纯 JVM 测试里断言。
 */
class MorphRuntime(
    particles: List<MorphParticle>,
    slots: List<MorphSlot>,
    options: MorphOptions,
    /** 动态目标：每 tick 用当前形变时间（毫秒）重算槽位；为 null 时不重算。 */
    private val slotProvider: ((Double) -> List<MorphSlot>)? = null,
) {

    private class Track(
        val id: UUID,
        val order: Int,
        var startX: Double, var startY: Double, var startZ: Double,
        var startVX: Double, var startVY: Double, var startVZ: Double,
        var slotIndex: Int,
        var delay: Double,
        var duration: Double,
        var c1: Vec3?,
        var c2: Vec3?,
        var startYaw: Double,
        var startPitch: Double,
        var startSize: Double,
    ) {
        var elapsed = 0.0
        var arrived = false
        var posX = startX; var posY = startY; var posZ = startZ
        var velX = startVX; var velY = startVY; var velZ = startVZ
        var yaw = startYaw; var pitch = startPitch; var size = startSize
    }

    private val tracks = ArrayList<Track>(particles.size)
    private val byId = HashMap<UUID, Track>(particles.size * 2)
    private var slots: List<MorphSlot> = slots
    private var options: MorphOptions = options

    /** 已经推进的形变时间（刻，含错峰与暂停的影响）。 */
    var ticksElapsed: Double = 0.0
        private set

    /** 时间倍率：0 等价于暂停。 */
    var timeScale: Double = 1.0
        private set

    var paused: Boolean = false
        private set

    var cancelled: Boolean = false
        private set

    /** 已完成过一次过渡（不含循环往返）。 */
    var completed: Boolean = false
        private set

    private var loopsLeft = options.loop

    /** 空着没人占据的槽位下标（G3）。 */
    val unmatchedSlots: List<Int>
        get() = _unmatched

    private var _unmatched: List<Int> = emptyList()

    /** 没有槽位可去、正在待命的粒子（G3）。 */
    val waiting: List<UUID>
        get() = _waiting

    private var _waiting: List<UUID> = emptyList()

    init {
        require(particles.isNotEmpty()) { "形变至少需要一颗粒子" }
        require(slots.isNotEmpty()) { "形变至少需要一个槽位" }
        start(particles, slots)
    }

    private fun start(particles: List<MorphParticle>, slots: List<MorphSlot>) {
        tracks.clear()
        byId.clear()
        val allocation = MorphAllocator.allocate(particles, slots, options.allocation)
        _unmatched = allocation.unmatchedSlots
        _waiting = particles.filter { it.id !in allocation.slotOfParticle }.map { it.id }

        // 错峰按到目标的距离归一化排序，远者先动。
        val distances = HashMap<UUID, Double>(particles.size * 2)
        for (p in particles) {
            val slot = allocation.slotOfParticle[p.id]?.let { slots[it] }
            distances[p.id] = if (slot == null) 0.0 else p.position.distanceTo(slot.position)
        }
        val ranked = particles.sortedByDescending { distances[it.id] ?: 0.0 }
        val rankOf = HashMap<UUID, Int>(ranked.size * 2)
        for ((i, p) in ranked.withIndex()) rankOf[p.id] = i

        val lastRank = max(1, ranked.size - 1)
        for ((i, p) in particles.withIndex()) {
            val slotIndex = allocation.slotOfParticle[p.id] ?: -1
            val slot = if (slotIndex >= 0) slots[slotIndex] else null
            val order = if (p.order >= 0) p.order else i
            val delay = if (slot == null || options.staggerTicks == 0) 0.0
            else options.staggerTicks.toDouble() * (rankOf[p.id]!!.toDouble() / lastRank)
            val duration = if (slot == null) 0.0 else durationTicksFor(p.position, slot, order)
            val track = Track(
                p.id, order,
                p.position.x, p.position.y, p.position.z,
                p.velocity.x, p.velocity.y, p.velocity.z,
                slotIndex, delay, duration,
                slot?.control1, slot?.control2,
                NO_ANGLE, NO_ANGLE, NO_SIZE,
            )
            tracks += track
            byId[p.id] = track
        }
        ticksElapsed = 0.0
        completed = false
        cancelled = false
        loopsLeft = options.loop
    }

    // ── 时长与限速 ────────────────────────────────────────────────────────────

    /**
     * 这颗粒子的实际时长（刻）：按 [MorphOptions.ticks] 与限速要求取较大者。
     * 限速只在给定时生效——超速就延长时长，保证 G1。
     */
    private fun durationTicksFor(start: Vec3, slot: MorphSlot, order: Int): Double {
        val distance = start.distanceTo(slot.position)
        var ticks = options.ticks.toDouble()
        if (options.maxSpeed > 0.0 && distance > 0.0) {
            ticks = max(ticks, distance / options.maxSpeed)
        }
        if (options.maxAccel > 0.0 && distance > 0.0) {
            // 三角速度剖面：先加速到中点再减速，最短耗时 ≈ 2*sqrt(d/a)。
            ticks = max(ticks, 2.0 * sqrt(distance / options.maxAccel))
        }
        return max(ticks, MIN_DURATION)
    }

    /** 某颗粒子的实际过渡时长（刻），查询用。 */
    fun effectiveTicksOf(id: UUID): Double = byId[id]?.duration ?: 0.0

    // ── 推进 ──────────────────────────────────────────────────────────────────

    /** 推进 [dtTicks] 刻（受暂停与倍率影响）。返回是否本刻有粒子位置变化。 */
    fun tick(dtTicks: Double = 1.0): Boolean {
        if (cancelled) return false
        if (paused || timeScale <= 0.0) return false
        val dt = dtTicks * timeScale
        ticksElapsed += dt

        if (slotProvider != null) {
            val next = slotProvider(ticksElapsed * 50.0)
            if (next.isNotEmpty()) {
                slots = next
                // 槽位数量变了就重新对齐：多出来的粒子进入待命，不再参与推进。
                reallocateIfShapeChanged()
            }
        }

        var moved = false
        for (t in tracks) {
            moved = advance(t, dt) || moved
        }
        if (!completed && tracks.all { it.arrived || it.slotIndex < 0 }) {
            completed = true
            if (loopsLeft != 0) {
                pingPong()
                completed = false
            }
        }
        return moved
    }

    private fun reallocateIfShapeChanged() {
        val maxSlot = tracks.maxOfOrNull { it.slotIndex } ?: -1
        if (maxSlot < slots.size) return
        val live = tracks.filter { it.slotIndex in slots.indices }
        if (live.isEmpty()) {
            _waiting = tracks.map { it.id }
            return
        }
        val particles = live.map { MorphParticle(it.id, Vec3(it.posX, it.posY, it.posZ), Vec3(it.velX, it.velY, it.velZ), it.order) }
        val allocation = MorphAllocator.allocate(particles, slots, options.allocation)
        for (t in tracks) {
            val next = allocation.slotOfParticle[t.id]
            if (next == null) {
                t.slotIndex = -1
                t.arrived = true
            } else if (next != t.slotIndex) {
                t.slotIndex = next
                t.startX = t.posX; t.startY = t.posY; t.startZ = t.posZ
                t.startVX = t.velX; t.startVY = t.velY; t.startVZ = t.velZ
                t.elapsed = 0.0
                t.arrived = false
                t.duration = durationTicksFor(Vec3(t.posX, t.posY, t.posZ), slots[next], t.order)
            }
        }
    }

    private fun advance(t: Track, dt: Double): Boolean {
        if (t.slotIndex < 0) return false
        val slot = slots.getOrNull(t.slotIndex) ?: return false
        val target = targetOf(t)
        // 到达后保留速度：不再沿路径求值，直接按当前速度继续飞。
        if (t.arrived && options.keepVelocity) {
            t.posX += t.velX * dt
            t.posY += t.velY * dt
            t.posZ += t.velZ * dt
            return true
        }
        t.elapsed += dt
        val prevX = t.posX; val prevY = t.posY; val prevZ = t.posZ

        val local = t.elapsed - t.delay
        val raw = if (t.duration <= 0.0) 1.0 else (local / t.duration).coerceIn(0.0, 1.0)
        if (local <= 0.0) {
            // 还在错峰等待：停在起点，速度归零。
            t.velX = 0.0; t.velY = 0.0; t.velZ = 0.0
            t.arrived = false
            return false
        }
        val eased = options.easing.ease(raw)
        var x = t.startX
        var y = t.startY
        var z = t.startZ
        when (options.path) {
            MorphPath.STRAIGHT -> {
                x = lerp(t.startX, target.x, eased)
                y = lerp(t.startY, target.y, eased)
                z = lerp(t.startZ, target.z, eased)
            }
            MorphPath.QUADRATIC -> {
                val c = t.c1 ?: midpoint(t.startX, t.startY, t.startZ, target.x, target.y, target.z)
                x = quad(t.startX, c.x, target.x, eased)
                y = quad(t.startY, c.y, target.y, eased)
                z = quad(t.startZ, c.z, target.z, eased)
            }
            MorphPath.CUBIC -> {
                val c1 = t.c1 ?: midpoint(t.startX, t.startY, t.startZ, target.x, target.y, target.z)
                val c2 = t.c2 ?: c1
                x = cubic(t.startX, c1.x, c2.x, target.x, eased)
                y = cubic(t.startY, c1.y, c2.y, target.y, eased)
                z = cubic(t.startZ, c1.z, c2.z, target.z, eased)
            }
        }

        if (options.wobble != MorphWobble.NONE && options.wobbleStrength > 0.0) {
            val off = wobbleOffset(t, target, raw, x, y, z)
            x += off.x; y += off.y; z += off.z
        }

        // 限速：过冲类缓动也可能造成单帧位移超标，这里按上限裁一次，保证 G1 与缓动无关。
        if (options.maxSpeed > 0.0 && dt > 0.0) {
            val ddx = x - prevX
            val ddy = y - prevY
            val ddz = z - prevZ
            val step = sqrt(ddx * ddx + ddy * ddy + ddz * ddz)
            val limit = options.maxSpeed * dt
            if (step > limit && step > 1e-12) {
                val k = limit / step
                x = prevX + ddx * k
                y = prevY + ddy * k
                z = prevZ + ddz * k
            }
        }

        t.posX = x; t.posY = y; t.posZ = z

        // 速度用实际位移算，保证与 G1 的“单帧位移”口径一致。
        val inv = if (dt > 0.0) 1.0 / dt else 0.0
        t.velX = (x - prevX) * inv
        t.velY = (y - prevY) * inv
        t.velZ = (z - prevZ) * inv

        if (options.writeAttributes && raw >= 1.0) {
            if (!slot.yaw.isNaN()) t.yaw = slot.yaw
            if (!slot.pitch.isNaN()) t.pitch = slot.pitch
            if (!slot.size.isNaN()) t.size = slot.size
        }

        if (raw >= 1.0) {
            val dist = Vec3(t.posX, t.posY, t.posZ).distanceTo(target)
            if (dist <= options.convergeEps) {
                t.arrived = true
                if (!options.keepVelocity) {
                    t.velX = 0.0; t.velY = 0.0; t.velZ = 0.0
                } else {
                    // 穿过槽位继续飞：速度取末段方向乘以限速或原速。
                    val dir = Vec3(slot.position.x - t.startX, slot.position.y - t.startY, slot.position.z - t.startZ)
                    val len = dir.length()
                    if (len > 1e-9) {
                        val speed = if (options.maxSpeed > 0.0) options.maxSpeed else len / max(t.duration, MIN_DURATION)
                        t.velX = dir.x / len * speed
                        t.velY = dir.y / len * speed
                        t.velZ = dir.z / len * speed
                    }
                }
            }
        }
        return true
    }

    private fun pingPong() {
        if (loopsLeft > 0) loopsLeft--
        val nextStarts = ArrayList<Vec3>(tracks.size)
        for (t in tracks) {
            if (t.slotIndex < 0) continue
            val slot = slots.getOrNull(t.slotIndex) ?: continue
            nextStarts += Vec3(t.startX, t.startY, t.startZ)
            // 目标换成“上一次的起点”，起点换成当前位置。
            t.startX = t.posX; t.startY = t.posY; t.startZ = t.posZ
            t.startVX = t.velX; t.startVY = t.velY; t.startVZ = t.velZ
            t.elapsed = 0.0
            t.arrived = false
            t.duration = durationTicksFor(Vec3(t.startX, t.startY, t.startZ), slot.copy(position = nextStarts.last()), t.order)
            // 直接把目标换成旧起点：借用一个临时槽位对象。
            slotOverrides[t.id] = nextStarts.last()
        }
        ticksElapsed = 0.0
        completed = false
        if (loopsLeft == 0 && options.loop > 0) loopsLeft = 0
    }

    /** 往返时被临时替换的目标位置；正常过渡里为空。 */
    private val slotOverrides = HashMap<UUID, Vec3>()

    private fun targetOf(t: Track): Vec3 =
        slotOverrides[t.id] ?: slots.getOrNull(t.slotIndex)?.position ?: Vec3(t.posX, t.posY, t.posZ)

    // ── 扰动 ──────────────────────────────────────────────────────────────────

    private fun wobbleOffset(t: Track, target: Vec3, raw: Double, x: Double, y: Double, z: Double): Vec3 {
        val bell = kotlin.math.sin(Math.PI * raw)
        if (bell <= 0.0) return Vec3.ZERO
        return when (options.wobble) {
            MorphWobble.NONE -> Vec3.ZERO
            MorphWobble.BELL -> {
                // 垂直于路径方向的横向偏移，随进度做钟形包络内的正弦摆动。
                val dirX = target.x - t.startX
                val dirY = target.y - t.startY
                val dirZ = target.z - t.startZ
                val axis = perpendicular(dirX, dirY, dirZ)
                val phase = kotlin.math.sin(2.0 * Math.PI * options.wobblePeaks * raw)
                val amp = options.wobbleStrength * bell * phase
                Vec3(axis.x * amp, axis.y * amp, axis.z * amp)
            }
            MorphWobble.NOISE -> {
                val cell = floor(raw * max(1.0, options.wobblePeaks) * 4.0).toLong()
                val amp = options.wobbleStrength * bell
                Vec3(
                    MorphNoise.at(t.id, cell, 1) * amp,
                    MorphNoise.at(t.id, cell, 2) * amp,
                    MorphNoise.at(t.id, cell, 3) * amp,
                )
            }
            MorphWobble.VORTEX -> {
                // 绕中心轴旋转着靠近：角度随进度收束到 0。
                val cx = (t.startX + target.x) * 0.5
                val cz = (t.startZ + target.z) * 0.5
                val angle = options.wobbleStrength * bell * options.wobblePeaks
                val dx = x - cx
                val dz = z - cz
                val cs = kotlin.math.cos(angle)
                val sn = kotlin.math.sin(angle)
                Vec3(cx + dx * cs - dz * sn - x, 0.0, cz + dx * sn + dz * cs - z)
            }
        }
    }

    private fun perpendicular(dx: Double, dy: Double, dz: Double): Vec3 {
        // 取一个与路径方向正交的稳定方向；方向退化为竖直时换一个基准，避免除零。
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 1e-9) return Vec3(1.0, 0.0, 0.0)
        val nx = dx / len; val ny = dy / len; val nz = dz / len
        var px = -nz; var py = 0.0; var pz = nx
        if (abs(px) + abs(pz) < 1e-6) { px = 1.0; py = 0.0; pz = 0.0 }
        val plen = sqrt(px * px + py * py + pz * pz)
        return Vec3(px / plen, py / plen, pz / plen)
    }

    // ── 控制 ──────────────────────────────────────────────────────────────────

    fun pause() { paused = true }

    fun resume() { paused = false }

    /** 时间倍率；0 暂停，1 正常，可大于 1 加速。 */
    fun speed(scale: Double) {
        require(scale >= 0.0) { "倍率不能为负" }
        timeScale = scale
    }

    /** 取消：停在当前位置并清空分配（G4/G10 的中断语义）。 */
    fun cancel() {
        cancelled = true
        for (t in tracks) {
            t.slotIndex = -1
            t.arrived = false
            t.velX = 0.0; t.velY = 0.0; t.velZ = 0.0
        }
    }

    /** 只改时长/缓动等，不重新分配（进度按当前比例保留）。 */
    fun retune(next: MorphOptions) {
        val ratio = if (options.ticks > 0) next.ticks.toDouble() / options.ticks.toDouble() else 1.0
        options = next
        for (t in tracks) {
            t.duration *= ratio
            t.delay *= ratio
            t.elapsed *= ratio
        }
        cancelled = false
        paused = false
        completed = false
    }

    // ── 查询（G5） ────────────────────────────────────────────────────────────

    fun positionOf(id: UUID): Vec3? = byId[id]?.let { Vec3(it.posX, it.posY, it.posZ) }

    fun velocityOf(id: UUID): Vec3? = byId[id]?.let { Vec3(it.velX, it.velY, it.velZ) }

    fun yawOf(id: UUID): Double? = byId[id]?.yaw?.takeIf { !it.isNaN() }

    fun pitchOf(id: UUID): Double? = byId[id]?.pitch?.takeIf { !it.isNaN() }

    fun sizeOf(id: UUID): Double? = byId[id]?.size?.takeIf { !it.isNaN() }

    /** 过渡进度：0 未开始，1 已到时长（不代表已收敛）。 */
    fun progressOf(id: UUID): Double {
        val t = byId[id] ?: return 0.0
        if (t.duration <= 0.0) return if (t.elapsed >= t.delay) 1.0 else 0.0
        return ((t.elapsed - t.delay) / t.duration).coerceIn(0.0, 1.0)
    }

    fun slotOf(id: UUID): Int? = byId[id]?.slotIndex?.takeIf { it >= 0 }

    fun slotPositionOf(id: UUID): Vec3? = byId[id]?.let { targetOf(it) }

    fun arrivedOf(id: UUID): Boolean = byId[id]?.arrived ?: false

    fun isDone(): Boolean = cancelled || (completed && tracks.all { it.arrived || it.slotIndex < 0 })

    fun ids(): List<UUID> = tracks.map { it.id }

    /** 当前最大“粒子到目标槽位”的距离，G2 断言用。 */
    fun maxDistanceToSlot(): Double {
        var max = 0.0
        for (t in tracks) {
            if (t.slotIndex < 0) continue
            val target = targetOf(t)
            val d = Vec3(t.posX, t.posY, t.posZ).distanceTo(target)
            if (d > max) max = d
        }
        return max
    }

    // ── 数学 ──────────────────────────────────────────────────────────────────

    private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t

    private fun quad(a: Double, c: Double, b: Double, t: Double): Double {
        val u = 1 - t
        return u * u * a + 2 * u * t * c + t * t * b
    }

    private fun cubic(a: Double, c1: Double, c2: Double, b: Double, t: Double): Double {
        val u = 1 - t
        return u * u * u * a + 3 * u * u * t * c1 + 3 * u * t * t * c2 + t * t * t * b
    }

    private fun midpoint(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double) =
        Vec3((x0 + x1) * 0.5, (y0 + y1) * 0.5, (z0 + z1) * 0.5)

    private companion object {
        const val MIN_DURATION = 1e-6
        const val NO_ANGLE = Double.NaN
        const val NO_SIZE = Double.NaN
    }
}
