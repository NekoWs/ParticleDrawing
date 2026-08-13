package work.nekow.particledrawing.core.motion.algorithms

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.core.motion.MotionAlgorithm
import work.nekow.particledrawing.core.motion.at
import kotlin.math.cos
import kotlin.math.sin

/**
 * 螺旋扭转算法：粒子绕轴旋转，角速度随沿轴高度线性增大，
 * 模拟龙卷风/涡柱的剪切扭转。纯客户端帧级计算，零网络开销。
 *
 * 参数布局（DoubleArray，缺省值见括号）：
 * ```
 * [0..2] axis   旋转轴，需单位向量（默认 Y 轴 0,1,0）
 * [3]    speed  基准角速度 rad/s（默认 0.8）
 * [4]    twist  每格沿轴高度附加角速度 rad/s（默认 0.35）
 * ```
 */
class SwirlAlgorithm(params: DoubleArray) : MotionAlgorithm {
    override val id = ID

    private val axis = Vec3(params.at(0, 0.0), params.at(1, 1.0), params.at(2, 0.0)).normalize()
    private val speed = params.at(3, 0.8)
    private val twist = params.at(4, 0.35)

    override fun compute(basePos: Vec3, pivot: Vec3, elapsedSeconds: Double): MotionAlgorithm.Result {
        val rel = basePos.subtract(pivot)
        val height = rel.dot(axis)
        val angle = elapsedSeconds * (speed + twist * height)

        val c = cos(angle)
        val s = sin(angle)
        val dot = rel.dot(axis)
        val cross = axis.cross(rel)
        val rotated = Vec3(
            rel.x * c + cross.x * s + axis.x * dot * (1 - c),
            rel.y * c + cross.y * s + axis.y * dot * (1 - c),
            rel.z * c + cross.z * s + axis.z * dot * (1 - c)
        )
        return MotionAlgorithm.Result(position = pivot.add(rotated))
    }

    companion object {
        const val ID = "swirl"
    }
}
