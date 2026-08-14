package work.nekow.particledrawing.core.motion.algorithms

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.core.motion.MotionAlgorithm
import work.nekow.particledrawing.core.motion.at
import work.nekow.particledrawing.core.motion.rotateAround

/**
 * 螺旋扭转算法：粒子绕轴旋转，角速度随沿轴高度线性增大。
 *
 * 参数布局（DoubleArray，缺省值见括号）：
 * ```
 * [0..2] axis   旋转轴（默认 Y 轴 0,1,0）
 * [3]    speed  基准角速度 rad/s（默认 0.8）
 * [4]    twist  每格沿轴高度附加角速度 rad/s（默认 0.35）
 * ```
 */
class SwirlAlgorithm(params: DoubleArray) : MotionAlgorithm {
    override val id = ID

    private val axis = Vec3(params.at(0, 0.0), params.at(1, 1.0), params.at(2, 0.0)).normalize()
    private val speed = params.at(3, 0.8)
    private val twist = params.at(4, 0.35)

    override fun compute(basePos: Vec3, pivot: Vec3, elapsedSeconds: Double, target: Vec3?): MotionAlgorithm.Result {
        if (axis == Vec3.ZERO) return MotionAlgorithm.Result(position = basePos)
        val rel = basePos.subtract(pivot)
        val angle = elapsedSeconds * (speed + twist * rel.dot(axis))
        return MotionAlgorithm.Result(position = pivot.add(rel.rotateAround(axis, angle)))
    }

    companion object {
        const val ID = "swirl"
    }
}
