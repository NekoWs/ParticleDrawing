package work.nekow.particledrawing.animation.recipe

import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.translation
import work.nekow.particledrawing.math.Vec3

internal object CollisionMath {
    const val SURFACE_MARGIN = 1e-4
    const val MAX_STEP = 1024.0
    private const val REST_VELOCITY = 0.02

    data class Response(val position: Vec3, val velocity: Vec3, val surface: SurfaceHit)

    fun resolve(
        mode: RecipeCollision, world: ParticleWorld, from: Vec3, to: Vec3,
        velocity: Vec3, bounce: Double, friction: Double, includeMaterial: Boolean = false, halfSize: Double = 0.0,
    ): Response? {
        if (mode == RecipeCollision.NONE || from == to) return null
        requireLocalized(from.distanceToSqr(to) <= MAX_STEP * MAX_STEP) {
            translation("error.recipe.collision_step", MAX_STEP.toInt())
        }
        val hit = world.cast(ParticleSweep(mode, halfSize, includeMaterial), from, to) ?: return null
        if (hit.inside) return null // 允许在方块里出生的粒子离开。
        val normalSpeed = velocity.dot(hit.normal)
        val tangent = velocity.subtract(hit.normal.scale(normalSpeed)).scale(friction)
        val reflected = when {
            normalSpeed >= 0.0 -> normalSpeed
            normalSpeed > -REST_VELOCITY -> 0.0
            else -> -normalSpeed * bounce
        }
        return Response(hit.center.add(hit.normal.scale(SURFACE_MARGIN)), tangent.add(hit.normal.scale(reflected)), hit)
    }
}
