package work.nekow.particledrawing.api

import work.nekow.particledrawing.animation.recipe.ParticleWorld
import work.nekow.particledrawing.animation.recipe.RecipeCollision
import work.nekow.particledrawing.math.Vec3

/** 按中心到附近方块碰撞面的距离淡出。 */
internal object SoftFade {
    const val MAX_DISTANCE = 4.0
    private val directions = arrayOf(
        Vec3(0.0, -1.0, 0.0), Vec3(0.0, 1.0, 0.0),
        Vec3(-1.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0),
        Vec3(0.0, 0.0, -1.0), Vec3(0.0, 0.0, 1.0),
    )

    fun alpha(world: ParticleWorld, position: Vec3, distance: Double): Float {
        if (distance <= 0.0) return 1f
        var closest = distance
        for (direction in directions) {
            val hit = world.cast(RecipeCollision.SOLID, position, position.add(direction.scale(closest))) ?: continue
            if (hit.inside) return 0f
            closest = minOf(closest, position.distanceTo(hit.position))
            if (closest <= 0.001) return 0f
        }
        val t = closest / distance
        return (t * t * (3.0 - 2.0 * t)).toFloat()
    }
}
