package work.nekow.particledrawing.core

import work.nekow.particledrawing.animation.recipe.RecipeFrame
import work.nekow.particledrawing.api.ParticleForceField
import work.nekow.particledrawing.api.RecipeAnchor
import work.nekow.particledrawing.core.network.FieldPlacement
import work.nekow.particledrawing.math.Vec3

internal class ParticleFieldSamples {
    private data class Field(val spec: ParticleForceField, val center: Vec3, val direction: Vec3)
    private val active = arrayListOf<Field>()

    fun refresh(placements: Collection<FieldPlacement>, anchorFrame: (RecipeAnchor) -> RecipeFrame?) {
        active.clear()
        for ((spec, anchor) in placements) {
            val frame = anchorFrame(anchor) ?: continue
            active += Field(spec, frame.origin, frame.velocity(spec.direction))
        }
    }

    fun accelerationAt(position: Vec3, tick: Long): Vec3 {
        var sum = Vec3.ZERO
        for ((spec, center, direction) in active) {
            sum = sum.add(spec.acceleration(position.subtract(center), direction, tick))
        }
        return sum
    }
}
