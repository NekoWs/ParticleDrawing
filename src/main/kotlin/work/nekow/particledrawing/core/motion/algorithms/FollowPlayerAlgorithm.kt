package work.nekow.particledrawing.core.motion.algorithms

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.core.motion.MotionAlgorithm
import work.nekow.particledrawing.core.motion.at

/**
 * 跟随目标点（默认玩家位置）移动，带指数平滑。
 * 目标点由 MotionSystem.targetProvider 提供，可替换为任意移动体。
 *
 * params = [ smoothFactor ]，默认 0.02
 */
class FollowPlayerAlgorithm(params: DoubleArray) : MotionAlgorithm {
    override val id = ID
    private val factor = params.at(0, 0.02)
    private var originPivot: Vec3? = null
    private var smoothPivot: Vec3? = null

    override fun updatePivot(pivot: Vec3, elapsedSeconds: Double, target: Vec3?): Vec3 {
        if (originPivot == null) originPivot = pivot
        val smooth = smoothPivot ?: pivot
        val next = if (target == null) smooth else Vec3(
            smooth.x + (target.x - smooth.x) * factor,
            smooth.y + (target.y - smooth.y) * factor,
            smooth.z + (target.z - smooth.z) * factor
        )
        smoothPivot = next
        return next
    }

    override fun compute(basePos: Vec3, pivot: Vec3, elapsedSeconds: Double, target: Vec3?): MotionAlgorithm.Result {
        val origin = originPivot ?: return MotionAlgorithm.Result()
        return MotionAlgorithm.Result(position = pivot.add(basePos.subtract(origin)))
    }

    companion object {
        const val ID = "follow_player"
    }
}
