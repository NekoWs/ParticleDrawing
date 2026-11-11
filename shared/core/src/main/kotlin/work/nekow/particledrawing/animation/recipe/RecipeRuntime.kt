package work.nekow.particledrawing.animation.recipe

import work.nekow.particledrawing.animation.script.Reg
import work.nekow.particledrawing.api.ParticleRecipe
import work.nekow.particledrawing.api.ParticleSeed
import work.nekow.particledrawing.math.Vec3

/**
 * 可在纯 JVM 验证的出生调度和逐粒子积分；渲染与网络只消费它的状态。
 *
 * 粒子状态存成一条定长寄存器：[Reg.X]..[Reg.LIGHT] 是属性，之后是 state 与本刻输入，
 * 再往后是编译出来的临时槽。配方能编译时走寄存器直算，编不过才回退解释器。
 */
class RecipeRuntime(var recipe: ParticleRecipe, val burst: Boolean) {
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

        /** 按字段名取当前值；命令、测试与慢路径用，热路径直接读 [regs]。 */
        fun value(name: String): Double = regs[slots.slot(name)]

        val x: Double get() = regs[Reg.X]
        val y: Double get() = regs[Reg.Y]
        val z: Double get() = regs[Reg.Z]
        val position: Vec3 get() = Vec3(regs[Reg.X], regs[Reg.Y], regs[Reg.Z])
    }

    private var expressions = RecipeExpressions(recipe)
    var ticks = 0L
        private set
    var cursor = 0L
        private set
    var emitting = true
    val particles = arrayListOf<Particle>()
    val finished get() = !emitting && particles.isEmpty()

    /** 当前配方的槽位表；渲染端同步时按它直接读寄存器。 */
    val slots: RecipeSlots get() = expressions.slots

    /** 慢路径专用的复用表与校验槽位（只在配方编不过时用到）。 */
    private val attrMap = HashMap<String, Double>(32)
    private val motionOut = LinkedHashMap<String, Double>()
    private val birthOut = LinkedHashMap<String, Double>()
    private var persistent: Map<String, Int> = persistentSlots()
    private var checkSlots: IntArray = intArrayOf()
    private var checkNames: Array<String> = arrayOf()

    init {
        rebuildLayout()
    }

    fun update(next: ParticleRecipe) {
        next.validate()
        require(next.state.keys == recipe.state.keys) { "运行中不能增删 state 字段；请停止后重新启动" }
        val compiled = RecipeExpressions(next)
        recipe = next
        expressions = compiled
        rebuildLayout()
        // 新的临时槽可能更多：按需扩容已有粒子的寄存器
        val need = regSize()
        for (p in particles) {
            if (p.regs.size < need) p.regs = p.regs.copyOf(need)
        }
    }

    private fun rebuildLayout() {
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

    /** 一颗粒子需要多少个寄存器：热路径含临时槽，慢路径只要固定槽。 */
    private fun regSize(): Int = expressions.fast?.regCount ?: (expressions.slots.map.values.max() + 1)

    /** 旧粒子先推进，再生成本刻的新粒子；新生粒子保留 age=0 的完整首帧。 */
    fun tick(allowBirth: Boolean = true) {
        val fast = expressions.fast
        if (fast != null) advanceFast(fast) else advanceSlow()
        particles.removeAll { !it.alive }
        if (emitting && allowBirth && ticks % recipe.interval == 0L) {
            var attempt = 0
            while (attempt < recipe.rate) {
                if (!emitting || particles.size >= recipe.maxAlive) break
                val seed = recipe.seeds[(cursor % recipe.seeds.size).toInt()]
                val p = if (fast != null) spawnFast(fast, seed) else spawnSlow(seed)
                if (p != null) particles += p
                cursor++
                if (burst && cursor >= recipe.seeds.size) emitting = false
                attempt++
            }
        }
        ticks++
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
            program.exec(r, stack, null)
            if (r[killSlot] != 0.0) { p.alive = false; continue }
            val ax = r[RecipeExpressions.AX]
            val ay = r[RecipeExpressions.AY]
            val az = r[RecipeExpressions.AZ]
            val vx = r[Reg.VX] + ax
            val vy = r[Reg.VY] + ay
            val vz = r[Reg.VZ] + az
            r[Reg.VX] = vx
            r[Reg.VY] = vy
            r[Reg.VZ] = vz
            if (!fast.writesX) r[Reg.X] += vx
            if (!fast.writesY) r[Reg.Y] += vy
            if (!fast.writesZ) r[Reg.Z] += vz
            validateFast(p)
        }
    }

    private fun spawnFast(fast: RecipeFast, seed: ParticleSeed): Particle? {
        val regs = fast.newRegs()
        initRegs(regs, seed, cursor)
        val p = Particle(cursor, seed, recipe.lifetime, regs, expressions.slots)
        fast.birth.program.exec(regs, fast.birthStack, null)
        validateFast(p)
        p.initialPosition = p.position
        regs[RecipeExpressions.DX] = p.initialPosition.x
        regs[RecipeExpressions.DY] = p.initialPosition.y
        regs[RecipeExpressions.DZ] = p.initialPosition.z
        // 出生后的筛选与销毁：where 在 base，kill 在 base+1
        fast.check.program.exec(regs, fast.checkStack, null)
        if (regs[fast.base] == 0.0 || regs[fast.base + 1] != 0.0) return null
        return p
    }

    private fun validateFast(p: Particle) {
        val r = p.regs
        val slots = checkSlots
        for (i in slots.indices) require(r[slots[i]].isFinite()) { "粒子公式产生了非有限数（${checkNames[i]}）" }
        require(r[Reg.SC] >= 0.0) { "sc 不能为负" }
        require(r[Reg.R] in 0.0..1.0) { "r 需要在 0..1" }
        require(r[Reg.G] in 0.0..1.0) { "g 需要在 0..1" }
        require(r[Reg.B] in 0.0..1.0) { "b 需要在 0..1" }
        require(r[Reg.A] in 0.0..1.0) { "a 需要在 0..1" }
        require(r[Reg.LIGHT] in 0.0..15.0) { "light 需要在 0..15" }
        require(r[Reg.X].toFloat().isFinite() && r[Reg.Y].toFloat().isFinite() && r[Reg.Z].toFloat().isFinite()) {
            "坐标超出渲染数值范围"
        }
        require(r[Reg.SC].toFloat().isFinite()) { "sc 超出渲染数值范围" }
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
            storeOutputs(p, outputs)
            integrateSlow(p, outputs)
            validateSlow(p)
        }
    }

    private fun spawnSlow(seed: ParticleSeed): Particle? {
        val regs = DoubleArray(regSize())
        initRegs(regs, seed, cursor)
        val p = Particle(cursor, seed, recipe.lifetime, regs, expressions.slots)
        prepare(p)
        storeOutputs(p, expressions.evaluateInto(expressions.birth, birthOut))
        validateSlow(p)
        p.initialPosition = p.position
        regs[RecipeExpressions.DX] = p.initialPosition.x
        regs[RecipeExpressions.DY] = p.initialPosition.y
        regs[RecipeExpressions.DZ] = p.initialPosition.z
        prepare(p)
        if (!expressions.accepts() || expressions.kills()) return null
        return p
    }

    /** 一条轴的速度积分：先叠加加速度，再在公式没直接写位置时按速度推进。 */
    private fun integrateSlow(p: Particle, outputs: Map<String, Double>) {
        val r = p.regs
        val vx = r[Reg.VX] + r[RecipeExpressions.AX]
        val vy = r[Reg.VY] + r[RecipeExpressions.AY]
        val vz = r[Reg.VZ] + r[RecipeExpressions.AZ]
        r[Reg.VX] = vx; r[Reg.VY] = vy; r[Reg.VZ] = vz
        if ("x" !in outputs) r[Reg.X] += vx
        if ("y" !in outputs) r[Reg.Y] += vy
        if ("z" !in outputs) r[Reg.Z] += vz
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

    private fun prepare(p: Particle) {
        loadPersistent(p)
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
        for (i in slots.indices) require(r[slots[i]].isFinite()) { "粒子公式产生了非有限数（${checkNames[i]}）" }
        require(r[Reg.SC] >= 0.0) { "sc 不能为负" }
        require(r[Reg.R] in 0.0..1.0) { "r 需要在 0..1" }
        require(r[Reg.G] in 0.0..1.0) { "g 需要在 0..1" }
        require(r[Reg.B] in 0.0..1.0) { "b 需要在 0..1" }
        require(r[Reg.A] in 0.0..1.0) { "a 需要在 0..1" }
        require(r[Reg.LIGHT] in 0.0..15.0) { "light 需要在 0..15" }
        require(r[Reg.X].toFloat().isFinite() && r[Reg.Y].toFloat().isFinite() && r[Reg.Z].toFloat().isFinite()) {
            "坐标超出渲染数值范围"
        }
        require(r[Reg.SC].toFloat().isFinite()) { "sc 超出渲染数值范围" }
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
        val selected = arrayListOf<Pair<Particle, Map<String, Double>>>()
        val map = HashMap<String, Double>(32)
        for (p in particles) {
            map.clear()
            for ((name, slot) in persistent) map[name] = p.regs[slot]
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
                validateSlow(p)
            }
        }
        particles.removeAll { !it.alive }
        return selected.size
    }

    companion object {
        /** 假定全部样本通过，计入存活上限和发射间隔，给服务端一个安全回收上界。 */
        fun retirementBudget(r: ParticleRecipe): Long {
            require(r.lifetime > 0) { "无限寿命不能推算回收时间" }
            val batch = minOf(r.rate, r.maxAlive)
            return ((r.seeds.size.toLong() + batch - 1) / batch) * r.interval +
                ((r.seeds.size - 1L) / r.maxAlive + 1L) * r.lifetime + 4L
        }
    }
}
