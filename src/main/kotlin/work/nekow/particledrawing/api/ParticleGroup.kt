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
     * 启动客户端预测的持续旋转。客户端从初始位置每帧计算旋转，零漂移、无网络延迟。
     * @param axis 旋转轴（归一化）
     * @param radiansPerTick 每 tick 旋转弧度
     */
    fun rotateContinuously(axis: Vec3, radiansPerTick: Double) {
        manager.getEngine().sendContinuousRotation(id, true, axis, radiansPerTick, pivot, manager.getPlayers())
    }

    /**
     * 停止持续旋转。
     */
    fun stopContinuousRotation() {
        manager.getEngine().sendContinuousRotation(id, false, Vec3.ZERO, 0.0, pivot, manager.getPlayers())
    }

    /**
     * 销毁整个粒子组及其所有粒子。
     */
    fun remove() {
        manager.getEngine().destroyGroup(id, manager.getPlayers())
    }

    override fun toString() = "ParticleGroup{$id size=${size()}}"
}
