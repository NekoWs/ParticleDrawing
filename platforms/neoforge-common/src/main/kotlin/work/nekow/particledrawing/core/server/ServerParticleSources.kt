package work.nekow.particledrawing.core.server

import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.PacketDistributor
import org.apache.logging.log4j.LogManager
import work.nekow.particledrawing.api.ParticleRecipe
import work.nekow.particledrawing.api.ParticleRecipeMode
import work.nekow.particledrawing.api.RecipeAnchor
import work.nekow.particledrawing.animation.recipe.RecipeRuntime
import work.nekow.particledrawing.animation.recipe.ParticleRecipeExecution
import work.nekow.particledrawing.animation.recipe.SyncedRecipe
import work.nekow.particledrawing.core.network.ParticleRecipePayload
import work.nekow.particledrawing.core.network.RecipeOperation
import work.nekow.particledrawing.util.ParticleUtils
import java.util.UUID
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.requireNotNullLocalized
import work.nekow.particledrawing.i18n.translation

/** 服务端主线程管理配方，按选择下发运行声明或同步服务端模拟状态。 */
internal object ServerParticleSources {
    private class Entry(
        val dimension: UUID, var recipe: ParticleRecipe, var anchor: RecipeAnchor, val burst: Boolean,
        val synced: ServerSyncedRecipe?, val mode: ParticleRecipeMode,
    ) {
        val sent = hashSetOf<UUID>()
        var emitting = true
        var ticks = 0L
        var maxLifetime = recipe.lifetime
        var maxTail = RecipeRuntime.detachedTrailBudget(recipe)
        var retireAt = if (burst && recipe.lifetime > 0) RecipeRuntime.retirementBudget(recipe) + maxTail else Long.MAX_VALUE

        fun updated(next: ParticleRecipe) {
            maxLifetime = if (maxLifetime == -1 || next.lifetime == -1) -1 else maxOf(maxLifetime, next.lifetime)
            maxTail = maxOf(maxTail, RecipeRuntime.detachedTrailBudget(next))
            if (burst && maxLifetime > 0) retireAt = ticks + RecipeRuntime.retirementBudget(next.copy(lifetime = maxLifetime)) + maxTail
        }
    }
    private val entries = linkedMapOf<UUID, Entry>()
    private val logger = LogManager.getLogger("ParticleDrawing")
    private const val MAX_SYNCED_SOURCES = 8

    fun start(level: ServerLevel, recipe: ParticleRecipe, anchor: RecipeAnchor, burst: Boolean, synced: Boolean = false,
              mode: ParticleRecipeMode = ParticleRecipeMode.STANDARD): UUID {
        recipe.validate()
        RecipeRuntime.requireCadence(recipe, burst)
        ParticleRecipeExecution.validate(recipe, anchor, burst, mode)
        requireLocalized(entries.size < 256) { translation("error.recipe.active_limit") }
        if (synced) {
            SyncedRecipe.validate(recipe)
            requireLocalized(entries.values.count { it.synced != null } < MAX_SYNCED_SOURCES) {
                translation("error.recipe.synced_sources", MAX_SYNCED_SOURCES)
            }
        }
        val id = UUID.randomUUID()
        val frozen = freeze(recipe)
        val serverRecipe = if (synced) ServerSyncedRecipe(id, level, frozen, burst) else null
        val entry = Entry(ParticleUtils.dimensionUUID(level), frozen, anchor, burst, serverRecipe, mode)
        entries[id] = entry
        for (player in level.players()) sync(id, entry, player, true)
        return id
    }

    fun update(id: UUID, recipe: ParticleRecipe, level: ServerLevel) {
        recipe.validate()
        val e = requireNotNullLocalized(entries[id]) { translation("error.recipe.ended") }
        requireMutable(e)
        RecipeRuntime.requireCadence(recipe, e.burst)
        ParticleRecipeExecution.validate(recipe, e.anchor, e.burst, e.mode)
        requireLocalized(e.recipe.state.keys == recipe.state.keys) { translation("error.recipe.state_change") }
        val frozen = freeze(recipe)
        if (e.synced != null) {
            SyncedRecipe.validate(frozen)
            e.synced.simulation.runtime.update(frozen)
        }
        e.updated(frozen)
        e.recipe = frozen
        send(e, level, ParticleRecipePayload(id, RecipeOperation.UPDATE, recipe = e.recipe))
    }

    fun anchor(id: UUID, anchor: RecipeAnchor, level: ServerLevel) {
        val e = requireNotNullLocalized(entries[id]) { translation("error.recipe.ended") }
        requireMutable(e)
        e.anchor = anchor
        send(e, level, ParticleRecipePayload(id, RecipeOperation.ANCHOR, anchor = anchor))
    }

