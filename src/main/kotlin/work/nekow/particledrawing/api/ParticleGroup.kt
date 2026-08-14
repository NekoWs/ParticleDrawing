package work.nekow.particledrawing.api

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.core.easing.EasingType
import work.nekow.particledrawing.core.motion.algorithms.ColorGradientAlgorithm
import work.nekow.particledrawing.core.motion.algorithms.FollowPlayerAlgorithm
import work.nekow.particledrawing.core.motion.algorithms.RotateAlgorithm
import work.nekow.particledrawing.core.motion.algorithms.ScaleByDistanceAlgorithm
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

    /** [setPivot] 的分量重载。 */
    fun setPivot(x: Number, y: Number, z: Number): ParticleGroup {
        return setPivot(Vec3(x.toDouble(), y.toDouble(), z.toDouble()))
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
        val newPivot = pivot.add(delta)
        manager.getEngine().applyGroupTransform(
            id, TransformOp.Type.TRANSLATE,
            delta, Vec3.ZERO, 0.0, Color.WHITE, 0f, pivot,
            durationTicks, easing, manager.getPlayers()
        )
        pivot = newPivot
        manager.getEngine().getGroup(id)?.setPivot(newPivot)
        return this
    }

    /** [move] 的分量重载。 */
    fun move(x: Number, y: Number, z: Number, durationTicks: Int, easing: EasingType): ParticleGroup {
        return move(Vec3(x.toDouble(), y.toDouble(), z.toDouble()), durationTicks, easing)
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

    /** [rotate] 的分量重载。 */
    fun rotate(x: Number, y: Number, z: Number, radians: Double, durationTicks: Int, easing: EasingType): ParticleGroup {
        return rotate(Vec3(x.toDouble(), y.toDouble(), z.toDouble()), radians, durationTicks, easing)
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
     * @param handle 粒子的句柄，可为 null（粒子因达到上限被拒绝时）
     */
    fun add(handle: ParticleHandle?) {
        if (handle == null) return
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
     * 启动帧级运动算法。
     * @param algorithmId 算法标识（如 "rotate"、"color_gradient"）
     * @param params 算法参数数组
     */
    @JvmOverloads
    fun addMotion(algorithmId: String, params: DoubleArray = DoubleArray(0)) {
        manager.getEngine().sendMotion(id, true, algorithmId, params, pivot, manager.getPlayers())
    }

    /** 启动绕 X 轴旋转。 */
    fun rotateMotion(radiansPerSecond: Double) {
        addMotion(RotateAlgorithm.ID, doubleArrayOf(1.0, 0.0, 0.0, radiansPerSecond))
    }

    /** 启动渐变着色（HSB 色相渐变，默认参数）。 */
    fun colorGradientMotion() {
        addMotion(ColorGradientAlgorithm.ID)
    }

    /** 启动自定义参数的渐变着色。参数可由 [ColorGradientAlgorithm.hsbParams] / [ColorGradientAlgorithm.rgbParams] 构造。 */
    fun colorGradientMotion(params: DoubleArray) {
        addMotion(ColorGradientAlgorithm.ID, params)
    }

    /** 跟随玩家移动，带指数平滑。 */
    fun followPlayerMotion(smoothFactor: Double = 0.06) {
        addMotion(FollowPlayerAlgorithm.ID, doubleArrayOf(smoothFactor))
    }

    /** 基于玩家距离缩放粒子。 */
    fun scaleByDistanceMotion(maxScale: Double = 1.0, minScale: Double = 0.05, maxDistance: Double = 6.0) {
        addMotion(ScaleByDistanceAlgorithm.ID, doubleArrayOf(maxScale, minScale, maxDistance))
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
