package work.nekow.particledrawing.core.client

import work.nekow.particledrawing.api.ParticleForceField
import work.nekow.particledrawing.core.network.FieldPlacement
import work.nekow.particledrawing.math.Vec3

internal object ClientParticleFields {
    private data class Field(val spec: ParticleForceField, val center: Vec3, val direction: Vec3)

    private var placements = emptyList<FieldPlacement>()
    private val active = arrayListOf<Field>()

    fun replace(next: List<FieldPlacement>) {
        placements = next
        refresh()
    }

    fun refresh() {
        active.clear()
        for ((spec, anchor) in placements) {
            val frame = resolveRecipeAnchor(anchor) ?: continue
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

    fun clear() = replace(emptyList())
}
