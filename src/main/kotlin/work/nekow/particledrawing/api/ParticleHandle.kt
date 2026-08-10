package work.nekow.particledrawing.api

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.core.easing.EasingType
import work.nekow.particledrawing.core.server.ParticleData
import work.nekow.particledrawing.core.server.ServerParticleEngine
import java.util.UUID

/**
 * Handle for a spawned particle, allowing property updates and lifecycle control.
 * Created via [ParticleManager.create].
 */
@Suppress("unused")
class ParticleHandle(
    val id: UUID,
    private val manager: ParticleManager
) {
    /**
     * Move this particle to a new position with easing.
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
     * Move this particle immediately (no easing).
     */
    fun moveInstant(target: Vec3): ParticleHandle {
        return move(target, 0, EasingType.LINEAR)
    }

    /**
     * Change color with easing.
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
     * Change scale with easing.
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
     * Destroy this particle immediately.
     */
    fun remove() {
        manager.getEngine().destroyParticle(id, manager.getPlayers())
    }

    /**
     * Get the current server-side state.
     */
    fun data(): ParticleData? {
        return manager.getEngine().getParticle(id)
    }

    /**
     * Builder for creating particles with a fluent API.
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
         * Set particle lifetime in ticks. -1 for immortal.
         */
        fun lifetime(ticks: Int) = apply { this.lifetime = ticks }

        fun group(groupId: UUID) = apply { this.groupId = groupId }

        fun glowing(glowing: Boolean) = apply { this.glowing = glowing }

        fun offsetFromPivot(offset: Vec3) = apply { this.offsetFromPivot = offset }

        /**
         * Spawn the particle and return a handle for further control.
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