    fun variable(id: UUID, name: String, value: Double, level: ServerLevel) {
        val e = requireNotNullLocalized(entries[id]) { translation("error.recipe.ended") }
        requireMutable(e)
        val next = e.recipe.copy(variables = e.recipe.variables + (name to value))
        next.validate()
        e.synced?.simulation?.runtime?.update(next)
        e.recipe = next
        send(e, level, ParticleRecipePayload(id, RecipeOperation.VARIABLE, variables = mapOf(name to value)))
    }

    fun stop(id: UUID, level: ServerLevel, remove: Boolean) {
        val e = entries[id] ?: return
        if (e.synced != null) {
            if (remove) {
                entries.remove(id)
                e.synced.close()
            } else e.synced.simulation.runtime.emitting = false
            return
        }
        e.emitting = false
        if (e.maxLifetime > 0) e.retireAt = e.ticks + e.maxLifetime + e.maxTail + 2L
        send(e, level, ParticleRecipePayload(id, if (remove) RecipeOperation.REMOVE else RecipeOperation.STOP))
        if (remove) entries.remove(id)
    }

    fun edit(id: UUID, predicate: String, changes: Map<String, String>, remove: Boolean, level: ServerLevel) {
        val e = requireNotNullLocalized(entries[id]) { translation("error.recipe.ended") }
        requireMutable(e)
        val synced = e.synced
        if (synced != null) {
            synced.simulation.edit(predicate, changes, remove)
            return
        }
        e.recipe.copy(birth = changes, where = predicate).validate()
        send(e, level, ParticleRecipePayload(id, RecipeOperation.EDIT, predicate = predicate, changes = changes, deleteSelected = remove))
    }

    fun active(id: UUID) = id in entries

    private fun send(e: Entry, level: ServerLevel, payload: ParticleRecipePayload) {
        for (player in level.players()) if (player.uuid in e.sent) PacketDistributor.sendToPlayer(player, payload)
    }

    private fun sync(id: UUID, e: Entry, player: ServerPlayer, initial: Boolean = false) {
        if (e.synced != null || (e.burst && !initial) || e.dimension != ParticleUtils.dimensionUUID(player.level()) ||
            !e.emitting || !e.sent.add(player.uuid)) return
        PacketDistributor.sendToPlayer(player, ParticleRecipePayload(id, RecipeOperation.START, e.recipe, e.anchor, e.burst, mode = e.mode))
    }

    fun sync(player: ServerPlayer) { for ((id, e) in entries) sync(id, e, player) }
    fun forgetSynced(player: UUID) { entries.values.forEach { it.synced?.forget(player) } }
    fun forget(player: UUID) {
        entries.values.forEach { it.sent.remove(player) }
        forgetSynced(player)
    }

    fun tick(server: MinecraftServer) {
        val expired = arrayListOf<UUID>()
        for ((id, e) in entries) {
            val synced = e.synced
            if (synced == null) {
                if (++e.ticks >= e.retireAt) expired += id
                continue
            }
            try {
                synced.tick(e.anchor)
                if (synced.simulation.finished) expired += id
            } catch (error: RuntimeException) {
                logger.error(translation("log.recipe.instance").resolve(), id, error.message)
                expired += id
            }
        }
        for (id in expired) {
            val e = entries.remove(id) ?: continue
            if (e.synced != null) {
                e.synced.close()
                continue
            }
            val level = server.allLevels.firstOrNull { ParticleUtils.dimensionUUID(it) == e.dimension } ?: continue
            send(e, level, ParticleRecipePayload(id, RecipeOperation.STOP))
        }
        for (player in server.playerList.players) sync(player)
    }
    fun discard(dimension: UUID) {
        entries.values.filter { it.dimension == dimension }.forEach { it.synced?.close() }
        entries.entries.removeIf { it.value.dimension == dimension }
    }
    fun clear() = entries.clear()

    private fun requireMutable(entry: Entry) =
        requireLocalized(entry.mode != ParticleRecipeMode.GPU) { translation("error.recipe.gpu_immutable") }

    private fun freeze(r: ParticleRecipe): ParticleRecipe = r.copy(seeds = r.seeds.toList(), visual = r.visual.copy(),
        variables = LinkedHashMap(r.variables), state = LinkedHashMap(r.state), lets = LinkedHashMap(r.lets),
        birth = LinkedHashMap(r.birth), motion = LinkedHashMap(r.motion),
        probes = r.probes.toList(), emissions = r.emissions.map { it.copy(child = freeze(it.child)) })

}
