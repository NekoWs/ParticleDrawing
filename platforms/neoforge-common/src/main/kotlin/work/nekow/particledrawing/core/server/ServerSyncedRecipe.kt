package work.nekow.particledrawing.core.server

import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.PacketDistributor
import work.nekow.particledrawing.animation.recipe.RecipeLook
import work.nekow.particledrawing.animation.recipe.SyncedRecipe
import work.nekow.particledrawing.api.ParticleRecipe
import work.nekow.particledrawing.api.RecipeAnchor
import work.nekow.particledrawing.core.network.ParticleRecipePayload
import work.nekow.particledrawing.core.network.ParticleRecipeStatePayload
import work.nekow.particledrawing.core.network.ParticleRecipeStatePayload.Content
import work.nekow.particledrawing.core.network.RecipeOperation
import work.nekow.particledrawing.core.network.RecipeStateRow
import work.nekow.particledrawing.math.Vec3
import java.util.UUID

/** 每刻发布在视距内的状态，第一次看到成员时附带它已走过的尾迹。 */
internal class ServerSyncedRecipe(
    private val id: UUID, private val level: ServerLevel, recipe: ParticleRecipe, burst: Boolean,
) {
    private val world = ServerRecipeWorld(level)
    val simulation = SyncedRecipe(recipe, burst, world)

    private data class Visible(val particles: Set<Long>, val looks: Set<Long>)
    private val visible = hashMapOf<UUID, Visible>()
    private val looks = hashMapOf<RecipeLook, Long>()
    private var nextLookId = 0L

    fun tick(anchor: RecipeAnchor) {
        world.beginTick()
        simulation.tick(world.anchorFrame(anchor))
        publish()
    }

    private fun publish() {
        val players = level.players()
        if (players.isEmpty()) {
            visible.clear()
            looks.clear()
            return
        }
        val frame = simulation.snapshot(includeHistory = false)
        val activeLooks = frame.particles.mapTo(hashSetOf()) { it.look }
        looks.keys.retainAll(activeLooks)
        val trails by lazy(simulation::activeTrails)
        val histories by lazy { hashMapOf<Long, List<Vec3>>() }
        for (player in players) {
            val previous = visible[player.uuid]
            val samples = frame.particles.filter { particle ->
                val pos = particle.position
                ParticleVisibilityManager.isWithinViewDistance(player, pos.x, pos.y, pos.z)
            }
            if (samples.isEmpty()) {
                if (visible.remove(player.uuid) != null) {
                    PacketDistributor.sendToPlayer(player, ParticleRecipeStatePayload(id, Content.Frame(frame.tick, emptyList())))
                }
                continue
            }
            val particleIds = hashSetOf<Long>()
            val lookIds = hashSetOf<Long>()
            val rows = samples.map { sample ->
                val lookId = looks.getOrPut(sample.look) { nextLookId++ }
                if (lookIds.add(lookId) && previous?.looks?.contains(lookId) != true) {
                    PacketDistributor.sendToPlayer(player, ParticleRecipeStatePayload(id, Content.Look(lookId, sample.look)))
                }
                particleIds += sample.id
                val history = if (sample.look.visual.trailTicks > 0 && previous?.particles?.contains(sample.id) != true) {
                    histories.getOrPut(sample.id) { trails[sample.id]?.oldestFirst().orEmpty() }
                } else emptyList()
                RecipeStateRow(sample, lookId, history)
            }
            visible[player.uuid] = Visible(particleIds, lookIds)
            PacketDistributor.sendToPlayer(player, ParticleRecipeStatePayload(id, Content.Frame(frame.tick, rows)))
        }
    }

    fun forget(player: UUID) { visible.remove(player) }

    fun close() {
        for (player in level.players()) if (player.uuid in visible) sendRemove(player)
        visible.clear()
    }

    private fun sendRemove(player: ServerPlayer) =
        PacketDistributor.sendToPlayer(player, ParticleRecipePayload(id, RecipeOperation.REMOVE))
}
