package work.nekow.particledrawing.api

import kotlin.math.atan2
import kotlin.math.sqrt

/** 将四边形的局部 X 轴转到世界速度方向。 */
object VelocityStretch {

    const val MAX_STRETCH = 10.0

    /** 速度低于这个值时保留原朝向。 */
    const val MIN_SPEED = 1e-4

    /** 拉长后的上限系数，防止极快的粒子拉成一条贯穿屏幕的线。 */
    const val MAX_LENGTH_FACTOR = 12.0

    /**
     * 写入朝向欧拉角（度，`[rx, ry, rz]`，与 `ParticleVisual.spin` 同序）。
     *
     * @return 是否真的做了对齐（速度为 0 时返回 false，调用方保持原朝向）
     */
    fun spinDegrees(vx: Double, vy: Double, vz: Double, out: DoubleArray): Boolean {
        val speed = sqrt(vx * vx + vy * vy + vz * vz)
        if (speed < MIN_SPEED) return false
        // R = Rz·Ry·Rx 作用在局部 X 上：(cos rz·cos ry, sin rz·cos ry, -sin ry)
        // 于是 ry 由 z 分量决定，rz 由 x/y 的比例决定，rx 不影响长轴。
        val inPlane = sqrt(vx * vx + vy * vy)
        out[0] = 0.0
        out[1] = Math.toDegrees(atan2(-vz, inPlane))
        out[2] = Math.toDegrees(atan2(vy, vx))
        return true
    }

    /** 长度系数：速度越快拉得越长，结果落在 1..[MAX_LENGTH_FACTOR]。 */
    fun lengthFactor(speed: Double, stretch: Double): Double {
        if (stretch <= 0.0 || speed <= MIN_SPEED) return 1.0
        return (1.0 + stretch * speed).coerceIn(1.0, MAX_LENGTH_FACTOR)
    }

}