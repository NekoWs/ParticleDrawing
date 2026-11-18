package work.nekow.particledrawing.core.client

import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.animation.recipe.CollisionMath
import work.nekow.particledrawing.animation.recipe.RecipeFrame
import work.nekow.particledrawing.animation.recipe.RecipeRuntime
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.api.RecipeAnchor
import work.nekow.particledrawing.api.RecipeEmission
import work.nekow.particledrawing.api.SubEmit
import work.nekow.particledrawing.core.network.ParticleRecipePayload
import work.nekow.particledrawing.core.network.RecipeOperation
import work.nekow.particledrawing.i18n.component
import work.nekow.particledrawing.i18n.diagnostic
import work.nekow.particledrawing.i18n.localizedError
import work.nekow.particledrawing.i18n.translation
import work.nekow.particledrawing.platform.toCore
import java.util.UUID

/** 声明式采样器的客户端桥接；所有积分按游戏刻推进，暂停时不推进。 */
internal object ClientParticleSources {
    private class Member(
        val binding: ClientParticleEngine.RecipeBinding, var position: Vec3, var color: Color, var scale: Float,
    ) {
        val id: UUID get() = binding.particle.id()
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

        val slots = e.runtime.slots
        val sR = slots.slot("r"); val sG = slots.slot("g"); val sB = slots.slot("b"); val sA = slots.slot("a")
        val sSc = slots.slot("sc"); val sGlow = slots.slot("glow"); val sLight = slots.slot("light")
        for (p in e.runtime.particles) {
            var member = e.members[p]
            if (member == null && frame == null) continue
            val position = if (hidden && member != null) member.position else {
                e.runtime.frameOf(p).positionInto(p.x, p.y, p.z, posOut)
                val previous = member?.position
                if (previous != null && previous.x == posOut[0] && previous.y == posOut[1] && previous.z == posOut[2]) previous
                else Vec3(posOut[0], posOut[1], posOut[2])
            }
            val r = p.regs[sR].toFloat(); val g = p.regs[sG].toFloat(); val b = p.regs[sB].toFloat()
            val alpha = if (hidden) 0f else p.regs[sA].toFloat()
            val scale = p.regs[sSc].toFloat()
            val glow = p.regs[sGlow] != 0.0
            val light = p.regs[sLight].toInt()
            val previousColor = member?.color
            val color = if (previousColor != null && previousColor.r == r && previousColor.g == g &&
                previousColor.b == b && previousColor.a == alpha) previousColor else Color.of(r, g, b, alpha)
            if (member == null) {
                val id = UUID.randomUUID()
                val look = ResolvedVisual.of(e.runtime.recipe.visual, 1f)
                val event = p.event
                val decal = if (event != null && event.decalRadius > 0.0) {
                    ParticleModelRenderer.decal(position.toCore(), event.normal, event.decalRadius)
                } else null
                if (event != null && event.decalRadius > 0.0 && decal == null) {
                    p.alive = false
                    continue
                }
                engine.spawnParticle(id, position.x, position.y, position.z, r, g, b, alpha,
                    scale, p.lifetime, null, glow, light, look, e.runtime.recipe.lifeCurve, decal = decal)
                val binding = engine.bindRecipeParticle(id, look)
                if (binding == null) {
                    if (allowCapacityDrop) {
                        p.alive = false
                        continue
                    }
                    localizedError(translation("error.recipe.client_limit"))
                }
                member = Member(binding, position, color, scale)
                e.members[p] = member
            }
            member.position = position
            member.color = color
            member.scale = scale
            engine.applyRecipeFrame(member.binding, position, color, scale, glow, light)
        }

        // 优先补主发射器的成员，余下的容量再给子粒子。
        if (emitChildren && subEmit != null) for ((particle, member) in dead) {
            burst(engine, e, particle, member, subEmit)
        }
    }

    private val orbitOut = DoubleArray(3)

    private fun resolve(e: Entry): RecipeFrame? {
        val frame = resolveRecipeAnchor(e.anchor) ?: return null
        if (!e.runtime.orbitOffset(orbitOut, 0f)) return frame
        return frame.at(frame.origin.add(orbitOut[0], orbitOut[1], orbitOut[2]))
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
                pos, velocity, member.color.r, member.color.g, member.color.b, 1f,
                scale, sub.lifetime, false, 0, member.binding.visual,
            )
        }
    }
}
