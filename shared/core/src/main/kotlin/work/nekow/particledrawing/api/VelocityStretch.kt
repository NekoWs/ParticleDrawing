package work.nekow.particledrawing.api

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * 速度对齐：把四边形的长轴（局部 X）转到速度方向。
 *
 * 渲染端按 `rotationXYZ(x, y, z)` 组合（先绕 X、再绕 Y、最后绕 Z），长轴是局部 X，
 * 所以只需要 Y 与 Z 两个角：
 * - 绕 Z 决定长轴在水平面上的朝向（抬头/低头）
 * - 绕 Y 决定长轴的水平方位
 * 绕 X 的自转不影响长轴方向，保持 0。
 */
object VelocityStretch {

    /** 速度低于这个值视为静止，不做对齐，避免慢速粒子乱转。 */
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