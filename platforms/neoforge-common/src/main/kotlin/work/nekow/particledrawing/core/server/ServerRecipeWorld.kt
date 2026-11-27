package work.nekow.particledrawing.core.server

import net.minecraft.server.level.ServerLevel
import work.nekow.particledrawing.animation.recipe.RecipeFrame
import work.nekow.particledrawing.api.ParticleForceFields
import work.nekow.particledrawing.api.RecipeAnchor
import work.nekow.particledrawing.core.MinecraftParticleWorld
import work.nekow.particledrawing.core.ParticleFieldSamples
import work.nekow.particledrawing.core.recipeFrame
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.translation
import work.nekow.particledrawing.math.Vec3

internal class ServerRecipeWorld(private val serverLevel: ServerLevel) : MinecraftParticleWorld() {
    private val fieldSamples = ParticleFieldSamples()
    private var fieldsReady = false
    private var queries = 0

    override fun level() = serverLevel

    fun beginTick() {
        fieldsReady = false
        queries = 0
    }

    fun anchorFrame(anchor: RecipeAnchor): RecipeFrame? {
        val entity = anchor.entity?.let { serverLevel.getEntity(it) ?: return null }
        return recipeFrame(anchor, entity)
    }

    override fun fieldAcceleration(position: Vec3, tick: Long): Vec3 {
        if (!fieldsReady) {
            fieldSamples.refresh(ParticleForceFields.placements(serverLevel), ::anchorFrame)
            fieldsReady = true
        }
        return fieldSamples.accelerationAt(position, tick)
    }

    override fun checkSweep(from: Vec3, to: Vec3) {
        requireLocalized(from.distanceToSqr(to) <= MAX_SWEEP * MAX_SWEEP) {
            translation("error.recipe.synced_sweep", MAX_SWEEP)
        }
    }

    override fun countQuery() {
        requireLocalized(++queries <= MAX_QUERIES) { translation("error.recipe.synced_queries", MAX_QUERIES) }
    }

    private companion object {
        const val MAX_SWEEP = 1024.0
        const val MAX_QUERIES = 1_048_576
    }
}
