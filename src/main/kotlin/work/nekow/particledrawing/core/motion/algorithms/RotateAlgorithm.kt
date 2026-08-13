package work.nekow.particledrawing.core.motion.algorithms

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.core.motion.MotionAlgorithm
import work.nekow.particledrawing.core.motion.at
import kotlin.math.cos
import kotlin.math.sin

/** 绕任意轴旋转。params = [ax, ay, az, radiansPerSecond] */
class RotateAlgorithm(params: DoubleArray) : MotionAlgorithm {
    override val id = ID

    private val axis = Vec3(params.at(0), params.at(1), params.at(2))
    private val radiansPerSecond = params.at(3)

    override fun compute(basePos: Vec3, pivot: Vec3, elapsedSeconds: Double): MotionAlgorithm.Result {
        val angle = elapsedSeconds * radiansPerSecond
        val rel = basePos.subtract(pivot)
        val c = cos(angle); val s = sin(angle)
        val dot = rel.dot(axis); val cross = axis.cross(rel)
        val rotated = Vec3(
            rel.x * c + cross.x * s + axis.x * dot * (1 - c),
            rel.y * c + cross.y * s + axis.y * dot * (1 - c),
            rel.z * c + cross.z * s + axis.z * dot * (1 - c)
        )
        return MotionAlgorithm.Result(position = pivot.add(rotated))
    }

    companion object {
        const val ID = "rotate"
    }
}
