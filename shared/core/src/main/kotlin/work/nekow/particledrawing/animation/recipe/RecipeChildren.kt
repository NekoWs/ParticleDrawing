package work.nekow.particledrawing.animation.recipe

import work.nekow.particledrawing.api.RecipeEmission

/** 子配方按主配方的事件顺序生成，两端共用同一推进顺序和配额。 */
internal class RecipeChildren(private val world: ParticleWorld?) {
    private val runtimes = linkedMapOf<RecipeEmission, RecipeRuntime>()
    val active: Collection<RecipeRuntime> get() = runtimes.values

    fun tick(events: List<RecipeRuntime.Trigger>, budget: Int, maxAlive: Int = Int.MAX_VALUE): Int {
        for (child in active) child.tickInFrame(RecipeFrame.IDENTITY)
        var attempts = if (maxAlive == Int.MAX_VALUE) budget else minOf(budget, maxAlive - active.sumOf { it.particles.size })
        var spent = 0
        for ((rule, input) in events) {
            if (attempts <= 0) break
            val child = runtimes.getOrPut(rule) {
                RecipeRuntime(rule.child, burst = true, world = world).also { it.emitting = false }
            }
            val origin = input.position.add(input.normal.scale(CollisionMath.SURFACE_MARGIN))
            val count = child.emitAt(RecipeFrame(origin), input, attempts)
            attempts -= count
            spent += count
        }
        return spent
    }

    fun prune() { runtimes.values.removeIf { it.finished } }
    fun clear() = runtimes.clear()
}
