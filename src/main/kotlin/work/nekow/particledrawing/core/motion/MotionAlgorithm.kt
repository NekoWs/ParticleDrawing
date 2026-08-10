package work.nekow.particledrawing.core.motion

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.Color

/**
 * 运动算法接口。compute 基于实际耗时（秒）计算，不受 /tick 影响。
 */
interface MotionAlgorithm {
    val id: String
    val params: DoubleArray

    /**
     * @param basePos 基准位置
     * @param pivot 变换轴心
     * @param elapsedSeconds 自运动开始已过去的实际秒数
     * @return (新位置?, 新颜色?)
     */
    fun compute(basePos: Vec3, pivot: Vec3, elapsedSeconds: Double): Pair<Vec3?, Color?>

    interface Factory {
        val id: String
        fun create(params: DoubleArray): MotionAlgorithm
    }
}

val NO_PARAMS = DoubleArray(0)
