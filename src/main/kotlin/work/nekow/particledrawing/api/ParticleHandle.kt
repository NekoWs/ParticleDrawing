package work.nekow.particledrawing.api

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.core.easing.EasingType
import work.nekow.particledrawing.core.server.ParticleData
import java.util.UUID

/**
 * 已生成粒子的句柄，支持属性更新和生命周期控制。
 * 通过 [ParticleManager.create] 创建。
 */
@Suppress("unused")
class ParticleHandle(
    val id: UUID,
    private val manager: ParticleManager
) {
    /**
     * 使用缓动将粒子移动到新位置。
     * @param target 目标位置
     * @param durationTicks 持续 tick 数
     * @param easing 缓动类型
     * @return 自身，支持链式调用
     */
    fun move(target: Vec3, durationTicks: Int, easing: EasingType): ParticleHandle {
        val engine = manager.getEngine()
        val data = engine.getParticle(id) ?: return this

        engine.updateParticle(
            id, target, data.color(), data.scale(),
            true, false, false, durationTicks, easing, manager.getPlayers()
        )
        return this
    }

    /**
     * 立即移动粒子（无缓动）。
     * @param target 目标位置
     * @return 自身，支持链式调用
     */
    fun moveInstant(target: Vec3): ParticleHandle {
        return move(target, 0, EasingType.LINEAR)
    }

    /**
     * 使用缓动改变粒子颜色。
     * @param color 目标颜色
     * @param durationTicks 持续 tick 数
     * @param easing 缓动类型
     * @return 自身，支持链式调用
     */
    fun recolor(color: Color, durationTicks: Int, easing: EasingType): ParticleHandle {
        val engine = manager.getEngine()
        val data = engine.getParticle(id) ?: return this

        engine.updateParticle(
            id, data.position(), color, data.scale(),
            false, true, false, durationTicks, easing, manager.getPlayers()
        )
        return this
    }

    /**
     * 使用缓动改变粒子缩放。
     * @param scale 目标缩放值
     * @param durationTicks 持续 tick 数
     * @param easing 缓动类型
     * @return 自身，支持链式调用
     */
    fun resize(scale: Float, durationTicks: Int, easing: EasingType): ParticleHandle {
        val engine = manager.getEngine()
        val data = engine.getParticle(id) ?: return this

        engine.updateParticle(
            id, data.position(), data.color(), scale,
            false, false, true, durationTicks, easing, manager.getPlayers()
        )
        return this
    }

    /**
     * 立即销毁此粒子。
     */
    fun remove() {
        manager.getEngine().destroyParticle(id, manager.getPlayers())
    }

    /**
     * 获取粒子当前在服务端的状态。
     * @return 粒子数据，不存在则返回 null
     */
    fun data(): ParticleData? {
        return manager.getEngine().getParticle(id)
    }

    /**
     * 用于通过流式 API 创建粒子的构建器。
     */
    @Suppress("unused")
    class Builder(private val manager: ParticleManager) {

        private var style: ParticleStyle = ParticleStyle.DUST
        private var position: Vec3 = Vec3.ZERO
        private var color: Color = Color.WHITE
        private var scale: Float = 1.0f
        private var lifetime: Int = -1
        private var groupId: UUID? = null
        private var glowing: Boolean = false
        private var offsetFromPivot: Vec3 = Vec3.ZERO

        fun style(style: ParticleStyle) = apply { this.style = style }

        fun position(pos: Vec3) = apply { this.position = pos }

        fun position(x: Double, y: Double, z: Double) = apply {
            this.position = Vec3(x, y, z)
        }

        fun color(color: Color) = apply { this.color = color }

        fun color(r: Int, g: Int, b: Int) = apply {
            this.color = Color.ofInt(r, g, b)
        }

        fun color(r: Int, g: Int, b: Int, a: Int) = apply {
            this.color = Color.ofInt(r, g, b, a)
        }

        fun scale(scale: Float) = apply { this.scale = scale }

        /**
         * 设置粒子生命周期（单位 tick）。-1 表示永存。
         * @param ticks 生命周期 tick 数
         */
        fun lifetime(ticks: Int) = apply { this.lifetime = ticks }

        fun group(groupId: UUID) = apply { this.groupId = groupId }

        fun glowing(glowing: Boolean) = apply { this.glowing = glowing }

        fun offsetFromPivot(offset: Vec3) = apply { this.offsetFromPivot = offset }

        /**
         * 生成粒子并返回句柄以供后续控制。
         * @return 生成粒子的句柄
         */
        fun spawn(): ParticleHandle {
            val engine = manager.getEngine()
            val data = engine.spawnParticle(
                style, position, color, scale, lifetime,
                groupId, glowing, offsetFromPivot,
                manager.getPlayers()
            )

            if (offsetFromPivot != Vec3.ZERO) {
                engine.setOffsetFromPivot(data.id, offsetFromPivot)
            }

            return ParticleHandle(data.id, manager)
        }
    }
}
