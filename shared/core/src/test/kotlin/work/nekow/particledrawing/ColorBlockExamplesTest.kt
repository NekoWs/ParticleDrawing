package work.nekow.particledrawing

import work.nekow.particledrawing.animation.recipe.RecipeRuntime
import work.nekow.particledrawing.api.ParticleRecipe
import work.nekow.particledrawing.api.ParticleSampling
import work.nekow.particledrawing.math.Vec3
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ColorBlock 四个代表案例在命令生成器里的等价写法。 */
class ColorBlockExamplesTest {

    @Test
    fun `立方体只保留十二条棱上的网格点`() {
        val seeds = ParticleSampling.grid(Vec3(-0.5, -0.5, -0.5), Vec3(0.5, 0.5, 0.5), 0.1)
        val recipe = ParticleRecipe(
            seeds,
            rate = 1024,
            lifetime = -1,
            maxAlive = 8192,
            birth = mapOf("g" to "1", "b" to "1"),
            motion = mapOf("vy" to "0.01"),
            where = "(abs(y)==0.5 && !(abs(z)<0.5)) || (abs(x)==0.5 && (!(abs(z)<0.5) || !(abs(y)<0.5)))",
        )
        val runtime = RecipeRuntime(recipe, true)
        runtime.tick(); runtime.tick()
        assertFalse(runtime.emitting)
        assertEquals(116, runtime.particles.size)
        runtime.particles.forEach {
            val edge = listOf(abs(it.initialPosition.x), abs(it.initialPosition.y), abs(it.initialPosition.z)).count { v -> abs(v - 0.5) < 1e-9 }
            assertTrue(edge >= 2, "not on an edge: ${it.initialPosition}")
        }
    }

    @Test
    fun `七芒星按每刻 25 颗逐步画完`() {
        val recipe = ParticleRecipe(
            ParticleSampling.range(0.0, 1000.0, 1.0),
            rate = 25,
            lifetime = -1,
            maxAlive = 1001,
            birth = mapOf(
                "x" to "(0.07*sin(0.04*u+0.225)+-0.1*sin(-0.03*u+0.225))*50",
                "z" to "(0.07*cos(0.04*u+0.225)+-0.1*cos(-0.03*u+0.225))*50",
                "r" to "0.1", "g" to "1", "b" to "0.6",
            ),
        )
        val runtime = RecipeRuntime(recipe, true)
        runtime.tick()
        assertEquals(25, runtime.particles.size)
        repeat(40) { runtime.tick() }
        assertFalse(runtime.emitting)
        assertEquals(1001, runtime.particles.size)
        val first = runtime.particles.first().position
        assertEquals((0.07 * kotlin.math.sin(0.225) - 0.1 * kotlin.math.sin(0.225)) * 50, first.x, 1e-9)
        assertEquals((0.07 * kotlin.math.cos(0.225) - 0.1 * kotlin.math.cos(0.225)) * 50, first.z, 1e-9)
    }

    @Test
    fun `锐角星形用 floor 和余数拆出两个参数`() {
        val recipe = ParticleRecipe(
            ParticleSampling.range(-3082.0, 3082.0, 1.0),
            rate = 1024,
            lifetime = -1,
            maxAlive = 8192,
            lets = linkedMapOf(
                "amp" to "5",
                "p" to "floor(u/78.5)/25",
                "q" to "u%157/25",
            ),
            birth = mapOf(
                "x" to "amp*(cos(p)*cos(q))^3",
                "y" to "amp*(sin(p)*cos(q))^3",
                "z" to "amp*sin(q)^3",
            ),
            motion = mapOf(
                "vx" to "-sin(0.05)*(x*2*sin(0.05))-cos(0.05)*(z*2*sin(0.05))",
                "vy" to "0.1",
                "vz" to "cos(0.05)*(x*2*sin(0.05))-sin(0.05)*(z*2*sin(0.05))",
            ),
        )
        val runtime = RecipeRuntime(recipe, true)
        repeat(7) { runtime.tick() }
        assertFalse(runtime.emitting)
        assertEquals(6165, runtime.particles.size)
        runtime.particles.forEach {
            assertTrue(abs(it.initialPosition.x) <= 5.0 + 1e-9)
            assertTrue(abs(it.initialPosition.y) <= 5.0 + 1e-9)
            assertTrue(abs(it.initialPosition.z) <= 5.0 + 1e-9)
        }
    }

    @Test
    fun `五角星按步长 2 采样`() {
        val recipe = ParticleRecipe(
            ParticleSampling.range(0.0, 1000.0, 2.0),
            rate = 1024,
            lifetime = -1,
            maxAlive = 501,
            birth = mapOf(
                "x" to "(0.06*sin(0.02*u+1)+-0.03*sin(-0.03*u+1))*20",
                "z" to "(0.06*cos(0.02*u+1)+-0.03*cos(-0.03*u+1))*20",
                "r" to "1", "g" to "0.5", "b" to "0",
            ),
        )
        val runtime = RecipeRuntime(recipe, true)
        runtime.tick()
        assertFalse(runtime.emitting)
        assertEquals(501, runtime.particles.size)
    }
}
