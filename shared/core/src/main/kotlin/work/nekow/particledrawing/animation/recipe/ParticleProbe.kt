package work.nekow.particledrawing.animation.recipe

import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.translation
import work.nekow.particledrawing.math.ResourceId
import work.nekow.particledrawing.math.Vec3

/** 从粒子当前位置探测前方首个碰撞面，查询不改变粒子状态。 */
data class ParticleProbe(
    val range: Double,
    val mode: RecipeCollision = RecipeCollision.SOLID,
    val direction: Vec3? = null,
    val material: ResourceId? = null,
) {
    private val fixed = direction?.normalize()
    private val sweep = ParticleSweep(mode, includeMaterial = material != null)

    init {
        requireLocalized(range in MIN_RANGE..MAX_RANGE) { translation("error.recipe.probe_range", MIN_RANGE, MAX_RANGE) }
        requireLocalized(mode != RecipeCollision.NONE) { translation("error.recipe.probe_mode") }
        requireLocalized(direction == null || (direction.lengthSqr().isFinite() && fixed != Vec3.ZERO)) {
            translation("error.recipe.probe_direction")
        }
    }

    internal data class Reading(val distance: Double, val hit: SurfaceHit? = null, val matches: Boolean = false)

    internal fun read(world: ParticleWorld?, frame: RecipeFrame?, position: Vec3, velocity: Vec3): Reading {
        if (world == null || frame == null) return empty
        val axis = fixed ?: velocity.normalize()
        if (axis == Vec3.ZERO) return empty
        val end = position.add(frame.velocity(axis).scale(range))
        val hit = world.cast(sweep, position, end) ?: return empty
        return Reading(position.distanceTo(hit.position).coerceAtMost(range), hit, material == null || material == hit.material)
    }

    private val empty = Reading(range)

    companion object {
        const val MIN_RANGE = 0.01
        const val MAX_RANGE = 32.0
    }
}
