package work.nekow.particledrawing.animation.emitter

import work.nekow.particledrawing.math.Vec3
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

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
