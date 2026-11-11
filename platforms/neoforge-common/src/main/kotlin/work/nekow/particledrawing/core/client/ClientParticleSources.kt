package work.nekow.particledrawing.core.client

import work.nekow.particledrawing.platform.toMinecraft


import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.animation.recipe.RecipeRuntime
import work.nekow.particledrawing.api.RecipeAnchor
import work.nekow.particledrawing.core.network.ParticleRecipePayload
import work.nekow.particledrawing.core.network.RecipeOperation
import work.nekow.particledrawing.util.AttachMath
import java.util.UUID

/** 声明式采样器的客户端桥接；所有积分按游戏刻推进，暂停时不推进。 */
internal object ClientParticleSources {
    private data class Frame(val pos: Vec3, val yaw: Float = 0f, val pitch: Float = 0f, val local: Boolean = false) {
        fun apply(v: Vec3) = AttachMath.resolve(pos, yaw, pitch, v, local)
    }
    private class Member(val id: UUID, val frame: Frame, val visual: ResolvedVisual)
    private class Entry(val runtime: RecipeRuntime, var anchor: RecipeAnchor) {
        val members = linkedMapOf<RecipeRuntime.Particle, Member>()
    }
    private val sources = linkedMapOf<UUID, Entry>()

    fun handle(p: ParticleRecipePayload) {
        try {
            when (p.operation) {
                RecipeOperation.START -> {
                    remove(p.id)
                    val recipe = requireNotNull(p.recipe)
                    recipe.validate()
                    sources[p.id] = Entry(RecipeRuntime(recipe, p.burst), requireNotNull(p.anchor))
                }
                RecipeOperation.UPDATE -> sources[p.id]?.runtime?.update(requireNotNull(p.recipe))
                RecipeOperation.ANCHOR -> sources[p.id]?.anchor = requireNotNull(p.anchor)
                RecipeOperation.VARIABLE -> sources[p.id]?.runtime?.let { it.update(it.recipe.copy(variables = it.recipe.variables + p.variables)) }
                RecipeOperation.STOP -> sources[p.id]?.runtime?.emitting = false
                RecipeOperation.REMOVE -> remove(p.id)
                RecipeOperation.EDIT -> sources[p.id]?.let { it.runtime.edit(p.predicate, p.changes, p.deleteSelected); sync(it, resolve(it.anchor)) }
            }
        } catch (e: RuntimeException) { remove(p.id); error(p.id, e) }
    }

    fun tick() {
        for ((id, e) in sources.toList()) {
            try {
                val frame = resolve(e.anchor)
                e.runtime.tick(frame != null)
                sync(e, frame)
                if (e.runtime.finished) sources.remove(id)
            } catch (ex: RuntimeException) { remove(id); error(id, ex) }
        }
    }

    // 逐 tick 复用：别为每颗粒子攒一份哈希集/数组，几千颗 ×20tps 就是几十万次分配
    private val livingSet: MutableSet<RecipeRuntime.Particle> =
        java.util.Collections.newSetFromMap(java.util.IdentityHashMap())
    private val deadIds = ArrayList<UUID>()

    private fun sync(e: Entry, frame: Frame?) {
        val engine = ClientParticleEngine.instance() ?: return
        // 反向清理：members 里 runtime 已不再持有的粒子
        livingSet.clear()
        for (p in e.runtime.particles) livingSet.add(p)
        val dead = e.members.entries.iterator()
        while (dead.hasNext()) {
            val entry = dead.next()
            if (entry.key !in livingSet) {
                deadIds.add(entry.value.id)
                dead.remove()
            }
        }
        if (deadIds.isNotEmpty()) {
            engine.destroyParticles(deadIds.toTypedArray())
            deadIds.clear()
        }
        for (p in e.runtime.particles) {
            var member = e.members[p]
            if (member == null) {
                if (frame == null) continue
                val id = UUID.randomUUID()
                val v = p.attributes
                val look = ResolvedVisual.of(e.runtime.recipe.visual, 1f)
                val pos = frame.apply(p.position.toMinecraft())
                engine.spawnParticle(id, pos.x, pos.y, pos.z,
                    v.getValue("r").toFloat(), v.getValue("g").toFloat(), v.getValue("b").toFloat(), v.getValue("a").toFloat(),
                    v.getValue("sc").toFloat(), p.lifetime, null, v.getValue("glow") != 0.0, v.getValue("light").toInt(), look, e.runtime.recipe.lifeCurve)
                require(engine.containsParticle(id)) { "客户端粒子数量已达上限，生成器已停止；清理后 remove/start 重试" }
                engine.markSpawnedDuringRender(id, 0f)
                member = Member(id, frame, look)
                e.members[p] = member
            }
            val v = p.attributes
            val current = if (e.runtime.recipe.follow) frame ?: member.frame else member.frame
            val pos = current.apply(p.position.toMinecraft())
            val a = if (e.runtime.recipe.follow && frame == null) 0f else v.getValue("a").toFloat()
            engine.applyRecipeFrame(member.id, pos, v.getValue("r").toFloat(), v.getValue("g").toFloat(), v.getValue("b").toFloat(), a,
                v.getValue("sc").toFloat(), v.getValue("glow") != 0.0, v.getValue("light").toInt(), member.visual)
        }
    }

    private fun resolve(a: RecipeAnchor): Frame? {
        if (a.entity == null) return Frame(a.position.add(a.offset).toMinecraft())
        val level = Minecraft.getInstance().level ?: return null
        val entity = level.getEntity(a.entityId)?.takeIf { it.uuid == a.entity }
            ?: level.entitiesForRendering().firstOrNull { it.uuid == a.entity } ?: return null
        return Frame(AttachMath.resolve(entity.position(), entity.yRot, entity.xRot, a.offset.toMinecraft(), a.local), entity.yRot, entity.xRot, a.local)
    }

    private fun error(id: UUID, e: RuntimeException) {
        org.apache.logging.log4j.LogManager.getLogger("ParticleDrawing").error("粒子生成器 {}: {}", id, e.message)
        Minecraft.getInstance().player?.sendSystemMessage(Component.literal("PD 粒子生成器已停止并清理当前实例: ${e.message}；修正后 remove/start 重试"))
    }

    private fun remove(id: UUID) {
        val e = sources.remove(id) ?: return
        ClientParticleEngine.instance()?.destroyParticles(e.members.values.map { it.id }.toTypedArray())
    }

    fun clear() = sources.clear()
}
