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

/** 各生成器按服务器刻播放，桥接粒子按渲染帧采样相邻两刻。 */
internal object ClientSyncedRecipes {
    private data class Sample(val row: RecipeStateRow, val look: RecipeLook)
    private data class Frame(val tick: Long, val samples: List<Sample>)

    private class Entry {
        var tick = -1L
        var receivedTick = -1L
        var lastEmptyTick = -1L
        var held = false
        val pending = TickFrameQueue<Frame>(capacity = 4, reserve = 1)
        val looks = hashMapOf<Long, RecipeLook>()
        val members = linkedMapOf<Long, Member>()
        val bindings = hashMapOf<Long, ClientParticleEngine.RecipeBinding>()
        var appliedTick = -1L
    }

    private class Member(val look: RecipeLook, var row: RecipeStateRow) {
        val history = look.visual.trailTicks.takeIf { it > 0 }?.let { RibbonHistory(it, row.position) }

        fun accept(next: RecipeStateRow, existing: Boolean) {
            if (next.trailAge >= 0) {
                requireLocalized(history != null && next.trailAge < look.visual.trailStyle.lingerTicks &&
                    next.light == 0 && (next.history.isNotEmpty() || existing)) {
                    translation("error.packet.recipe_state")
                }
            }
            if (next.history.isNotEmpty()) {
                requireLocalized(history != null && next.history.size <= look.visual.trailTicks + 1) {
                    translation("error.packet.recipe_state")
                }
                checkNotNull(history).restore(next.history)
            } else if (existing && next.trailAge < 0) history?.record(next.position)
            row = next
        }
    }

    /** 客户端同时跟踪的同步配方条目上限；只发 Look 不发帧的异常流不能无限堆。 */
    private const val MAX_ENTRIES = 256

    private val entries = linkedMapOf<UUID, Entry>()

    fun receive(payload: ParticleRecipeStatePayload) {
        try {
            if (payload.id !in entries && entries.size >= MAX_ENTRIES) {
                entries.keys.firstOrNull()?.let(entries::remove)
            }
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
        if (frame.tick <= entry.receivedTick) return
        val looks = hashSetOf<Long>()
        val samples = frame.rows.map { row ->
            looks += row.lookId
            Sample(row, requireNotNullLocalized(entry.looks[row.lookId]) { translation("error.packet.recipe_state") })
        }
        entry.looks.keys.retainAll(looks)
        entry.receivedTick = frame.tick
        if (samples.isEmpty()) entry.lastEmptyTick = frame.tick
        // 跳过上屏的帧仍留下路径点和首次入场时的完整尾迹。
        entry.pending.offer(Frame(frame.tick, samples))?.let { acceptFrame(entry, it) }
    }

    private fun acceptFrame(entry: Entry, frame: Frame) {
        val present = hashSetOf<Long>()
        for ((row, look) in frame.samples) {
            val existing = entry.members[row.id]
            val member = existing ?: Member(look, row)
            requireLocalized(member.row.lookId == row.lookId) { translation("error.packet.recipe_state") }
            member.accept(row, existing != null)
            entry.members[row.id] = member
            present += row.id
        }
        entry.members.keys.retainAll(present)
        entry.tick = frame.tick
    }

    fun tick(engine: ClientParticleEngine) {
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val (id, entry) = iterator.next()
            try {
                entry.pending.advance(drain = entry.lastEmptyTick > entry.tick)?.let { acceptFrame(entry, it) }
                sync(engine, entry)
                if (entry.looks.isEmpty() && entry.members.isEmpty() && entry.pending.size == 0) iterator.remove()
            } catch (e: RuntimeException) {
                iterator.remove()
                destroy(engine, entry)
                ClientParticleSources.error(id, e)
            }
        }
    }

    private fun sync(engine: ClientParticleEngine, entry: Entry) {
        val changed = entry.tick != entry.appliedTick
        val skipped = entry.appliedTick >= 0 && entry.tick - entry.appliedTick > 1
        if (changed) {
            val dead = entry.bindings.keys.filter { it !in entry.members }
            if (dead.isNotEmpty()) engine.destroyParticles(dead.mapNotNull { entry.bindings.remove(it)?.particle?.id() }.toTypedArray())
        }
        for ((id, member) in entry.members) {
            val previous = entry.bindings[id]?.takeIf { engine.containsParticle(it.particle.id()) }
            if (previous != null && !changed) {
                if (!entry.held) engine.holdSyncedRecipeFrame(previous)
                continue
            }
            val binding = previous ?: spawn(engine, member) ?: continue
            entry.bindings[id] = binding
            val row = member.row
            binding.particle.syncRecipeAge(row.age, snap = skipped || previous == null)
            engine.applyRecipeFrame(binding, row.position.toMinecraft(), row.color, row.scale, row.glowing, row.light)
            if (member.history != null) {
                if (previous == null || skipped || row.history.isNotEmpty() || row.trailAge >= 0) binding.bridge?.syncTrail(member.history)
                else binding.bridge?.recordTrail()
            }
            if (row.trailAge >= 0 && binding.bridge != null) {
                requireLocalized(binding.bridge.detachTrail(row.trailAge)) { translation("error.packet.recipe_state") }
            }
            if (skipped) engine.holdSyncedRecipeFrame(binding)
        }
        entry.held = !changed || skipped
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
        ClientParticleEngine.instance()?.let { destroy(it, entry) }
    }

    private fun destroy(engine: ClientParticleEngine, entry: Entry) =
        engine.destroyParticles(entry.bindings.values.map { it.particle.id() }.toTypedArray())

    fun clear() = entries.clear()
}
