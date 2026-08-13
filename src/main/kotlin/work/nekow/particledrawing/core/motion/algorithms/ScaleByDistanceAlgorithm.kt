package work.nekow.particledrawing.core.motion.algorithms

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.core.motion.MotionAlgorithm
import work.nekow.particledrawing.core.motion.at

/**
 * 基于目标点（默认玩家位置）距离缩放粒子。越近越大，越远越小。
 * 目标点由 MotionSystem.targetProvider 提供，可替换为任意参照点。
 *
 * params = [maxScale, minScale, maxDistance], 默认 [1.0, 0.05, 6.0]
 */
class ScaleByDistanceAlgorithm(params: DoubleArray) : MotionAlgorithm {
    override val id = ID
    private val maxScale = params.at(0, 1.0).toFloat()
    private val minScale = params.at(1, 0.05).toFloat()
    private val maxDist = params.at(2, 6.0)

    override fun compute(basePos: Vec3, pivot: Vec3, elapsedSeconds: Double, target: Vec3?): MotionAlgorithm.Result {
        val t = target ?: return MotionAlgorithm.Result()
        val dist = t.distanceTo(basePos)
        val f = (1.0 - (dist / maxDist).coerceIn(0.0, 1.0)).toFloat()
        // 二次曲线，近处变化更明显
        return MotionAlgorithm.Result(scale = minScale + (maxScale - minScale) * f * f)
    }

    companion object {
        const val ID = "scale_by_distance"
    }
}
