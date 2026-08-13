package work.nekow.particledrawing.core.motion

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.Color

/**
 * 运动算法接口。
 *
 * 每个 tick 按两阶段执行：
 * 1. [updatePivot] —— 每个算法调用一次，用于更新组轴心（可有内部状态）；
 * 2. [compute] —— 对组内每个粒子调用一次，应为纯计算（无副作用）。
 *
 * 算法不直接依赖 Minecraft 环境：目标点由 [MotionSystem.targetProvider]
 * 提供（默认为本地玩家位置），可替换以泛化用途。
 */
interface MotionAlgorithm {
    val id: String

    data class Result(
        val position: Vec3? = null,
        val color: Color? = null,
        val scale: Float? = null
    )

    /** 更新组轴心并返回新轴心，默认不变。 */
    fun updatePivot(pivot: Vec3, elapsedSeconds: Double, target: Vec3?): Vec3 = pivot

    /** 计算单个粒子的输出。 */
    fun compute(basePos: Vec3, pivot: Vec3, elapsedSeconds: Double, target: Vec3?): Result

    /** 算法工厂：由参数数组构造算法实例，可通过构造函数引用（如 `::RotateAlgorithm`）直接注册。 */
    typealias Factory = (DoubleArray) -> MotionAlgorithm
}

/** 按索引安全读取原始参数，越界时返回默认值。 */
fun DoubleArray.at(index: Int, default: Double = 0.0): Double =
    if (index in indices) this[index] else default
