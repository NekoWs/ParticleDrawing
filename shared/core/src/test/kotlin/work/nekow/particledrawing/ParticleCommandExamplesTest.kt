package work.nekow.particledrawing

import work.nekow.particledrawing.animation.recipe.RecipeRuntime
import work.nekow.particledrawing.api.*
import java.nio.file.Files
import java.nio.file.Path
import work.nekow.particledrawing.math.Vec3
import kotlin.math.abs
import kotlin.test.*

class ParticleCommandExamplesTest {
    private val hasExamples = Files.isDirectory(Path.of("examples/commands"))

    private fun example(name: String, emitterName: String? = null): ParticleRecipe =
        ParticleExampleDeclarations.read(name).single { emitterName == null || it.name == emitterName }.recipe

    @Test fun `ColorBlock 星形发射器示例都能画出对应形状`() {
        if (!hasExamples) return
        val seven = RecipeRuntime(example("seven-star"), true).also { repeat(41) { _ -> it.tick() } }
        assertEquals(1001, seven.particles.size)
        val sharp = RecipeRuntime(example("sharp-star"), true).also { repeat(7) { _ -> it.tick() } }
        assertEquals(6165, sharp.particles.size)
        val five = RecipeRuntime(example("five-star"), true).also { it.tick() }
        assertEquals(501, five.particles.size)
    }

    @Test fun `图片示例的全部像素能同时存在并在寿命结束后回收`() {
        if (!hasExamples) return
        val recipe = example("image")
        assertEquals(7225, recipe.seeds.size)
        val runtime = RecipeRuntime(recipe, true)
        val batches = (recipe.seeds.size + recipe.rate - 1) / recipe.rate
        assertTrue(recipe.lifetime > batches)
        repeat(batches) { runtime.tick() }
        assertFalse(runtime.emitting)
        assertEquals(recipe.seeds, runtime.particles.map { it.seed })
        assertEquals(7225, runtime.particles.size)
        repeat(recipe.lifetime) { runtime.tick() }
        assertTrue(runtime.finished)
    }

    @Test fun `球面示例旋转变色后仍保持半径且可以热更新转速`() {
        if (!hasExamples) return
        val recipe = example("rotating-sphere")
        val runtime = RecipeRuntime(recipe, true)
        runtime.tick()
        val before = runtime.particles.map { it.position }
        runtime.update(recipe.copy(variables = recipe.variables + ("speed" to 0.08)))
        repeat(60) { runtime.tick() }
        assertEquals(768, runtime.particles.size)
        runtime.particles.forEach { assertEquals(2.4, it.position.length(), 1e-8) }
        assertTrue(runtime.particles.indices.any { before[it].distanceTo(runtime.particles[it].position) > 0.5 })
        assertTrue(runtime.particles.map { it.value("r") }.distinct().size > 100)
    }

    @Test fun `桃心示例逐步画完且留在锚点竖直平面`() {
        if (!hasExamples) return
        val recipe = example("heart")
        val runtime = RecipeRuntime(recipe, true)
        repeat(20) { runtime.tick() }
        assertEquals(recipe.seeds.size, runtime.particles.size)
        assertFalse(runtime.emitting)
        assertTrue(runtime.particles.any { it.position.x < -2 })
        assertTrue(runtime.particles.any { it.position.x > 2 })
        runtime.particles.forEach { assertEquals(0.0, it.position.z); assertTrue(it.value("sc") in 0.8..1.0) }
    }

    @Test fun `动态曲臂保持九臂数量并在有界区域摆动`() {
        if (!hasExamples) return
        val recipe = example("tentacles")
        val runtime = RecipeRuntime(recipe, true)
        runtime.tick()
        val before = runtime.particles.map { it.position }
        repeat(80) { runtime.tick() }
        assertEquals(9 * 80, runtime.particles.size)
        assertTrue(runtime.particles.indices.any { before[it].distanceTo(runtime.particles[it].position) > 0.5 })
        runtime.particles.forEach {
            assertTrue(abs(it.position.y) <= 0.25)
            assertTrue(it.position.horizontalDistance() <= 4.3 + 1e-9)
        }
    }

    @Test fun `环面结闭合且旋转倾斜不改变到中心的距离`() {
        if (!hasExamples) return
        val recipe = example("torus-knot")
        val runtime = RecipeRuntime(recipe, true)
        runtime.tick()
        val before = runtime.particles.map { it.position }
        assertEquals(960, before.size)
        assertTrue(before.first().distanceTo(before.last()) < 1e-8)
        assertTrue(before.zipWithNext().all { (a, b) -> a.distanceTo(b) < 0.1 })
        repeat(60) { runtime.tick() }
        runtime.particles.forEachIndexed { i, p -> assertEquals(before[i].length(), p.position.length(), 1e-8) }
        assertTrue(runtime.particles.indices.any { before[it].distanceTo(runtime.particles[it].position) > 0.5 })
    }

    @Test fun `莫比乌斯带首尾反向衔接且扭转曲面旋转时不散开`() {
        if (!hasExamples) return
        val runtime = RecipeRuntime(example("mobius"), true)
        runtime.tick()
        val before = runtime.particles.map { it.position }
        assertEquals(144 * 7, before.size)
        for (j in 0..6) assertTrue(before[j].distanceTo(before[143 * 7 + 6 - j]) < 0.2)
        assertTrue(before.first().distanceTo(before[143 * 7]) > 1.0)
        repeat(60) { runtime.tick() }
        runtime.particles.forEachIndexed { i, p -> assertEquals(before[i].length(), p.position.length(), 1e-8) }
        assertTrue(runtime.particles.indices.any { before[it].distanceTo(runtime.particles[it].position) > 0.5 })
    }

    @Test fun `曼陀罗三层始终完整居中且外环保持三种半径`() {
        if (!hasExamples) return
        val runtimes = listOf("mandala", "mandala-star", "mandala-ring").associateWith { name ->
            val recipe = example("mandala", name)
            assertTrue(recipe.follow)
            assertTrue(recipe.maxAlive >= recipe.seeds.size)
            RecipeRuntime(recipe, true).also { it.tick() }
        }
        assertEquals(2040, runtimes.values.sumOf { it.particles.size })
        repeat(60) { runtimes.values.forEach { it.tick() } }
        for (runtime in runtimes.values) {
            assertFalse(runtime.emitting)
            assertEquals(runtime.recipe.seeds.size, runtime.particles.size)
            assertTrue(abs(runtime.particles.map { it.position.x }.average()) < 0.03)
            assertTrue(abs(runtime.particles.map { it.position.z }.average()) < 0.03)
            runtime.particles.forEach { assertTrue(abs(it.position.y) <= 0.12) }
        }
        val rings = runtimes.getValue("mandala-ring").particles
        rings.forEachIndexed { i, p -> assertEquals(3.2 + (i / 240) * 0.18, p.position.horizontalDistance(), 1e-8) }
    }
}
