package work.nekow.particledrawing.api

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.core.easing.EasingType
import java.util.UUID

/**
 * 一组可同时变换的粒子集合。
 * 通过 [Draw] 工具或 [ParticleManager.createGroup] 创建。
 */
@Suppress("unused")
class ParticleGroup(
    val id: UUID,
    var pivot: Vec3,
    internal val manager: ParticleManager
) {
    /**
     * 设置后续变换的基准点。
     * @param pivot 新的基准点
     * @return 自身，支持链式调用
     */
    fun setPivot(pivot: Vec3): ParticleGroup {
        this.pivot = pivot
        val group = manager.getEngine().getGroup(id)
        group?.setPivot(pivot)
        return this
    }

    /**
     * 使用缓动平移组内所有粒子。
     * @param delta 平移向量
     * @param durationTicks 缓动持续时间 (tick)
     * @param easing 缓动曲线类型
     * @return 自身，支持链式调用
     */
    @Suppress("unused")
    fun move(delta: Vec3, durationTicks: Int, easing: EasingType): ParticleGroup {
        manager.getEngine().applyGroupTransform(
            id, TransformOp.Type.TRANSLATE,
            delta, Vec3.ZERO, 0.0, Color.WHITE, 0f, pivot,
            durationTicks, easing, manager.getPlayers()
        )
        pivot = pivot.add(delta)
        return this
    }

    /**
     * 绕基准点旋转组内所有粒子。
     * @param axis 归一化的旋转轴 (如 Vec3.Z 为 Z 轴)
     * @param radians 旋转角度 (弧度)
     * @param durationTicks 缓动持续时间 (tick)
     * @param easing 缓动曲线类型
     * @return 自身，支持链式调用
     */
    @Suppress("unused")
    fun rotate(axis: Vec3, radians: Double, durationTicks: Int, easing: EasingType): ParticleGroup {
        manager.getEngine().applyGroupTransform(
            id, TransformOp.Type.ROTATE,
            Vec3.ZERO, axis, radians, Color.WHITE, 0f, pivot,
            durationTicks, easing, manager.getPlayers()
        )
        return this
    }

    /**
     * 使用缓动重新着色组内所有粒子。
     * @param targetColor 目标颜色
     * @param durationTicks 缓动持续时间 (tick)
     * @param easing 缓动曲线类型
     * @return 自身，支持链式调用
     */
    @Suppress("unused")
    fun recolor(targetColor: Color, durationTicks: Int, easing: EasingType): ParticleGroup {
        manager.getEngine().applyGroupTransform(
            id, TransformOp.Type.RECOLOR,
            Vec3.ZERO, Vec3.ZERO, 0.0, targetColor, 0f, null,
            durationTicks, easing, manager.getPlayers()
        )
        return this
    }

    /**
     * 相对于基准点缩放组内所有粒子。
     * @param targetScale 目标缩放值
     * @param durationTicks 缓动持续时间 (tick)
     * @param easing 缓动曲线类型
     * @return 自身，支持链式调用
     */
    fun scale(targetScale: Float, durationTicks: Int, easing: EasingType): ParticleGroup {
        manager.getEngine().applyGroupTransform(
            id, TransformOp.Type.SCALE,
            Vec3.ZERO, Vec3.ZERO, 0.0, Color.WHITE, targetScale, pivot,
            durationTicks, easing, manager.getPlayers()
        )
        return this
    }

    /**
     * 向该组添加一个粒子。
     * @param handle 粒子的句柄
     */
    fun add(handle: ParticleHandle) {
        val group = manager.getEngine().getGroup(id)
        group?.addMember(handle.id)
    }

    /**
     * 获取组内成员数量。
     * @return 粒子数量
     */
    fun size(): Int {
        val group = manager.getEngine().getGroup(id)
        return group?.size() ?: 0
    }

    /**
     * 启动客户端帧级运动算法。客户端从基准位置每帧通过注册的算法函数计算。
     *
     * @param algorithmId 算法标识（如 "rotate", "color_by_y"）
     * @param params 算法参数数组
     */
    @JvmOverloads
    fun addMotion(algorithmId: String, params: DoubleArray = DoubleArray(0)) {
        manager.getEngine().sendMotion(id, true, algorithmId, params, pivot, manager.getPlayers())
    }

    /** 启动绕 X 轴旋转（基于实际秒数，不受 /tick 影响）。 */
    fun rotateMotion(radiansPerSecond: Double) {
        addMotion("rotate", doubleArrayOf(1.0, 0.0, 0.0, radiansPerSecond))
    }

    /** 便捷方法：启动按 Y 坐标着色。 */
    fun colorByYMotion() {
        addMotion("color_by_y")
    }

    /** 停止所有运动。 */
    fun stopMotion() {
        manager.getEngine().sendMotion(id, false, "", DoubleArray(0), pivot, manager.getPlayers())
    }

    /**
     * 销毁整个粒子组及其所有粒子。
     */
    fun remove() {
        manager.getEngine().destroyGroup(id, manager.getPlayers())
    }

    override fun toString() = "ParticleGroup{$id size=${size()}}"
}
