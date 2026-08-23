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
            updatePos = true, updateColor = false, updateScale = false,
            durationTicks, easing, manager.getPlayers()
        )
        return this
    }

    /** [move] 的分量重载。 */
    fun move(x: Number, y: Number, z: Number, durationTicks: Int, easing: EasingType): ParticleHandle {
        return move(Vec3(x.toDouble(), y.toDouble(), z.toDouble()), durationTicks, easing)
    }

    /**
     * 立即移动粒子（无缓动）。
     * @param target 目标位置
     * @return 自身，支持链式调用
     */
    fun moveInstant(target: Vec3): ParticleHandle {
        return move(target, 0, EasingType.LINEAR)
    }

    /** [moveInstant] 的分量重载。 */
    fun moveInstant(x: Number, y: Number, z: Number): ParticleHandle {
        return moveInstant(Vec3(x.toDouble(), y.toDouble(), z.toDouble()))
    }

    /**
     * 设置粒子的速度向量（blocks/tick）。
     * @param velocity 速度向量
     * @return 自身，支持链式调用
     */
    fun setVelocity(velocity: Vec3): ParticleHandle {
        manager.getEngine().setVelocity(id, velocity, manager.getPlayers())
        return this
    }

    /** [setVelocity] 的分量重载。 */
    fun setVelocity(x: Number, y: Number, z: Number): ParticleHandle {
        return setVelocity(Vec3(x.toDouble(), y.toDouble(), z.toDouble()))
    }

    /**
     * 获取粒子当前在服务端的速度向量。
     * @return 速度向量，不存在则返回 null
     */
    fun velocity(): Vec3? {
        return manager.getEngine().getParticle(id)?.velocity()
    }

    /**
     * 动态修改粒子的发光光照等级 (0-15)，并同步到客户端。
     * @param level 目标光照等级，自动钳制到 [0, 15]
     * @return 自身，支持链式调用
     */
    fun lightLevel(level: Int): ParticleHandle {
        manager.getEngine().setLightLevel(id, level, manager.getPlayers())
        return this
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
            updatePos = false, updateColor = true, updateScale = false,
            durationTicks, easing, manager.getPlayers()
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
            updatePos = false, updateColor = false, updateScale = true,
            durationTicks, easing, manager.getPlayers()
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

        private var position: Vec3 = Vec3.ZERO
        private var color: Color = Color.WHITE
        private var scale: Float = 1.0f
        private var lifetime: Int = -1
        private var groupId: UUID? = null
        private var glowing: Boolean = false
        private var lightLevel: Int = 15
        private var offsetFromPivot: Vec3 = Vec3.ZERO

        /** 设置粒子位置。 */
        fun position(pos: Vec3) = apply { this.position = pos }

        /** 设置粒子位置。 */
        fun position(x: Number, y: Number, z: Number) = apply {
            this.position = Vec3(x.toDouble(), y.toDouble(), z.toDouble())
        }

        /** 设置粒子颜色。 */
        fun color(color: Color) = apply { this.color = color }

        /** 设置粒子颜色（整数分量）。 */
        fun color(r: Int, g: Int, b: Int) = apply {
            this.color = Color.ofInt(r, g, b)
        }

        /** 设置粒子颜色（整数分量，含透明度）。 */
        fun color(r: Int, g: Int, b: Int, a: Int) = apply {
            this.color = Color.ofInt(r, g, b, a)
        }

        /** 设置粒子缩放。 */
        fun scale(scale: Float) = apply { this.scale = scale }

        /**
         * 设置粒子生命周期（单位 tick）。-1 表示永存。
         * @param ticks 生命周期 tick 数
         */
        fun lifetime(ticks: Int) = apply { this.lifetime = ticks }

        /** 关联到指定粒子组。 */
        fun group(groupId: UUID) = apply { this.groupId = groupId }

        /** 标记为发光粒子。 */
        fun glowing(glowing: Boolean) = apply { this.glowing = glowing }

        /**
         * 设置发光粒子向外发出的光照等级 (0-15)，仅当 [glowing] 为 true 时生效。
         * @param level 光照等级，自动钳制到 [0, 15]
         */
        fun lightLevel(level: Int) = apply { this.lightLevel = level.coerceIn(0, 15) }

        /** 设置相对组轴心的偏移。 */
        fun offsetFromPivot(offset: Vec3) = apply { this.offsetFromPivot = offset }

        /** 设置相对组轴心的偏移。 */
        fun offsetFromPivot(x: Number, y: Number, z: Number) = apply {
            this.offsetFromPivot = Vec3(x.toDouble(), y.toDouble(), z.toDouble())
        }

        /**
         * 生成粒子并返回句柄以供后续控制。
         * @return 生成粒子的句柄；因达到维度粒子上限被拒绝时为 null
         */
        fun spawn(): ParticleHandle? {
            val engine = manager.getEngine()
            val data = engine.spawnParticle(
                position, color, scale, lifetime,
                groupId, glowing, lightLevel, offsetFromPivot,
                manager.getPlayers()
            ) ?: return null
            return ParticleHandle(data.id, manager)
        }
    }
}
