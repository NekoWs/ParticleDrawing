package work.nekow.particledrawing.core.client

import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.animation.recipe.CollisionMath
import work.nekow.particledrawing.animation.recipe.RecipeFrame
import work.nekow.particledrawing.animation.recipe.RecipeRuntime
import work.nekow.particledrawing.api.RecipeAnchor
import work.nekow.particledrawing.api.RecipeEmission
import work.nekow.particledrawing.api.SubEmit
import work.nekow.particledrawing.core.network.ParticleRecipePayload
import work.nekow.particledrawing.core.network.RecipeOperation
import work.nekow.particledrawing.i18n.component
import work.nekow.particledrawing.i18n.diagnostic
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.translation
import work.nekow.particledrawing.platform.toCore
import work.nekow.particledrawing.platform.toMinecraft
import work.nekow.particledrawing.util.AttachMath
import java.util.UUID

/** 声明式采样器的客户端桥接；所有积分按游戏刻推进，暂停时不推进。 */
internal object ClientParticleSources {
    private class Member(val id: UUID, var position: Vec3, val visual: ResolvedVisual) {
        var r = 0f
        var g = 0f
        var b = 0f
        var scale = 1f
    }
    private class Entry(val runtime: RecipeRuntime, var anchor: RecipeAnchor) {
        val members = linkedMapOf<RecipeRuntime.Particle, Member>()
        val children = linkedMapOf<RecipeEmission, Entry>()

        fun particleIds(): List<UUID> = members.values.map { it.id } + children.values.flatMap(Entry::particleIds)
    }
    private val sources = linkedMapOf<UUID, Entry>()

    fun handle(p: ParticleRecipePayload) {
        try {
            when (p.operation) {
                RecipeOperation.START -> {
                    remove(p.id)
                    val recipe = requireNotNull(p.recipe)
                    recipe.validate()
                    sources[p.id] = Entry(RecipeRuntime(recipe, p.burst, world = ClientParticleWorld), requireNotNull(p.anchor))
                }
                RecipeOperation.UPDATE -> sources[p.id]?.runtime?.update(requireNotNull(p.recipe))
                RecipeOperation.ANCHOR -> sources[p.id]?.anchor = requireNotNull(p.anchor)
                RecipeOperation.VARIABLE -> sources[p.id]?.runtime?.let { it.update(it.recipe.copy(variables = it.recipe.variables + p.variables)) }
                RecipeOperation.STOP -> sources[p.id]?.runtime?.emitting = false
                RecipeOperation.REMOVE -> remove(p.id)
                RecipeOperation.EDIT -> sources[p.id]?.let {
                    it.runtime.edit(p.predicate, p.changes, p.deleteSelected)
                    sync(it, emitChildren = false)
                }
            }
        } catch (e: RuntimeException) { remove(p.id); error(p.id, e) }
    }

    fun tick() {
        var childBudget = CHILD_ATTEMPTS_PER_TICK
        for ((id, e) in sources.toList()) {
            try {
                e.runtime.tickInFrame(resolve(e))
                sync(e)
                for (child in e.children.values) child.runtime.tickInFrame(RecipeFrame.IDENTITY)
                for ((rule, input) in e.runtime.events) {
                    if (childBudget <= 0) break
                    val child = e.children.getOrPut(rule) {
                        Entry(RecipeRuntime(rule.child, burst = true, world = ClientParticleWorld), e.anchor).also {
                            it.runtime.emitting = false
                        }
                    }
                    val origin = input.position.add(input.normal.scale(CollisionMath.SURFACE_MARGIN))
                    childBudget -= child.runtime.emitAt(RecipeFrame(origin), input, childBudget)
                }
                for (child in e.children.values) sync(child, allowCapacityDrop = true)
                e.children.values.removeIf { it.runtime.finished }
                if (e.runtime.finished && e.children.isEmpty()) sources.remove(id)
            } catch (ex: RuntimeException) { remove(id); error(id, ex) }
        }
    }

    private val posOut = DoubleArray(3)

