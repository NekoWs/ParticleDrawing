package work.nekow.particledrawing.core.client

import work.nekow.particledrawing.animation.recipe.RecipeLook
import work.nekow.particledrawing.core.network.ParticleRecipeStatePayload
import work.nekow.particledrawing.core.network.ParticleRecipeStatePayload.Content
import work.nekow.particledrawing.core.network.RecipeStateRow
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.requireNotNullLocalized
import work.nekow.particledrawing.i18n.translation
import work.nekow.particledrawing.platform.toMinecraft
import work.nekow.particledrawing.render.RibbonHistory
import java.util.UUID

/** 接收每刻的路径点，渲染时只把最新状态落到现有粒子引擎。 */
internal object ClientSyncedRecipes {
    private class Entry {
        var tick = -1L
        val looks = hashMapOf<Long, RecipeLook>()
        val members = linkedMapOf<Long, Member>()
        val bindings = hashMapOf<Long, ClientParticleEngine.RecipeBinding>()
        var appliedTick = -1L
    }

    private class Member(val look: RecipeLook, var row: RecipeStateRow) {
        val history = look.visual.trailTicks.takeIf { it > 0 }?.let { RibbonHistory(it, row.position) }

        fun accept(next: RecipeStateRow, existing: Boolean) {
            if (next.history.isNotEmpty()) {
                requireLocalized(history != null && next.history.size <= look.visual.trailTicks + 1) {
                    translation("error.packet.recipe_state")
                }
                checkNotNull(history).restore(next.history)
            } else if (existing) history?.record(next.position)
            row = next
        }
    }

    private val entries = linkedMapOf<UUID, Entry>()

    fun receive(payload: ParticleRecipeStatePayload) {
        try {
            val entry = entries.getOrPut(payload.id, ::Entry)
            when (val content = payload.content) {
                is Content.Look -> entry.looks[content.id] = content.look
                is Content.Frame -> receiveFrame(entry, content)
            }
        } catch (e: RuntimeException) {
            remove(payload.id)
            ClientParticleSources.error(payload.id, e)
        }
    }

    private fun receiveFrame(entry: Entry, frame: Content.Frame) {
        if (frame.tick <= entry.tick) return
        val present = hashSetOf<Long>()
        val looks = hashSetOf<Long>()
        for (row in frame.rows) {
            val existing = entry.members[row.id]
            val member = existing ?: Member(
                requireNotNullLocalized(entry.looks[row.lookId]) { translation("error.packet.recipe_state") }, row,
            )
            requireLocalized(member.row.lookId == row.lookId) { translation("error.packet.recipe_state") }
            member.accept(row, existing != null)
            entry.members[row.id] = member
            present += row.id
            looks += row.lookId
        }
        entry.members.keys.retainAll(present)
        entry.looks.keys.retainAll(looks)
        entry.tick = frame.tick
    }

    fun tick(engine: ClientParticleEngine) {
        for ((id, entry) in entries.toList()) {
            try {
                sync(engine, entry)
            } catch (e: RuntimeException) {
                remove(id)
                ClientParticleSources.error(id, e)
            }
        }
    }

    private fun sync(engine: ClientParticleEngine, entry: Entry) {
        val changed = entry.tick != entry.appliedTick
        if (changed) {
            val dead = entry.bindings.keys.filter { it !in entry.members }
            if (dead.isNotEmpty()) engine.destroyParticles(dead.mapNotNull { entry.bindings.remove(it)?.particle?.id() }.toTypedArray())
        }
        for ((id, member) in entry.members) {
            val previous = entry.bindings[id]?.takeIf { engine.containsParticle(it.particle.id()) }
            if (previous != null && !changed) continue
            val binding = previous ?: spawn(engine, member) ?: continue
            entry.bindings[id] = binding
            member.history?.let { binding.bridge?.syncTrail(it) }
            val row = member.row
            binding.particle.syncRecipeAge(row.age)
            engine.applyRecipeFrame(binding, row.position.toMinecraft(), row.color, row.scale, row.glowing, row.light)
        }
        entry.appliedTick = entry.tick
    }

    private fun spawn(engine: ClientParticleEngine, member: Member): ClientParticleEngine.RecipeBinding? {
        val row = member.row
        val visual = ResolvedVisual.of(member.look.visual, 1f)
        val decal = row.decal?.let {
            ParticleModelRenderer.decal(row.position, it.position, it.normal, it.radius, it.wrap) ?: return null
        }
        val id = UUID.randomUUID()
        val p = row.position
        val color = row.color
        val history = member.history
        val previous = if (history != null && history.size > 1) history.sample(1).toMinecraft() else null
        engine.spawnParticle(id, p.x, p.y, p.z, color.r, color.g, color.b, color.a,
            row.scale, row.lifetime, null, row.glowing, row.light, visual, member.look.curve, prev = previous, decal = decal)
        return engine.bindSyncedRecipeParticle(id, visual)
    }

    fun remove(id: UUID) {
        val entry = entries.remove(id) ?: return
        ClientParticleEngine.instance()?.destroyParticles(entry.bindings.values.map { it.particle.id() }.toTypedArray())
    }

    fun clear() = entries.clear()
}
