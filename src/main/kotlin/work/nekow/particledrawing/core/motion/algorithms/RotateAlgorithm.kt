package work.nekow.particledrawing.core.motion.algorithms

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.core.motion.MotionAlgorithm
import kotlin.math.cos
import kotlin.math.sin

/** 绕任意轴旋转。params = [ax, ay, az, radiansPerSecond] */
class RotateAlgorithm(override val params: DoubleArray) : MotionAlgorithm {
    override val id = "rotate"

    override fun compute(basePos: Vec3, pivot: Vec3, elapsedSeconds: Double): MotionAlgorithm.Result {
        val ax = params[0]; val ay = params[1]; val az = params[2]
        val angle = elapsedSeconds * params[3]
        val axis = Vec3(ax, ay, az)
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

    companion object Factory : MotionAlgorithm.Factory {
        override val id = "rotate"
        override fun create(params: DoubleArray) = RotateAlgorithm(params)
    }
}
