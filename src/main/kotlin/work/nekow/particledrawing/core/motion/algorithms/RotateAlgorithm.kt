package work.nekow.particledrawing.core.motion.algorithms

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.core.motion.MotionAlgorithm
import work.nekow.particledrawing.core.motion.at
import work.nekow.particledrawing.core.motion.rotateAround

/** 绕任意轴匀速旋转。params = [ax, ay, az, radiansPerSecond]，缺省轴 (0,1,0)、角速度 0。 */
class RotateAlgorithm(params: DoubleArray) : MotionAlgorithm {
    override val id = ID

    private val axis = Vec3(params.at(0, 0.0), params.at(1, 1.0), params.at(2, 0.0)).normalize()
    private val radiansPerSecond = params.at(3)

    override fun compute(basePos: Vec3, pivot: Vec3, elapsedSeconds: Double, target: Vec3?): MotionAlgorithm.Result {
        if (axis == Vec3.ZERO || radiansPerSecond == 0.0) return MotionAlgorithm.Result(position = basePos)
        val rel = basePos.subtract(pivot)
        return MotionAlgorithm.Result(position = pivot.add(rel.rotateAround(axis, elapsedSeconds * radiansPerSecond)))
    }

    companion object {
        const val ID = "rotate"
    }
}
