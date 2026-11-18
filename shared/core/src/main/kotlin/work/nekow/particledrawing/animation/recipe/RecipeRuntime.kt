package work.nekow.particledrawing.animation.recipe

import work.nekow.particledrawing.animation.emitter.DistanceAdvance
import work.nekow.particledrawing.animation.script.Reg
import work.nekow.particledrawing.api.ParticleEvent
import work.nekow.particledrawing.api.RecipeEmission
import work.nekow.particledrawing.api.ParticleRecipe
import work.nekow.particledrawing.api.ParticleSeed
import work.nekow.particledrawing.math.Vec3
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.translation

/**
 * 可在纯 JVM 验证的出生调度和逐粒子积分；渲染与网络只消费它的状态。
 *
 * 粒子状态存成一条定长寄存器：[Reg.X]..[Reg.LIGHT] 是属性，之后是 state 与本刻输入，
 * 再往后是编译出来的临时槽。配方能编译时走寄存器直算，编不过才回退解释器。
 */
class RecipeRuntime(
    var recipe: ParticleRecipe,
    val burst: Boolean,
    private val preferFast: Boolean = true,
    /** 碰撞查询；为 null 时即使配了碰撞也按自由运动处理（例如无世界的测试环境）。 */
    private val world: ParticleWorld? = null,
) {
    /**
     * 一颗粒子。热路径下推进一颗粒子只做几次数组读写，不再按名字查表、不再逐粒子分配。
     */
    class Particle(
        val index: Long,
        val seed: ParticleSeed,
        val lifetime: Int,
        var regs: DoubleArray,
        val slots: RecipeSlots,
    ) {
        var age = 0
        var alive = true
        var initialPosition: Vec3 = seed.position
        internal var birthFrame = RecipeFrame.IDENTITY
        internal var event: EventInput? = null
        internal var triggered = 0
        internal var previousWorldPosition: Vec3? = null

        /** 按字段名取当前值；命令、测试与慢路径用，热路径直接读 [regs]。 */
        fun value(name: String): Double = regs[slots.slot(name)]

        val x: Double get() = regs[Reg.X]
        val y: Double get() = regs[Reg.Y]
        val z: Double get() = regs[Reg.Z]
        val position: Vec3 get() = Vec3(regs[Reg.X], regs[Reg.Y], regs[Reg.Z])
    }

    internal data class Trigger(val rule: RecipeEmission, val input: EventInput)

    private var expressions = RecipeExpressions(recipe, preferFast)
    private var inputs = RecipeInputReader(expressions, world)
    private val triggers = arrayListOf<Trigger>()
    internal val events: List<Trigger> get() = triggers
    private val listens = BooleanArray(ParticleEvent.entries.size)
    private var distance = distanceAdvance(recipe)
    private var previousOrigin: Vec3? = null
    private val spawnPositions = arrayListOf<Vec3>()
    var ticks = 0L
        private set
    var cursor = 0L
        private set
    var emitting = true
    val particles = arrayListOf<Particle>()
    private val removed = arrayListOf<Particle>()
    internal val removedParticles: List<Particle> get() = removed
    val finished get() = !emitting && particles.isEmpty()

    /** 当前配方的槽位表；渲染端同步时按它直接读寄存器。 */
    val slots: RecipeSlots get() = expressions.slots

    /** 慢路径专用的复用表与校验槽位（只在配方编不过时用到）。 */
    private val attrMap = HashMap<String, Double>(32)
    private val motionOut = LinkedHashMap<String, Double>()
    private val birthOut = LinkedHashMap<String, Double>()
    internal var frame: RecipeFrame? = RecipeFrame.IDENTITY
        private set
    private var persistent: Map<String, Int> = persistentSlots()
    private var checkSlots: IntArray = intArrayOf()
    private var checkNames: Array<String> = arrayOf()

    init {
        requireCadence(recipe, burst)
        rebuildLayout()
    }

    fun update(next: ParticleRecipe) {
        next.validate()
        requireCadence(next, burst)
        requireLocalized(next.state.keys == recipe.state.keys) { translation("error.recipe.state_change") }
        val ordered = next.copy(state = recipe.state.keys.associateWith(next.state::getValue))
        val compiled = RecipeExpressions(ordered, preferFast)
        if (next.emissions != recipe.emissions) for (particle in particles) particle.triggered = 0
        if (recipe.spacing != ordered.spacing) {
            distance = distanceAdvance(ordered)
            previousOrigin = null
        }
        recipe = ordered
        expressions = compiled
        inputs = RecipeInputReader(compiled, world)
        rebuildLayout()
        // 新的临时槽可能更多：按需扩容已有粒子的寄存器
        val need = regSize()
        for (p in particles) {
            if (p.regs.size < need) p.regs = p.regs.copyOf(need)
        }
    }

    private fun rebuildLayout() {
        listens.fill(false)
        for (rule in recipe.emissions) listens[rule.event.ordinal] = true
        persistent = persistentSlots()
        val slots = expressions.slots
        val names = ArrayList<String>(slots.map.size)
        val indices = ArrayList<Int>(slots.map.size)
        for ((name, slot) in slots.map) {
            if (name in RecipeExpressions.INPUT_SLOTS) continue
            names += name
            indices += slot
        }
        checkSlots = indices.toIntArray()
        checkNames = names.toTypedArray()
    }

    private fun persistentSlots(): Map<String, Int> {
        val slots = expressions.slots.map
        val out = LinkedHashMap<String, Int>(slots.size)
        for ((name, slot) in slots) if (name !in RecipeExpressions.INPUT_SLOTS) out[name] = slot
        return out
    }

    /** 有没有锚点偏移公式。 */
    val hasOrbit: Boolean get() = expressions.hasOrbit

    /**
     * 求锚点偏移（格）：每 tick 一次，`t` 为发射器运行毫秒，按 partialTick 做帧内插值。
     * 结果写进调用方复用的 out[0..2]；没有公式返回 false。
     */
    fun orbitOffset(out: DoubleArray, partialTick: Float): Boolean {
        if (!expressions.hasOrbit) return false
        expressions.prepareAnchor(ticks * 50.0 + partialTick * 50.0)
        expressions.orbitInto(out)
        return true
    }

    /** 一颗粒子需要多少个寄存器：热路径含临时槽，慢路径只要固定槽。 */
    private fun regSize(): Int = expressions.fast?.regCount ?: (expressions.slots.map.values.max() + 1)

    /** 旧粒子先推进，再生成本刻的新粒子；新生粒子保留 age=0 的完整首帧。 */
    fun tick(allowBirth: Boolean = true) = tickInFrame(if (allowBirth) RecipeFrame.IDENTITY else null)

    internal fun tickInFrame(frame: RecipeFrame?) {
        this.frame = frame
        triggers.clear()
        val fast = expressions.fast
        if (fast != null) advanceFast(fast) else advanceSlow()
        collectRemoved()
        if (listens[ParticleEvent.DEATH.ordinal]) for (particle in removed) {
            trigger(ParticleEvent.DEATH, particle, frameOf(particle))
        }
        if (emitting) emitScheduled(frame)
        ticks++
    }

    private fun emitScheduled(frame: RecipeFrame?) {
        if (frame == null) {
            previousOrigin = null
            distance?.reset()
            return
        }
        val cadence = distance
        if (cadence == null) {
            if (ticks % recipe.interval == 0L) emitBatch(frame, recipe.rate)
            return
        }
        val previous = previousOrigin
        previousOrigin = frame.origin
        if (previous == null || previous.distanceToSqr(frame.origin) > MAX_EMISSION_STEP * MAX_EMISSION_STEP) {
            cadence.reset()
            return
        }

        spawnPositions.clear()
        cadence.advance(previous, frame.origin, spawnPositions, ParticleRecipe.MAX_RATE / recipe.rate)
        for (position in spawnPositions) emitBatch(frame.at(position), recipe.rate, frame)
    }

    private fun emitBatch(frame: RecipeFrame, attempts: Int, followerFrame: RecipeFrame = frame) {
        repeat(attempts) {
            if (!emitting || particles.size >= recipe.maxAlive) return
            val seed = recipe.seeds[(cursor % recipe.seeds.size).toInt()]
            spawn(seed, frame, followerFrame = followerFrame)
            cursor++
            if (burst && cursor >= recipe.seeds.size) emitting = false
        }
    }

    private fun distanceAdvance(recipe: ParticleRecipe) =
        recipe.spacing.takeIf { it > 0.0 }?.let(::DistanceAdvance)

    /** 在事件位置尝试一轮样本。调用方负责总预算，现存粒子照常推进。 */
    internal fun emitAt(frame: RecipeFrame, event: EventInput, budget: Int): Int {
        var attempts = 0
        for (seed in recipe.seeds) {
            if (attempts >= budget || particles.size >= recipe.maxAlive) break
            spawn(seed, frame, event)
            cursor++
            attempts++
        }
        return attempts
    }

    private fun spawn(
        seed: ParticleSeed, frame: RecipeFrame, event: EventInput? = null, followerFrame: RecipeFrame = frame,
    ) {
        val fast = expressions.fast
        val regs = fast?.newRegs() ?: DoubleArray(regSize())
        initRegs(regs, seed, cursor)
        val particle = Particle(cursor, seed, recipe.lifetime, regs, expressions.slots)
        particle.birthFrame = frame
        particle.event = event
        val accepted = if (fast != null) spawnFast(fast, particle) else spawnSlow(particle)
        if (!accepted) return
        if (world != null && recipe.follow && recipe.collision != RecipeCollision.NONE) {
            particle.previousWorldPosition = frame.position(particle.x, particle.y, particle.z)
        }
        if (listens[ParticleEvent.BIRTH.ordinal]) trigger(ParticleEvent.BIRTH, particle, frame)
        if (recipe.follow && followerFrame !== frame) {
            val worldPosition = frame.position(particle.x, particle.y, particle.z)
            val local = followerFrame.localPosition(worldPosition)
            regs[Reg.X] = local.x
            regs[Reg.Y] = local.y
            regs[Reg.Z] = local.z
            regs[RecipeExpressions.DX] = local.x
            regs[RecipeExpressions.DY] = local.y
            regs[RecipeExpressions.DZ] = local.z
            particle.initialPosition = local
        }
        particles += particle
    }

    private fun inputFrame(particle: Particle): RecipeFrame? =
        if (recipe.follow) frame else particle.birthFrame

    private fun trigger(
        kind: ParticleEvent, particle: Particle, space: RecipeFrame,
        surface: SurfaceHit? = null, incoming: Vec3? = null,
    ) {
        if (!listens[kind.ordinal]) return
        for ((index, rule) in recipe.emissions.withIndex()) {
            if (rule.event != kind || (rule.material != null && surface?.material != rule.material)) continue
            val bit = 1 shl index
            if (rule.once && particle.triggered and bit != 0) continue
            if (triggers.size < MAX_EVENTS_PER_TICK) {
                val position = surface?.position ?: space.position(particle.x, particle.y, particle.z)
                val r = particle.regs
                val velocity = incoming ?: space.velocity(Vec3(r[Reg.VX], r[Reg.VY], r[Reg.VZ]))
                triggers += Trigger(rule, EventInput(position, velocity, surface?.normal ?: Vec3.ZERO, surface?.material))
                particle.triggered = particle.triggered or bit
            }
            if (rule.removeParent) particle.alive = false
        }
    }

    // ── 热路径 ────────────────────────────────────────────────────────────────

    private fun advanceFast(fast: RecipeFast) {
        val program = fast.tick.program
        val stack = fast.tickStack
        val killSlot = fast.base
        val t = ticks * 50.0
        val n = recipe.seeds.size.toDouble()
        for (p in particles) {
            if (!p.alive) continue
            p.age++
            if (p.lifetime > 0 && p.age >= p.lifetime) { p.alive = false; continue }
            val r = p.regs
            r[Reg.N] = n
            r[Reg.T] = t
            r[RecipeExpressions.AGE] = p.age.toDouble()
            r[RecipeExpressions.PROGRESS] = if (p.lifetime > 0) p.age.toDouble() / p.lifetime else 0.0
            val x = p.x
            val y = p.y
            val z = p.z
            inputs.prepare(p, inputFrame(p))
            program.exec(r, stack, null)
            if (r[killSlot] != 0.0) { p.alive = false; continue }
            integrate(p, x, y, z, fast.writesX, fast.writesY, fast.writesZ)
            validateFast(p)
        }
    }

    private fun spawnFast(fast: RecipeFast, p: Particle): Boolean {
        val regs = p.regs
        inputs.prepare(p, p.birthFrame)
        fast.birth.program.exec(regs, fast.birthStack, null)
        validateFast(p)
        p.initialPosition = p.position
        regs[RecipeExpressions.DX] = p.initialPosition.x
        regs[RecipeExpressions.DY] = p.initialPosition.y
        regs[RecipeExpressions.DZ] = p.initialPosition.z
        // 出生后的筛选与销毁：where 在 base，kill 在 base+1
        inputs.prepare(p, p.birthFrame)
        fast.check.program.exec(regs, fast.checkStack, null)
        return regs[fast.base] != 0.0 && regs[fast.base + 1] == 0.0
    }

    private fun validateFast(p: Particle) {
        val r = p.regs
        val slots = checkSlots
        for (i in slots.indices) requireLocalized(r[slots[i]].isFinite()) { translation("error.expression.nonfinite_output", checkNames[i]) }
        requireLocalized(r[Reg.SC] >= 0.0) { translation("error.expression.scale") }
        requireLocalized(r[Reg.R] in 0.0..1.0) { translation("error.expression.red") }
        requireLocalized(r[Reg.G] in 0.0..1.0) { translation("error.expression.green") }
        requireLocalized(r[Reg.B] in 0.0..1.0) { translation("error.expression.blue") }
        requireLocalized(r[Reg.A] in 0.0..1.0) { translation("error.expression.alpha") }
        requireLocalized(r[Reg.LIGHT] in 0.0..15.0) { translation("error.expression.light") }
        requireLocalized(r[Reg.X].toFloat().isFinite() && r[Reg.Y].toFloat().isFinite() && r[Reg.Z].toFloat().isFinite()) {
            translation("error.render.position_range")
        }
        requireLocalized(r[Reg.SC].toFloat().isFinite()) { translation("error.render.scale_range") }
    }

    // ── 解释器回退路径 ────────────────────────────────────────────────────────

    private fun advanceSlow() {
        for (p in particles) {
            if (!p.alive) continue
            p.age++
            if (p.lifetime > 0 && p.age >= p.lifetime) { p.alive = false; continue }
            prepare(p)
            if (expressions.kills()) { p.alive = false; continue }
            val outputs = expressions.evaluateInto(expressions.motion, motionOut)
            val x = p.x
            val y = p.y
            val z = p.z
            storeOutputs(p, outputs)
            integrate(p, x, y, z, "x" in outputs, "y" in outputs, "z" in outputs)
            validateSlow(p)
        }
    }

    private fun spawnSlow(p: Particle): Boolean {
        val regs = p.regs
        prepare(p, p.birthFrame)
        storeOutputs(p, expressions.evaluateInto(expressions.birth, birthOut))
        validateSlow(p)
        p.initialPosition = p.position
        regs[RecipeExpressions.DX] = p.initialPosition.x
        regs[RecipeExpressions.DY] = p.initialPosition.y
        regs[RecipeExpressions.DZ] = p.initialPosition.z
        prepare(p, p.birthFrame)
        return expressions.accepts() && !expressions.kills()
    }

    internal fun frameOf(p: Particle): RecipeFrame = if (recipe.follow) frame ?: p.birthFrame else p.birthFrame

    /** 快慢路径用同一积分，显式写位置的轴仍参与碰撞。 */
    private fun integrate(
        p: Particle, oldX: Double, oldY: Double, oldZ: Double,
        writesX: Boolean, writesY: Boolean, writesZ: Boolean,
    ) {
        val r = p.regs
        r[Reg.VX] += r[RecipeExpressions.AX]
        r[Reg.VY] += r[RecipeExpressions.AY]
        r[Reg.VZ] += r[RecipeExpressions.AZ]
        if (!writesX) r[Reg.X] = oldX + r[Reg.VX]
        if (!writesY) r[Reg.Y] = oldY + r[Reg.VY]
        if (!writesZ) r[Reg.Z] = oldZ + r[Reg.VZ]

        val query = world ?: return
        val mode = recipe.collision
        if (mode == RecipeCollision.NONE) {
            p.previousWorldPosition = null
            return
        }
        if (recipe.follow && frame == null) {
            p.previousWorldPosition = null
            return
        }

        val space = frameOf(p)
        val from = p.previousWorldPosition ?: space.position(oldX, oldY, oldZ)
        val to = space.position(p.x, p.y, p.z)
        val velocity = space.velocity(Vec3(r[Reg.VX], r[Reg.VY], r[Reg.VZ]))
        val hit = CollisionMath.resolve(
            mode, query, from, to, velocity, recipe.bounce, recipe.friction, listens[ParticleEvent.CONTACT.ordinal],
        )
        p.previousWorldPosition = hit?.position ?: to
        if (hit == null) return
        val position = space.localPosition(hit.position)
        val localVelocity = space.localVelocity(hit.velocity)
        r[Reg.X] = position.x
        r[Reg.Y] = position.y
        r[Reg.Z] = position.z
        r[Reg.VX] = localVelocity.x
        r[Reg.VY] = localVelocity.y
        r[Reg.VZ] = localVelocity.z
        if (listens[ParticleEvent.CONTACT.ordinal]) {
            val displacement = to.subtract(from)
            if (displacement.dot(hit.surface.normal) < 0.0) {
                trigger(ParticleEvent.CONTACT, p, space, hit.surface, displacement)
            }
        }
    }

    private fun storeOutputs(p: Particle, outputs: Map<String, Double>) {
        val slots = expressions.slots.map
        for ((name, value) in outputs) p.regs[slots[name] ?: continue] = value
    }

    /** 把持久槽读进复用的 map，供解释器按名字取值。 */
    private fun loadPersistent(p: Particle) {
        attrMap.clear()
        for ((name, slot) in persistent) attrMap[name] = p.regs[slot]
    }

    private fun prepare(p: Particle, space: RecipeFrame? = inputFrame(p)) {
        loadPersistent(p)
        inputs.prepare(p, space)
        inputs.copyInputs(p, attrMap)
        expressions.prepare(
            attrMap,
            p.index.toDouble(), recipe.seeds.size.toDouble(), p.seed.u, ticks * 50.0, 1.0,
            p.age.toDouble(), p.lifetime.toDouble(),
            if (p.lifetime > 0) p.age.toDouble() / p.lifetime else 0.0,
            p.initialPosition.x, p.initialPosition.y, p.initialPosition.z,
            p.seed.position.x, p.seed.position.y, p.seed.position.z,
            recipe.variables,
        )
    }

    private fun validateSlow(p: Particle) {
        val r = p.regs
        val slots = checkSlots
        for (i in slots.indices) requireLocalized(r[slots[i]].isFinite()) { translation("error.expression.nonfinite_output", checkNames[i]) }
        requireLocalized(r[Reg.SC] >= 0.0) { translation("error.expression.scale") }
        requireLocalized(r[Reg.R] in 0.0..1.0) { translation("error.expression.red") }
        requireLocalized(r[Reg.G] in 0.0..1.0) { translation("error.expression.green") }
        requireLocalized(r[Reg.B] in 0.0..1.0) { translation("error.expression.blue") }
        requireLocalized(r[Reg.A] in 0.0..1.0) { translation("error.expression.alpha") }
        requireLocalized(r[Reg.LIGHT] in 0.0..15.0) { translation("error.expression.light") }
        requireLocalized(r[Reg.X].toFloat().isFinite() && r[Reg.Y].toFloat().isFinite() && r[Reg.Z].toFloat().isFinite()) {
            translation("error.render.position_range")
        }
        requireLocalized(r[Reg.SC].toFloat().isFinite()) { translation("error.render.scale_range") }
    }

    /** 写一颗粒子的初值：属性、本刻输入与 state（寄存器数组本身已清零）。 */
    private fun initRegs(regs: DoubleArray, seed: ParticleSeed, index: Long) {
        regs[Reg.X] = seed.position.x
        regs[Reg.Y] = seed.position.y
        regs[Reg.Z] = seed.position.z
        regs[Reg.R] = seed.color.r.toDouble()
        regs[Reg.G] = seed.color.g.toDouble()
        regs[Reg.B] = seed.color.b.toDouble()
        regs[Reg.A] = seed.color.a.toDouble()
        regs[Reg.SC] = 1.0
        regs[Reg.GLOW] = if (recipe.visual.glowing) 1.0 else 0.0
        regs[Reg.LIGHT] = recipe.visual.lightLevel.toDouble()
        regs[Reg.I] = index.toDouble()
        regs[Reg.N] = recipe.seeds.size.toDouble()
        regs[Reg.T] = ticks * 50.0
        regs[RecipeExpressions.U] = seed.u
        regs[RecipeExpressions.DT] = 1.0
        regs[RecipeExpressions.LIFE] = recipe.lifetime.toDouble()
        regs[RecipeExpressions.DX] = seed.position.x
        regs[RecipeExpressions.DY] = seed.position.y
        regs[RecipeExpressions.DZ] = seed.position.z
        regs[RecipeExpressions.SX] = seed.position.x
        regs[RecipeExpressions.SY] = seed.position.y
        regs[RecipeExpressions.SZ] = seed.position.z
        for ((name, value) in recipe.state) regs[expressions.slots.slot(name)] = value
    }

    /** 对选中的现存成员执行一次修改或删除，不改变以后出生的粒子。 */
    fun edit(predicate: String, changes: Map<String, String>, remove: Boolean): Int {
        val plan = recipe.copy(lets = recipe.lets, birth = changes, motion = emptyMap(), where = predicate, kill = "false")
        plan.validate()
        val edit = RecipeExpressions(plan)
        val editInputs = RecipeInputReader(edit, world)
        val selected = arrayListOf<Pair<Particle, Map<String, Double>>>()
        val map = HashMap<String, Double>(32)
        for (p in particles) {
            map.clear()
            for ((name, slot) in persistent) map[name] = p.regs[slot]
            editInputs.prepare(p, inputFrame(p))
            editInputs.copyInputs(p, map)
            edit.prepare(
                map,
                p.index.toDouble(), recipe.seeds.size.toDouble(), p.seed.u, ticks * 50.0, 1.0,
                p.age.toDouble(), p.lifetime.toDouble(),
                if (p.lifetime > 0) p.age.toDouble() / p.lifetime else 0.0,
                p.initialPosition.x, p.initialPosition.y, p.initialPosition.z,
                p.seed.position.x, p.seed.position.y, p.seed.position.z,
                recipe.variables,
            )
            if (edit.accepts()) selected += p to if (remove) emptyMap() else HashMap(edit.evaluate(edit.birth))
        }
        for ((p, outputs) in selected) {
            if (remove) {
                p.alive = false
            } else {
                storeOutputs(p, outputs)
                if ("x" in outputs || "y" in outputs || "z" in outputs) p.previousWorldPosition = null
                validateSlow(p)
            }
        }
        collectRemoved()
        return selected.size
    }

    private fun collectRemoved() {
        removed.clear()
        particles.removeAll {
            if (it.alive) false else removed.add(it)
        }
    }

    companion object {
        private const val MAX_EVENTS_PER_TICK = 64
        private const val MAX_EMISSION_STEP = 64.0

        internal fun requireCadence(recipe: ParticleRecipe, burst: Boolean) {
            requireLocalized(!burst || recipe.spacing == 0.0) { translation("error.recipe.distance_burst") }
        }

        /** 假定全部样本通过，计入存活上限和发射间隔，给服务端一个安全回收上界。 */
        fun retirementBudget(r: ParticleRecipe): Long {
            requireLocalized(r.lifetime > 0) { translation("error.recipe.unbounded_reclaim") }
            val batch = minOf(r.rate, r.maxAlive)
            return ((r.seeds.size.toLong() + batch - 1) / batch) * r.interval +
                ((r.seeds.size - 1L) / r.maxAlive + 1L) * r.lifetime + 4L
        }
    }
}
