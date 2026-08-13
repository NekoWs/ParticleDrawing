package work.nekow.particledrawing.core.motion

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.Color

/** 运动算法接口。 */
interface MotionAlgorithm {
    val id: String

    data class Result(val position: Vec3? = null, val color: Color? = null,
                       val newPivot: Vec3? = null, val scale: Float? = null)

    fun compute(basePos: Vec3, pivot: Vec3, elapsedSeconds: Double): Result

    /** 算法工厂：由参数数组构造算法实例，可通过构造函数引用（如 `::RotateAlgorithm`）直接注册。 */
    typealias Factory = (DoubleArray) -> MotionAlgorithm
}

/** 按索引安全读取原始参数，越界时返回默认值。 */
fun DoubleArray.at(index: Int, default: Double = 0.0): Double =
    if (index in indices) this[index] else default
