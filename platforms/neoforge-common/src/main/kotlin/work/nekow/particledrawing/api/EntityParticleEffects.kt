package work.nekow.particledrawing.api

import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.core.server.ServerEntityParticleEffects
import work.nekow.particledrawing.math.ResourceId

/** One burst at the event's world position. [stopEmission] stops only this binding's continuous source. */
data class EntityParticleBurst @JvmOverloads constructor(
    val recipe: ParticleRecipe,
    val each: Boolean = false,
    val stopEmission: Boolean = false,
    val mode: ParticleRecipeMode = ParticleRecipeMode.STANDARD,
)

/** Server-thread bindings scoped to an entity's current stay in a loaded level. */
object EntityParticleEffects {
    @JvmField val IMPACT = ResourceId("particledrawing", "impact")
    @JvmField val DEATH = ResourceId("particledrawing", "death")

    /** Returns false if this name is already bound to the entity. Finite particles drain on unbind. */
    @JvmStatic
    @JvmOverloads
    fun bind(
        entity: Entity,
        name: String,
        emission: ParticleRecipe? = null,
        events: Map<ResourceId, EntityParticleBurst> = emptyMap(),
        emissionMode: ParticleRecipeMode = ParticleRecipeMode.STANDARD,
    ): Boolean = ServerEntityParticleEffects.bind(entity, name, emission, events, emissionMode)

    @JvmStatic fun isBound(entity: Entity, name: String): Boolean = ServerEntityParticleEffects.isBound(entity, name)

    @JvmStatic fun unbind(entity: Entity, name: String): Boolean = ServerEntityParticleEffects.unbind(entity, name)

    /** Dispatch a built-in or mod-defined event. Returns the number of bursts started. */
    @JvmStatic
    fun trigger(entity: Entity, event: ResourceId, position: Vec3): Int =
        ServerEntityParticleEffects.trigger(entity, event, position)
}
