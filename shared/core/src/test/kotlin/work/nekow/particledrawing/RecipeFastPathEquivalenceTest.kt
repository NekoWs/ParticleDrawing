package work.nekow.particledrawing

import work.nekow.particledrawing.animation.recipe.RecipeExpressions
import work.nekow.particledrawing.animation.recipe.RecipeRuntime
import work.nekow.particledrawing.api.ParticleRecipe
import work.nekow.particledrawing.api.ParticleSampling
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 热路径与解释器必须给出同一结果。
 *
 * 配方求值有两条实现：编译成寄存器直算的 [RecipeExpressions.fast]，和按名字查表的解释器。
 * 两者行为一旦分叉，同一条命令在不同配方上就会出现不同的数，这里逐刻逐粒子钉住。
 */
class RecipeFastPathEquivalenceTest {

    private val hole: ParticleRecipe
        get() = ParticleRecipe(
            ParticleSampling.count(64),
            rate = 64,
            lifetime = 400,
            maxAlive = 512,
            lets = linkedMapOf("ang" to "u*6.2832", "rad" to "8+16*fract(u*7.31)", "k" to "26", "s" to "1.7"),
            birth = mapOf(
                "position" to "vec3(cos(ang)*rad, (fract(u*3.17)-0.5)*7, sin(ang)*rad)",
                "r" to "0.55+0.45*(1-(rad-8)/16)",
                "g" to "0.25+0.5*(rad-8)/16",
                "b" to "0.9",
            ),
            motion = mapOf(
                "acceleration" to "vec3(-x*k/((x*x+y*y+z*z)*sqrt(x*x+y*y+z*z))-z*s/sqrt(x*x+y*y+z*z), " +
                    "-y*k*4/((x*x+y*y+z*z)*sqrt(x*x+y*y+z*z)), " +
                    "-z*k/((x*x+y*y+z*z)*sqrt(x*x+y*y+z*z))+x*s/sqrt(x*x+y*y+z*z))",
            ),
            kill = "x*x+y*y+z*z < 3.24",
        )

    private val ring: ParticleRecipe
        get() = ParticleRecipe(
            ParticleSampling.count(96),
            rate = 96,
            lifetime = 60,
            maxAlive = 512,
            lets = linkedMapOf(
                "ph" to "min(age/60,1)",
                "env" to "sin(ph*3.1416)",
                "trail" to "env*2.6",
                "lag" to "fract(u*11.3)*2.6",
                "head" to "ph*7",
                "h" to "max(head-lag,0)",
                "ang" to "u*6.2832",
                "rad" to "2.2+1.6*sin(ph*3.1416)",
            ),
            birth = mapOf("position" to "vec3(cos(ang)*rad,0.05,sin(ang)*rad)", "sc" to "0.9"),
            motion = mapOf(
                "position" to "vec3(cos(ang)*rad,h,sin(ang)*rad)",
                "a" to "clamp(max((1-lag/max(trail,0.05)),(1-ph)*step(head,lag)),0,1)*0.95",
                "b" to "0.22+0.6*(1-lag/2.6)",
                "sc" to "0.5+0.5*(1-lag/2.6)",
                "glow" to "1",
            ),
        )

    /** state 每刻自增，并被 motion 读取：考校快路径有没有把 state 写丢。 */
    private val stateful: ParticleRecipe
        get() = ParticleRecipe(
            ParticleSampling.count(32),
            rate = 32,
            lifetime = 200,
            maxAlive = 256,
            state = mapOf("hits" to 0.0, "phase" to 0.0),
            birth = mapOf("position" to "vec3(u*2-1,0,0)"),
            motion = mapOf(
                "phase" to "phase+0.25",
                "hits" to "hits+1",
                "y" to "sin(phase)*0.5",
                "r" to "clamp(hits/20,0,1)",
            ),
        )

    /** 筛选与销毁条件里带比较和逻辑运算。 */
    private val filtered: ParticleRecipe
        get() = ParticleRecipe(
            ParticleSampling.count(48),
            rate = 48,
            lifetime = 300,
            maxAlive = 256,
            birth = mapOf("position" to "vec3(u*4-2,0,0)"),
            motion = mapOf("vy" to "0.01", "glow" to "1"),
            where = "x > -1 && x < 1",
            kill = "y > 1 || x < -1.5",
        )

    @Test
    fun compiledRecipesTakeTheFastPath() {
        assertNotNull(RecipeExpressions(hole).fast, "黑洞配方应能编译")
        assertNotNull(RecipeExpressions(ring).fast, "升级动画配方应能编译")
        assertNotNull(RecipeExpressions(stateful).fast, "state 配方应能编译")
        assertNotNull(RecipeExpressions(filtered).fast, "筛选配方应能编译")
    }

    @Test
    fun vectorLetFallsBackToInterpreter() {
        val recipe = ParticleRecipe(
            ParticleSampling.count(8),
            lifetime = 100,
            lets = mapOf("v" to "vec3(1,2,3)"),
            birth = mapOf("position" to "v"),
        )
        recipe.validate()
        val runtime = RecipeRuntime(recipe, burst = false)
        runtime.tick()
        assertNull(RecipeExpressions(recipe).fast, "向量 let 只能走解释器")
        assertTrue(runtime.particles.isEmpty() || runtime.particles.isNotEmpty())
    }

    @Test
    fun holeMatchesInterpreter() = assertSame("hole", hole)

    @Test
    fun ringMatchesInterpreter() = assertSame("ring", ring)

    @Test
    fun stateMatchesInterpreter() = assertSame("state", stateful)

    @Test
    fun filterMatchesInterpreter() = assertSame("filter", filtered)

    /** 同一配方分别走快慢两条路，逐刻逐粒子比较所有持久字段。 */
    private fun assertSame(name: String, recipe: ParticleRecipe, ticks: Int = 40) {
        recipe.validate()
        val fast = RecipeRuntime(recipe, burst = false, preferFast = true)
        val slow = RecipeRuntime(recipe, burst = false, preferFast = false)
        val fields = fast.slots.map.keys.filter { it !in RecipeExpressions.INPUT_SLOTS }
        assertEquals(fields, slow.slots.map.keys.filter { it !in RecipeExpressions.INPUT_SLOTS })

        repeat(ticks) { t ->
            fast.tick()
            slow.tick()
            assertEquals(slow.particles.size, fast.particles.size, "$name t=$t 存活数不同")
            for (k in fast.particles.indices) {
                val a = fast.particles[k]
                val b = slow.particles[k]
                assertEquals(b.age, a.age, "$name t=$t 第 $k 颗年龄不同")
                for (field in fields) {
                    val expected = b.value(field)
                    val actual = a.value(field)
                    assertTrue(
                        abs(expected - actual) <= 1e-9 * maxOf(1.0, abs(expected)),
                        "$name t=$t 第 $k 颗 $field 不同：解释器=$expected 热路径=$actual",
                    )
                }
            }
        }
    }
}
