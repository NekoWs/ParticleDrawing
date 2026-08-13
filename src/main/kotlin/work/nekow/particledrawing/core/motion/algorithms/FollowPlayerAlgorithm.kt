package work.nekow.particledrawing.core.motion.algorithms

import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.core.motion.MotionAlgorithm
import work.nekow.particledrawing.core.motion.at

/**
 * 跟随玩家移动，带指数平滑。
 * params = [ smoothFactor ]
 */
class FollowPlayerAlgorithm(params: DoubleArray) : MotionAlgorithm {
    override val id = ID
    private val factor = params.at(0, 0.02)
    private var smoothPivot: Vec3? = null
    private val offsets: MutableMap<Vec3, Vec3> = mutableMapOf()

    override fun compute(basePos: Vec3, pivot: Vec3, elapsedSeconds: Double): MotionAlgorithm.Result {
        if (smoothPivot == null) smoothPivot = pivot
        val player = Minecraft.getInstance().player ?: return MotionAlgorithm.Result()
        val target = player.position()

        smoothPivot = Vec3(
            smoothPivot!!.x + (target.x - smoothPivot!!.x) * factor,
            smoothPivot!!.y + (target.y - smoothPivot!!.y) * factor,
            smoothPivot!!.z + (target.z - smoothPivot!!.z) * factor
        )

        offsets.putIfAbsent(basePos, basePos.subtract(pivot))
        val offset = offsets[basePos] ?: basePos.subtract(pivot)

        return MotionAlgorithm.Result(
            position = smoothPivot!!.add(offset),
            newPivot = smoothPivot
        )
    }

    companion object {
        const val ID = "follow_player"
    }
}
