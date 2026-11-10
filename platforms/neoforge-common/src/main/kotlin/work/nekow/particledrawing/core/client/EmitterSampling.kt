package work.nekow.particledrawing.core.client

import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// 发射器的采样工具：逐颗抖动与移动锚点的相邻样本，都是纯逻辑，便于单测。

/**
 * 逐颗抖动的确定性哈希：偏移只由发射器 id 的种子与第几颗决定。
 *
 * 不依赖调用顺序，同一声明在任何客户端上第 N 颗的偏移都相同，重连或重挂后也不变。
 */
internal object EmitterSampling {

    /** 发射器 id → 稳定的种子。 */
    fun seedOf(id: UUID): Long = id.mostSignificantBits xor id.leastSignificantBits

    /** 第 [index] 颗、第 [salt] 个随机数（[0,1)）。 */
    fun unit(seed: Long, index: Long, salt: Long): Double {
        val h = mix(seed xor mix(index * -0x61C8864680B583EBL + salt))
        return ((h ushr 11).toDouble() / (1L shl 53).toDouble()).coerceIn(0.0, 1.0)
    }

    /**
     * 第 [index] 颗粒子的位置偏移：沿运动方向前移 [along] 格，
     * 再在垂直于运动方向的圆盘里抖动，半径不超过 [jitter] 格。
     */
    fun offset(seed: Long, index: Long, direction: Vec3, jitter: Double, along: Double): Vec3 {
        val d = unitDirection(direction)
        var offset = d.scale(along)
        if (jitter > 0.0) {
            val (u, v) = perpendicularBasis(d)
            // sqrt 让点在圆盘内均匀，不做这一步会全挤在圆心附近
            val radius = jitter * sqrt(unit(seed, index, 1L))
            val angle = 2.0 * PI * unit(seed, index, 2L)
            offset = offset.add(u.scale(radius * cos(angle))).add(v.scale(radius * sin(angle)))
        }
        return offset
    }

    /** 单位方向；零向量时给 +Z，抖动平面保持稳定。 */
    fun unitDirection(direction: Vec3): Vec3 =
        if (direction.lengthSqr() < 1e-12) Vec3(0.0, 0.0, 1.0) else direction.normalize()

    /** 与 [direction] 垂直的一组正交基 (u, v)。 */
    fun perpendicularBasis(direction: Vec3): Pair<Vec3, Vec3> {
        val d = unitDirection(direction)
        val helper = if (abs(d.y) < 0.9) Vec3(0.0, 1.0, 0.0) else Vec3(1.0, 0.0, 0.0)
        val u = d.cross(helper).normalize()
        return u to d.cross(u)
    }

    /** splitmix64：把种子与序号摊开，避免相邻序号得到相似的随机数。 */
    private fun mix(z0: Long): Long {
        var z = z0
        z = (z xor (z ushr 30)) * -0x40A7B892E31B1A47L
        z = (z xor (z ushr 27)) * -0x6B2FB644ECCEEE15L
        return z xor (z ushr 31)
    }
}

/**
 * 可移动锚点的相邻两个服务器样本。
 *
 * 服务端每 tick 报一条位置，渲染按 `lerp(prev, cur, partialTick)` 求值，与 `track` 粒子
 * 逐 tick 消费一条样本的相位一致。
 *
 * - 瞬移：一条样本跳得比 [TELEPORT_LIMIT] 还远，直接落到新位置；
 * - 断流：超过 [STALE_NANOS] 没有新样本，停在最后位置而不外推；
 * - 恢复：断流后的第一条样本按跳变处理，中间路径无从重建。
 */
internal class MovableAnchorSamples {

    private var prev: Vec3? = null
    private var cur: Vec3? = null
    private var lastSampleNanos: Long = 0L

    /** 最近一条样本的速度方向，抖动平面与朝向用它；零向量时保留上一次。 */
    var direction: Vec3 = Vec3(0.0, 0.0, 1.0)
        private set

    /** 是否已有样本；还没有时发射器先不发射，等第一条位置。 */
    fun hasSample(): Boolean = cur != null

    fun onSample(pos: Vec3, velocity: Vec3, nowNanos: Long) {
        val c = cur
        val stale = nowNanos - lastSampleNanos > STALE_NANOS
        prev = when {
            c == null -> pos
            stale -> pos
            pos.distanceTo(c) > TELEPORT_LIMIT -> pos
            else -> c
        }
        cur = pos
        lastSampleNanos = nowNanos
        if (velocity.lengthSqr() > 1e-12) direction = velocity.normalize()
    }

    /** 本帧的锚点位置；[partialTick] 为渲染帧在相邻两个 tick 之间的进度。 */
    fun resolve(partialTick: Float, nowNanos: Long): Vec3? {
        val c = cur ?: return null
        val p = prev ?: return c
        if (nowNanos - lastSampleNanos > STALE_NANOS) return c
        return Vec3(
            p.x + (c.x - p.x) * partialTick,
            p.y + (c.y - p.y) * partialTick,
            p.z + (c.z - p.z) * partialTick,
        )
    }

    fun clear() {
        prev = null
        cur = null
        lastSampleNanos = 0L
    }

    companion object {
        /** 一条样本跳这么远（格）就按瞬移处理。 */
        const val TELEPORT_LIMIT = 8.0

        /** 多久（纳秒）没有新样本算断流，取 3 tick。 */
        const val STALE_NANOS = 150_000_000L
    }
}
