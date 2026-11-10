package work.nekow.particledrawing

import work.nekow.particledrawing.animation.recipe.RecipeRuntime
import work.nekow.particledrawing.api.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.*

class ParticleCommandExamplesTest {
    /** 读取实际函数的数据和公式部分；服务器锚点与贴图由游戏解析。 */
    private fun example(name: String, emitterName: String? = null): ParticleRecipe {
        val declarations = Files.readAllLines(Path.of("examples/commands/$name.mcfunction"))
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split(' ', limit = 5) }
            .filter { emitterName == null || it[3] == emitterName || (it[3] == "reset" && it[4] == emitterName) }
        val emitter = declarations.first().also { assertEquals("reset", it[3]) }[4]
        val commands = declarations.drop(1).map {
            assertEquals(listOf("pdraw", "particle", "emitter", emitter), it.take(4))
            val parts = it[4].split(' ', limit = 2)
            parts[0] to parts.getOrElse(1) { "" }
        }.groupBy({ it.first }, { it.second })
        fun value(key: String) = commands.getValue(key).single()
        fun fields(key: String) = commands[key].orEmpty().associate {
            val parts = it.split(' ', limit = 2)
            parts[0] to parts[1]
        }
        val seeds = if ("image" in commands) {
            val settings = fields("image")
            assertEquals("pd-icon.png", settings.getValue("file"))
            Files.newInputStream(Path.of("platforms/neoforge-common/src/main/resources/icon.png")).use {
                ParticleImageSampler.sample(it, ParticleImageSampler.Options(
                    stride = settings.getValue("stride").toInt(), spacing = settings.getValue("spacing").toDouble(),
                    plane = ParticleImageSampler.Plane.valueOf(settings.getValue("plane").uppercase()),
                ))
            }
        } else {
            val sampling = value("sample").split(' ')
            when (sampling[0]) {
                "count" -> ParticleSampling.count(sampling[1].toInt())
                "range" -> ParticleSampling.range(sampling[1].toDouble(), sampling[2].toDouble(), sampling[3].toDouble())
                else -> error("未覆盖的示例采样")
            }
        }
        return ParticleRecipe(seeds, rate = value("rate").toInt(), lifetime = value("lifetime").toInt(),
            maxAlive = value("max_alive").toInt(), variables = fields("variable").mapValues { it.value.toDouble() },
            follow = commands["follow"]?.single()?.toBooleanStrict() ?: false,
            lets = fields("let"), birth = fields("birth"), motion = fields("motion")).also { it.validate() }
    }

    @Test fun `图片示例的全部像素能同时存在并在寿命结束后回收`() {
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
        val recipe = example("rotating-sphere")
        val runtime = RecipeRuntime(recipe, true)
        runtime.tick()
        val before = runtime.particles.map { it.position }
        runtime.update(recipe.copy(variables = recipe.variables + ("speed" to 0.08)))
        repeat(60) { runtime.tick() }
        assertEquals(768, runtime.particles.size)
        runtime.particles.forEach { assertEquals(2.4, it.position.length(), 1e-8) }
        assertTrue(runtime.particles.indices.any { before[it].distanceTo(runtime.particles[it].position) > 0.5 })
        assertTrue(runtime.particles.map { it.attributes.getValue("r") }.distinct().size > 100)
    }

    @Test fun `桃心示例逐步画完且留在锚点竖直平面`() {
        val recipe = example("heart")
        val runtime = RecipeRuntime(recipe, true)
        repeat(20) { runtime.tick() }
        assertEquals(recipe.seeds.size, runtime.particles.size)
        assertFalse(runtime.emitting)
        assertTrue(runtime.particles.any { it.position.x < -2 })
        assertTrue(runtime.particles.any { it.position.x > 2 })
        runtime.particles.forEach { assertEquals(0.0, it.position.z); assertTrue(it.attributes.getValue("sc") in 0.8..1.0) }
    }

    @Test fun `动态曲臂保持九臂数量并在有界区域摆动`() {
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
