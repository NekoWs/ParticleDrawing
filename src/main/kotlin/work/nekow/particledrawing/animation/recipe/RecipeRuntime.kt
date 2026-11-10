package work.nekow.particledrawing.animation.recipe

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.ParticleRecipe
import work.nekow.particledrawing.api.ParticleSeed

/** 可在纯 JVM 验证的出生调度和逐粒子积分；渲染与网络只消费它的状态。 */
internal class RecipeRuntime(var recipe: ParticleRecipe, val burst: Boolean) {
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

    /** 旧粒子先推进，再生成本刻的新粒子；新生粒子保留 age=0 的完整首帧。 */
    fun tick(allowBirth: Boolean = true) {
        for (p in particles) {
            if (!p.alive) continue
            p.age++
            if (p.lifetime > 0 && p.age >= p.lifetime) { p.alive = false; continue }
            prepare(p)
            if (expressions.kills()) { p.alive = false; continue }
            val outputs = expressions.evaluate(expressions.motion)
            p.attributes.putAll(outputs)
            for ((pos, vel, accel) in listOf(Triple("x", "vx", "ax"), Triple("y", "vy", "ay"), Triple("z", "vz", "az"))) {
                val speed = p.attributes.getValue(vel) + p.attributes.getValue(accel)
                p.attributes[vel] = speed
                if (pos !in outputs) p.attributes[pos] = p.attributes.getValue(pos) + speed
            }
            validateAttributes(p)
        }
        particles.removeAll { !it.alive }
        if (emitting && allowBirth && ticks % recipe.interval == 0L) {
            repeat(recipe.rate) {
                if (!emitting || particles.size >= recipe.maxAlive) return@repeat
                val seed = recipe.seeds[(cursor % recipe.seeds.size).toInt()]
                val a = defaults(seed)
                val p = Particle(cursor++, seed, recipe.lifetime, a)
                prepare(p)
                p.attributes.putAll(expressions.evaluate(expressions.birth))
                validateAttributes(p)
                p.initialPosition = p.position
                prepare(p)
                if (expressions.accepts() && !expressions.kills()) particles += p
                if (burst && cursor >= recipe.seeds.size) emitting = false
            }
        }
        ticks++
    }

    /** 对选中的现存成员执行一次修改或删除，不改变以后出生的粒子。 */
    fun edit(predicate: String, changes: Map<String, String>, remove: Boolean): Int {
        val plan = recipe.copy(lets = recipe.lets, birth = changes, motion = emptyMap(), where = predicate, kill = "false")
        plan.validate()
        val edit = RecipeExpressions(plan)
        val selected = arrayListOf<Pair<Particle, Map<String, Double>>>()
        for (p in particles) {
            edit.prepare(inputs(p), recipe.variables)
            if (edit.accepts()) selected += p to if (remove) emptyMap() else edit.evaluate(edit.birth)
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

    private fun inputs(p: Particle) = LinkedHashMap(p.attributes).apply {
        put("i", p.index.toDouble()); put("n", recipe.seeds.size.toDouble()); put("u", p.seed.u)
        put("t", ticks * 50.0); put("dt", 1.0); put("age", p.age.toDouble()); put("life", p.lifetime.toDouble())
        put("progress", if (p.lifetime > 0) p.age.toDouble() / p.lifetime else 0.0)
        put("dx", p.initialPosition.x); put("dy", p.initialPosition.y); put("dz", p.initialPosition.z)
    }

    private fun prepare(p: Particle) = expressions.prepare(inputs(p), recipe.variables)

    private fun validateAttributes(p: Particle) {
        require(p.attributes.values.all(Double::isFinite)) { "粒子公式产生了非有限数" }
        require(p.attributes.getValue("sc") >= 0.0) { "sc 不能为负" }
        for (name in listOf("r", "g", "b", "a")) require(p.attributes.getValue(name) in 0.0..1.0) { "$name 需要在 0..1" }
        require(p.attributes.getValue("light") in 0.0..15.0) { "light 需要在 0..15" }
        for (name in listOf("x", "y", "z", "sc")) require(p.attributes.getValue(name).toFloat().isFinite()) { "$name 超出渲染数值范围" }
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
