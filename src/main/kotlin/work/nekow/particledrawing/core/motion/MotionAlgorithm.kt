package work.nekow.particledrawing.core.motion

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.Color

/** 运动算法接口。 */
interface MotionAlgorithm {
    val id: String
    val params: DoubleArray

    data class Result(val position: Vec3? = null, val color: Color? = null, val newPivot: Vec3? = null)

    fun compute(basePos: Vec3, pivot: Vec3, elapsedSeconds: Double): Result

    interface Factory {
        val id: String
        fun create(params: DoubleArray): MotionAlgorithm
    }
}
