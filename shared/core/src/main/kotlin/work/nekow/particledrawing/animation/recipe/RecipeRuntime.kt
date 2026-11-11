package work.nekow.particledrawing.animation.recipe

import work.nekow.particledrawing.math.Vec3
import work.nekow.particledrawing.api.ParticleRecipe
import work.nekow.particledrawing.api.ParticleSeed

/** 可在纯 JVM 验证的出生调度和逐粒子积分；渲染与网络只消费它的状态。 */
class RecipeRuntime(var recipe: ParticleRecipe, val burst: Boolean) {
    class Particle(val index: Long, val seed: ParticleSeed, val lifetime: Int, val attributes: MutableMap<String, Double>) {
        var age = 0
        var alive = true
        var initialPosition = seed.position
        val position get() = Vec3(attributes.getValue("x"), attributes.getValue("y"), attributes.getValue("z"))
    }

    private var expressions = RecipeExpressions(recipe)
    var ticks = 0L
        private set
    var cursor = 0L
        private set
    var emitting = true
    val particles = arrayListOf<Particle>()
    val finished get() = !emitting && particles.isEmpty()

    fun update(next: ParticleRecipe) {
        next.validate()
        require(next.state.keys == recipe.state.keys) { "运行中不能增删 state 字段；请停止后重新启动" }
        val compiled = RecipeExpressions(next)
        recipe = next
        expressions = compiled
    }

    /** 逐粒子逐 tick 复用的输出表与积分临时量（不逐粒子新建）。 */
    private val motionOut = LinkedHashMap<String, Double>()
    private val birthOut = LinkedHashMap<String, Double>()

    /** 旧粒子先推进，再生成本刻的新粒子；新生粒子保留 age=0 的完整首帧。 */
    fun tick(allowBirth: Boolean = true) {
        for (p in particles) {
            if (!p.alive) continue
            p.age++
            if (p.lifetime > 0 && p.age >= p.lifetime) { p.alive = false; continue }
            prepare(p)
            if (expressions.kills()) { p.alive = false; continue }
            val outputs = expressions.evaluateInto(expressions.motion, motionOut)
            val a = p.attributes
            a.putAll(outputs)
            integrateAxis(a, outputs, "x", "vx", "ax")
            integrateAxis(a, outputs, "y", "vy", "ay")
            integrateAxis(a, outputs, "z", "vz", "az")
            validateAttributes(p)
        }
        particles.removeAll { !it.alive }
        if (emitting && allowBirth && ticks % recipe.interval == 0L) {
            var attempt = 0
            while (attempt < recipe.rate) {
                if (!emitting || particles.size >= recipe.maxAlive) break
                val seed = recipe.seeds[(cursor % recipe.seeds.size).toInt()]
                val a = defaults(seed)
                val p = Particle(cursor++, seed, recipe.lifetime, a)
                prepare(p)
                p.attributes.putAll(expressions.evaluateInto(expressions.birth, birthOut))
                validateAttributes(p)
                p.initialPosition = p.position
                prepare(p)
                if (expressions.accepts() && !expressions.kills()) particles += p
                if (burst && cursor >= recipe.seeds.size) emitting = false
                attempt++
            }
        }
        ticks++
    }

    /** 一条轴的速度积分：先叠加加速度，再在公式没直接写位置时按速度推进。 */
    private fun integrateAxis(
        a: MutableMap<String, Double>,
        outputs: Map<String, Double>,
        pos: String, vel: String, accel: String,
    ) {
        val speed = a.getValue(vel) + a.getValue(accel)
        a[vel] = speed
        if (!outputs.containsKey(pos)) a[pos] = a.getValue(pos) + speed
    }

