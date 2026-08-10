package work.nekow.particledrawing.core.motion.algorithms

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.core.motion.MotionAlgorithm
import kotlin.math.cos
import kotlin.math.sin

/**
 * 绕任意轴旋转。基于实际耗时（秒）。
 * params = [ax, ay, az, radiansPerSecond]
 */
class RotateAlgorithm(override val params: DoubleArray) : MotionAlgorithm {
    override val id = "rotate"

    override fun compute(basePos: Vec3, pivot: Vec3, elapsedSeconds: Double): Pair<Vec3?, Color?> {
        val ax = params[0]; val ay = params[1]; val az = params[2]
        val speed = params[3]
        val angle = elapsedSeconds * speed
        val axis = Vec3(ax, ay, az)
        val rel = basePos.subtract(pivot)
        val cosA = cos(angle); val sinA = sin(angle)
        val dot = rel.dot(axis); val cross = axis.cross(rel)
        val rotated = Vec3(
            rel.x * cosA + cross.x * sinA + axis.x * dot * (1 - cosA),
            rel.y * cosA + cross.y * sinA + axis.y * dot * (1 - cosA),
            rel.z * cosA + cross.z * sinA + axis.z * dot * (1 - cosA)
        )
        return Pair(pivot.add(rotated), null)
    }

    companion object Factory : MotionAlgorithm.Factory {
        override val id = "rotate"
        override fun create(params: DoubleArray) = RotateAlgorithm(params)
    }
}
