package work.nekow.particledrawing.core.client

import work.nekow.particledrawing.core.ParticleFieldSamples
import work.nekow.particledrawing.core.network.FieldPlacement
import work.nekow.particledrawing.math.Vec3

internal object ClientParticleFields {
    private var placements = emptyList<FieldPlacement>()
    private val samples = ParticleFieldSamples()

    fun replace(next: List<FieldPlacement>) {
        placements = next
        refresh()
    }

    fun refresh() = samples.refresh(placements, ::resolveRecipeAnchor)

    fun accelerationAt(position: Vec3, tick: Long): Vec3 = samples.accelerationAt(position, tick)

    fun clear() = replace(emptyList())
}
