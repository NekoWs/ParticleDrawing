package work.nekow.particledrawing.core.motion.algorithms

import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.core.motion.MotionAlgorithm

/**
 * 基于玩家距离缩放粒子。越近越大，越远越小。
 * params = [maxScale, minScale, maxDistance], 默认 [1.0, 0.05, 6.0]
 */
class ScaleByDistanceAlgorithm(override val params: DoubleArray) : MotionAlgorithm {
    override val id = "scale_by_distance"
    private val maxScale = if (params.isNotEmpty()) params[0].toFloat() else 1.0f
    private val minScale = if (params.size > 1) params[1].toFloat() else 0.05f
    private val maxDist = if (params.size > 2) params[2] else 6.0

    override fun compute(basePos: Vec3, pivot: Vec3, elapsedSeconds: Double): MotionAlgorithm.Result {
        val player = Minecraft.getInstance().player ?: return MotionAlgorithm.Result()
        val dist = player.position().distanceTo(basePos)
        val t = (1.0 - (dist / maxDist).coerceIn(0.0, 1.0)).toFloat()
        val scale = minScale + (maxScale - minScale) * t * t  // 二次曲线，近处变化更明显
        return MotionAlgorithm.Result(scale = scale)
    }

    companion object Factory : MotionAlgorithm.Factory {
        override val id = "scale_by_distance"
        override fun create(params: DoubleArray) = ScaleByDistanceAlgorithm(params)
    }
}
