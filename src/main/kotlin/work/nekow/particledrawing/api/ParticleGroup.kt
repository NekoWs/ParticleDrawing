package work.nekow.particledrawing.api

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.core.easing.EasingType
import java.util.UUID

/**
 * A collection of particles that can be transformed together.
 * Created via [Draw] utilities or [ParticleManager.createGroup].
 */
@Suppress("unused")
class ParticleGroup(
    val id: UUID,
    var pivot: Vec3,
    private val manager: ParticleManager
) {
    /**
     * Set the pivot point for future transforms.
     */
    fun setPivot(pivot: Vec3): ParticleGroup {
        this.pivot = pivot
        val group = manager.getEngine().getGroup(id)
        group?.setPivot(pivot)
        return this
    }

    /**
     * Translate all particles in the group with easing.
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
     * Rotate all particles around the pivot with easing.
     * @param axis normalized rotation axis (e.g. Vec3.Z for Z-axis)
     * @param radians rotation angle in radians
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
     * Recolor all particles in the group with easing.
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
     * Scale all particles relative to the pivot with easing.
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
     * Add a particle to this group.
     */
    fun add(handle: ParticleHandle) {
        val group = manager.getEngine().getGroup(id)
        group?.addMember(handle.id)
    }

    /**
     * Get the member count.
     */
    fun size(): Int {
        val group = manager.getEngine().getGroup(id)
        return group?.size() ?: 0
    }

    /**
     * Destroy the entire group and all its particles.
     */
    fun remove() {
        manager.getEngine().destroyGroup(id, manager.getPlayers())
    }

    override fun toString() = "ParticleGroup{$id size=${size()}}"
}
