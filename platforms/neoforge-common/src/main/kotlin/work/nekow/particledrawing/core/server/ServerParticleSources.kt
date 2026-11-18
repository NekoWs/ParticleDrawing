package work.nekow.particledrawing.core.server

import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.PacketDistributor
import work.nekow.particledrawing.api.ParticleRecipe
import work.nekow.particledrawing.api.RecipeAnchor
import work.nekow.particledrawing.animation.recipe.RecipeRuntime
import work.nekow.particledrawing.core.network.ParticleRecipePayload
import work.nekow.particledrawing.core.network.RecipeOperation
import work.nekow.particledrawing.util.ParticleUtils
import java.util.UUID
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.requireNotNullLocalized
import work.nekow.particledrawing.i18n.translation

/** 服务端主线程登记生成声明；客户端自行执行采样和运动，无逐颗逐刻广播。 */
internal object ServerParticleSources {
    private class Entry(val dimension: UUID, var recipe: ParticleRecipe, var anchor: RecipeAnchor, val burst: Boolean) {
        val sent = hashSetOf<UUID>()
        var emitting = true
        var ticks = 0L
        var maxLifetime = recipe.lifetime
        var retireAt = if (burst && recipe.lifetime > 0) RecipeRuntime.retirementBudget(recipe) else Long.MAX_VALUE

        fun updated(next: ParticleRecipe) {
            maxLifetime = if (maxLifetime == -1 || next.lifetime == -1) -1 else maxOf(maxLifetime, next.lifetime)
            if (burst && maxLifetime > 0) retireAt = ticks + RecipeRuntime.retirementBudget(next.copy(lifetime = maxLifetime))
        }
    }
    private val entries = linkedMapOf<UUID, Entry>()

    fun start(level: ServerLevel, recipe: ParticleRecipe, anchor: RecipeAnchor, burst: Boolean): UUID {
        recipe.validate()
        RecipeRuntime.requireCadence(recipe, burst)
        requireLocalized(entries.size < 256) { translation("error.recipe.active_limit") }
        val id = UUID.randomUUID()
        val entry = Entry(ParticleUtils.dimensionUUID(level), freeze(recipe), anchor, burst)
        entries[id] = entry
        for (player in level.players()) sync(id, entry, player, true)
        return id
    }

    fun update(id: UUID, recipe: ParticleRecipe, level: ServerLevel) {
        recipe.validate()
        val e = requireNotNullLocalized(entries[id]) { translation("error.recipe.ended") }
        RecipeRuntime.requireCadence(recipe, e.burst)
        requireLocalized(e.recipe.state.keys == recipe.state.keys) { translation("error.recipe.state_change") }
        e.updated(recipe)
        e.recipe = freeze(recipe)
        send(e, level, ParticleRecipePayload(id, RecipeOperation.UPDATE, recipe = e.recipe))
    }

    fun anchor(id: UUID, anchor: RecipeAnchor, level: ServerLevel) {
        val e = requireNotNullLocalized(entries[id]) { translation("error.recipe.ended") }
        e.anchor = anchor
        send(e, level, ParticleRecipePayload(id, RecipeOperation.ANCHOR, anchor = anchor))
    }

    fun variable(id: UUID, name: String, value: Double, level: ServerLevel) {
        val e = requireNotNullLocalized(entries[id]) { translation("error.recipe.ended") }
        val next = e.recipe.copy(variables = e.recipe.variables + (name to value))
        next.validate()
        e.recipe = next
        send(e, level, ParticleRecipePayload(id, RecipeOperation.VARIABLE, variables = mapOf(name to value)))
    }

    fun stop(id: UUID, level: ServerLevel, remove: Boolean) {
        val e = entries[id] ?: return
        e.emitting = false
        if (e.maxLifetime > 0) e.retireAt = e.ticks + e.maxLifetime + 2L
        send(e, level, ParticleRecipePayload(id, if (remove) RecipeOperation.REMOVE else RecipeOperation.STOP))
        if (remove) entries.remove(id)
    }

    fun edit(id: UUID, predicate: String, changes: Map<String, String>, remove: Boolean, level: ServerLevel) {
        val e = requireNotNullLocalized(entries[id]) { translation("error.recipe.ended") }
        e.recipe.copy(birth = changes, where = predicate).validate()
        send(e, level, ParticleRecipePayload(id, RecipeOperation.EDIT, predicate = predicate, changes = changes, deleteSelected = remove))
    }

    fun active(id: UUID) = id in entries

    private fun send(e: Entry, level: ServerLevel, payload: ParticleRecipePayload) {
        for (player in level.players()) if (player.uuid in e.sent) PacketDistributor.sendToPlayer(player, payload)
    }

    private fun sync(id: UUID, e: Entry, player: ServerPlayer, initial: Boolean = false) {
        if ((e.burst && !initial) || e.dimension != ParticleUtils.dimensionUUID(player.level()) || !e.emitting || !e.sent.add(player.uuid)) return
        PacketDistributor.sendToPlayer(player, ParticleRecipePayload(id, RecipeOperation.START, e.recipe, e.anchor, e.burst))
    }

    fun sync(player: ServerPlayer) { for ((id, e) in entries) sync(id, e, player) }
    fun forget(player: UUID) { entries.values.forEach { it.sent.remove(player) } }
    fun tick(server: MinecraftServer) {
        val expired = arrayListOf<UUID>()
        for ((id, e) in entries) if (++e.ticks >= e.retireAt) expired += id
        for (id in expired) {
            val e = entries.remove(id) ?: continue
            val level = server.allLevels.firstOrNull { ParticleUtils.dimensionUUID(it) == e.dimension } ?: continue
            send(e, level, ParticleRecipePayload(id, RecipeOperation.STOP))
        }
        for (player in server.playerList.players) sync(player)
    }
    fun discard(dimension: UUID) { entries.entries.removeIf { it.value.dimension == dimension } }
    fun clear() = entries.clear()

    private fun freeze(r: ParticleRecipe): ParticleRecipe = r.copy(seeds = r.seeds.toList(), visual = r.visual.copy(),
        variables = LinkedHashMap(r.variables), state = LinkedHashMap(r.state), lets = LinkedHashMap(r.lets),
        birth = LinkedHashMap(r.birth), motion = LinkedHashMap(r.motion),
        emissions = r.emissions.map { it.copy(child = freeze(it.child)) })

}
