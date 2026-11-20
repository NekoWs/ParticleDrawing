package work.nekow.particledrawing

import work.nekow.particledrawing.animation.recipe.ParticleRecipeExecution
import work.nekow.particledrawing.animation.recipe.ParticleWorld
import work.nekow.particledrawing.animation.recipe.RecipeCollision
import work.nekow.particledrawing.animation.recipe.RecipeFrame
import work.nekow.particledrawing.animation.recipe.RecipeRuntime
import work.nekow.particledrawing.api.ParticleRecipe
import work.nekow.particledrawing.api.ParticleRecipeMode
import work.nekow.particledrawing.api.ParticleSampling
import work.nekow.particledrawing.api.ParticleVisual
import work.nekow.particledrawing.api.RecipeAnchor
import work.nekow.particledrawing.math.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ParticleScalingTest {
    private val origin = RecipeFrame.IDENTITY
    private val seeds = ParticleSampling.count(5)
    private val look = ParticleVisual().apply {
        glowing = true
        additive = true
        lightLevel = 0
    }

    @Test fun `从暂停恢复保留粒子和时钟且不会扫掠暂停时锚点的位移`() {
        val sweeps = arrayListOf<Pair<Vec3, Vec3>>()
        val world = ParticleWorld { _, from, target ->
            sweeps += from to target
            null
        }
        val source = RecipeRuntime(ParticleRecipe(seeds, rate = 1, interval = 100, follow = true,
            collision = RecipeCollision.SOLID, birth = mapOf("vx" to "0.5")), burst = false, world = world)
        source.tickInFrame(origin)
        source.tickInFrame(origin)
        val particle = source.particles.single()
        source.resumeSimulation()
        assertSame(particle, source.particles.single())
        assertEquals(2L, source.ticks)
        assertEquals(1, particle.age)
        assertEquals(1L, source.cursor)

        source.tickInFrame(RecipeFrame(Vec3(100.0, 0.0, 0.0)))
        assertEquals(Vec3(100.5, 0.0, 0.0) to Vec3(101.0, 0.0, 0.0), sweeps.last())
        assertEquals(2, particle.age)
    }

    @Test fun `恢复等距发射不补算暂停时经过的里程`() {
        val source = RecipeRuntime(ParticleRecipe(seeds, rate = 1, spacing = 1.0), burst = false)
        source.tickInFrame(origin)
        source.tickInFrame(RecipeFrame(Vec3(0.5, 0.0, 0.0)))
        source.resumeSimulation()
        source.tickInFrame(RecipeFrame(Vec3(100.0, 0.0, 0.0)))
        source.tickInFrame(RecipeFrame(Vec3(100.5, 0.0, 0.0)))
        assertEquals(0L, source.cursor)
        source.tickInFrame(RecipeFrame(Vec3(101.0, 0.0, 0.0)))
        assertEquals(1L, source.cursor)
    }

    @Test fun `GPU 弹道初值沿用出生公式和发射间隔并与离散积分一致`() {
        val recipe = ParticleRecipe(seeds, rate = 2, interval = 3, lifetime = 15, visual = look,
            birth = mapOf("x" to "i", "vx" to "u*0.4", "vy" to "0.5 + t/500", "az" to "-0.04"),
            motion = mapOf("ay" to "-0.08"), where = "i != 1")
        val anchor = RecipeAnchor(Vec3(80.0, 1.0, -20.0))
        val shots = ParticleRecipeExecution.bake(recipe, anchor)
        assertEquals(listOf(0L, 3L, 3L, 6L), shots.map { it.birthTick })
        val cpu = RecipeRuntime(recipe, burst = true)
        for (tick in 0L..22L) {
            cpu.tickInFrame(RecipeFrame(anchor.position))
            val visible = shots.filter { tick - it.birthTick in 0 until recipe.lifetime }
            assertEquals(visible.size, cpu.particles.size)
            for ((shot, particle) in visible.zip(cpu.particles)) {
                val expected = shot.positionAt(tick)
                assertEquals(expected.x, particle.x, 1e-10)
                assertEquals(expected.y, particle.y, 1e-10)
                assertEquals(expected.z, particle.z, 1e-10)
            }
        }
    }

    @Test fun `两种图形模式明确拒绝无法复现的效果`() {
        val recipe = ParticleRecipe(seeds, visual = look)
        val anchor = RecipeAnchor(Vec3.ZERO)
        for (unsupported in listOf(recipe.copy(collision = RecipeCollision.SOLID),
            recipe.copy(motion = mapOf("ay" to "sin(t)")), recipe.copy(birth = mapOf("vx" to "flow_x")))) {
            assertFailsWith<IllegalArgumentException> {
                ParticleRecipeExecution.validate(unsupported, anchor, true, ParticleRecipeMode.GPU)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            ParticleRecipeExecution.validate(recipe.copy(visual = look.copy().apply {
                sceneMaterial = work.nekow.particledrawing.api.ParticleSceneMaterial(0f, 12f)
            }),
                anchor, true, ParticleRecipeMode.INSTANCED)
        }
        ParticleRecipeExecution.validate(recipe.copy(visual = look.copy().apply {
            glowing = false
            additive = false
            trailTicks = 8
            model = work.nekow.particledrawing.math.ResourceId.parse("particledrawing:builtin/octahedron")
        }), anchor, true, ParticleRecipeMode.INSTANCED)
    }
}