    /** 对选中的现存成员执行一次修改或删除，不改变以后出生的粒子。 */
    fun edit(predicate: String, changes: Map<String, String>, remove: Boolean): Int {
        val plan = recipe.copy(lets = recipe.lets, birth = changes, motion = emptyMap(), where = predicate, kill = "false")
        plan.validate()
        val edit = RecipeExpressions(plan)
        val selected = arrayListOf<Pair<Particle, Map<String, Double>>>()
        for (p in particles) {
            prepareInto(edit, p)
            if (edit.accepts()) selected += p to if (remove) emptyMap() else HashMap(edit.evaluate(edit.birth))
        }
        for ((p, outputs) in selected) {
            if (remove) p.alive = false else { p.attributes.putAll(outputs); validateAttributes(p) }
        }
        particles.removeAll { !it.alive }
        return selected.size
    }

    private fun defaults(s: ParticleSeed) = linkedMapOf(
        "x" to s.position.x, "y" to s.position.y, "z" to s.position.z,
        "vx" to 0.0, "vy" to 0.0, "vz" to 0.0, "ax" to 0.0, "ay" to 0.0, "az" to 0.0,
        "r" to s.color.r.toDouble(), "g" to s.color.g.toDouble(), "b" to s.color.b.toDouble(), "a" to s.color.a.toDouble(),
        "sc" to 1.0, "glow" to if (recipe.visual.glowing) 1.0 else 0.0, "light" to recipe.visual.lightLevel.toDouble(),
    ).also { it.putAll(recipe.state) }

    /**
     * 把粒子当前属性与本刻输入填进求值表。
     *
     * 不先拷一份 `inputs` 再拷进求值表：逐粒子逐 tick 都调，多一次几十项的 map 分配
     * 就是几百万次/秒的垃圾。这里单趟写进 [RecipeExpressions] 复用的那张表。
     */
    private fun prepare(p: Particle) = prepareInto(expressions, p)

    /** 把同一颗粒子的属性与输入填进指定求值器（正常 tick 与条件编辑共用）。 */
    private fun prepareInto(expr: RecipeExpressions, p: Particle) = expr.prepare(
        p.attributes,
        p.index.toDouble(), recipe.seeds.size.toDouble(), p.seed.u, ticks * 50.0, 1.0,
        p.age.toDouble(), p.lifetime.toDouble(),
        if (p.lifetime > 0) p.age.toDouble() / p.lifetime else 0.0,
        p.initialPosition.x, p.initialPosition.y, p.initialPosition.z,
        p.seed.position.x, p.seed.position.y, p.seed.position.z,
        recipe.variables,
    )

    private fun validateAttributes(p: Particle) {
        // 逐粒子每 tick 都跑：用普通循环，别用 all{} 这类会建迭代器/闭包的高阶写法
        for (v in p.attributes.values) require(v.isFinite()) { "粒子公式产生了非有限数" }
        require(p.attributes.getValue("sc") >= 0.0) { "sc 不能为负" }
        for (name in COLOR_KEYS) {
            val c = p.attributes.getValue(name)
            require(c in 0.0..1.0) { "$name 需要在 0..1" }
        }
        require(p.attributes.getValue("light") in 0.0..15.0) { "light 需要在 0..15" }
        for (name in RENDER_KEYS) require(p.attributes.getValue(name).toFloat().isFinite()) { "$name 超出渲染数值范围" }
    }

    companion object {
        private val COLOR_KEYS = arrayOf("r", "g", "b", "a")
        private val RENDER_KEYS = arrayOf("x", "y", "z", "sc")

        /** 假定全部样本通过，计入存活上限和发射间隔，给服务端一个安全回收上界。 */
        fun retirementBudget(r: ParticleRecipe): Long {
            require(r.lifetime > 0) { "无限寿命不能推算回收时间" }
            val batch = minOf(r.rate, r.maxAlive)
            return ((r.seeds.size.toLong() + batch - 1) / batch) * r.interval +
                ((r.seeds.size - 1L) / r.maxAlive + 1L) * r.lifetime + 4L
        }
    }
}