    private fun sync(e: Entry, emitChildren: Boolean = true, allowCapacityDrop: Boolean = false) {
        val engine = ClientParticleEngine.instance() ?: return
        val frame = e.runtime.frame
        val hidden = e.runtime.recipe.follow && frame == null
        val subEmit = e.runtime.recipe.subEmit
        val dead = if (e.runtime.removedParticles.isEmpty()) emptyList() else {
            e.runtime.removedParticles.mapNotNull { particle ->
                val member = e.members.remove(particle) ?: return@mapNotNull null
                particle to member
            }
        }
        if (dead.isNotEmpty()) engine.destroyParticles(dead.map { (_, member) -> member.id }.toTypedArray())

        // 槽位在配方编译期固定，循环外取一次即可。
        // 每颗每刻仍会新建一个 Vec3 与一个 Color：渲染粒子会持有这两个对象做插值端点，
        // 要再省下去得把 RenderParticle 的位置/颜色换成基本类型字段。
        val slots = e.runtime.slots
        val sR = slots.slot("r"); val sG = slots.slot("g"); val sB = slots.slot("b"); val sA = slots.slot("a")
        val sSc = slots.slot("sc"); val sGlow = slots.slot("glow"); val sLight = slots.slot("light")
        for (p in e.runtime.particles) {
            var member = e.members[p]
            if (member == null && frame == null) continue
            val position = if (hidden && member != null) member.position else {
                e.runtime.frameOf(p).positionInto(p.x, p.y, p.z, posOut)
                Vec3(posOut[0], posOut[1], posOut[2])
            }
            if (member == null) {
                val id = UUID.randomUUID()
                val look = ResolvedVisual.of(e.runtime.recipe.visual, 1f)
                engine.spawnParticle(id, position.x, position.y, position.z,
                    p.regs[sR].toFloat(), p.regs[sG].toFloat(), p.regs[sB].toFloat(), p.regs[sA].toFloat(),
                    p.regs[sSc].toFloat(), p.lifetime, null, p.regs[sGlow] != 0.0, p.regs[sLight].toInt(), look, e.runtime.recipe.lifeCurve)
                if (!engine.containsParticle(id)) {
                    if (allowCapacityDrop) {
                        p.alive = false
                        continue
                    }
                    requireLocalized(false) { translation("error.recipe.client_limit") }
                }
                engine.markSpawnedDuringRender(id, 0f)
                member = Member(id, position, look)
                e.members[p] = member
            }
            member.position = position
            member.r = p.regs[sR].toFloat()
            member.g = p.regs[sG].toFloat()
            member.b = p.regs[sB].toFloat()
            member.scale = p.regs[sSc].toFloat()
            val alpha = if (hidden) 0f else p.regs[sA].toFloat()
            engine.applyRecipeFrame(member.id, position, member.r, member.g, member.b, alpha,
                member.scale, p.regs[sGlow] != 0.0, p.regs[sLight].toInt(), member.visual)
        }

        // 优先补主发射器的成员，余下的容量再给子粒子。
        if (emitChildren && subEmit != null) for ((particle, member) in dead) {
            burst(engine, e, particle, member, subEmit)
        }
    }

    private val orbitOut = DoubleArray(3)

    private fun resolve(e: Entry): RecipeFrame? {
        val a = e.anchor
        val entity = if (a.entity == null) null else {
            val level = Minecraft.getInstance().level ?: return null
            level.getEntity(a.entityId)?.takeIf { it.uuid == a.entity }
                ?: level.entitiesForRendering().firstOrNull { it.uuid == a.entity } ?: return null
        }
        val origin = if (entity == null) a.position.add(a.offset) else
            AttachMath.resolve(entity.position(), entity.yRot, entity.xRot, a.offset.toMinecraft(), a.local).toCore()
        val position = if (e.runtime.orbitOffset(orbitOut, 0f)) origin.add(orbitOut[0], orbitOut[1], orbitOut[2]) else origin
        return RecipeFrame(position, entity?.yRot ?: 0f, entity?.xRot ?: 0f, entity != null && a.local)
    }

    private fun error(id: UUID, e: RuntimeException) {
        org.apache.logging.log4j.LogManager.getLogger("ParticleDrawing").error(translation("log.recipe.instance").resolve(), id, e.message)
        val detail = e.diagnostic()
        val message = if (detail == null) translation("error.recipe.stopped")
            else translation("error.recipe.stopped_detail", detail)
        Minecraft.getInstance().player?.sendSystemMessage(message.component())
    }

    private fun remove(id: UUID) {
        val e = sources.remove(id) ?: return
        ClientParticleEngine.instance()?.destroyParticles(e.particleIds().toTypedArray())
    }

    fun clear() = sources.clear()

    private const val CHILD_ATTEMPTS_PER_TICK = 256

    private fun burst(engine: ClientParticleEngine, e: Entry, p: RecipeRuntime.Particle, member: Member, sub: SubEmit) {
        val pos = member.position.toCore()
        val scale = (member.scale * sub.scale).toFloat()
        sub.forEachVelocity(p.index, e.runtime.ticks) { velocity ->
            engine.spawnLocalEmitterParticle(
                pos, velocity, member.r, member.g, member.b, 1f, scale, sub.lifetime, false, 0, member.visual,
            )
        }
    }
}
