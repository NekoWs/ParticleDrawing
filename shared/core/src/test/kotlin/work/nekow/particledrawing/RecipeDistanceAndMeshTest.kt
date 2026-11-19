package work.nekow.particledrawing

import work.nekow.particledrawing.animation.recipe.RecipeFrame
import work.nekow.particledrawing.animation.recipe.RecipeRuntime
import work.nekow.particledrawing.api.ParticleMeshSampler
import work.nekow.particledrawing.api.ParticleRecipe
import work.nekow.particledrawing.api.ParticleSampling
import work.nekow.particledrawing.api.ParticleTriangleProvider
import work.nekow.particledrawing.math.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RecipeDistanceAndMeshTest {
    @Test fun `同一距离调度能组合样本与出生公式`() {
        for (follow in listOf(false, true)) {
            val runtime = RecipeRuntime(ParticleRecipe(
                ParticleSampling.count(3), rate = 2, spacing = 1.0, follow = follow,
                birth = mapOf("y" to "i"),
            ), burst = false)
            fun advance(x: Double) = runtime.tickInFrame(RecipeFrame(Vec3(x, 0.0, 0.0)))
            advance(0.0)
            advance(0.4)
            assertTrue(runtime.particles.isEmpty())
            advance(2.6)
            assertEquals(listOf(Vec3(1.0, 0.0, 0.0), Vec3(1.0, 1.0, 0.0), Vec3(2.0, 2.0, 0.0), Vec3(2.0, 3.0, 0.0)),
                runtime.particles.map { runtime.frameOf(it).position(it.x, it.y, it.z) })
            advance(100.0)
            assertEquals(4, runtime.particles.size)
            advance(101.0)
            assertEquals(6, runtime.particles.size)
        }
    }

    @Test fun `距离发射受逐刻预算限制且静止时没有积压出生`() {
        val recipe = ParticleRecipe(ParticleSampling.count(1), rate = 64, spacing = 0.05)
        val runtime = RecipeRuntime(recipe, burst = false)
        runtime.tick()
        val next = RecipeFrame(Vec3(20.0, 0.0, 0.0))
        runtime.tickInFrame(next)
        assertEquals(ParticleRecipe.MAX_RATE, runtime.particles.size)
        runtime.tickInFrame(next)
        assertEquals(ParticleRecipe.MAX_RATE, runtime.particles.size)
        assertFailsWith<IllegalArgumentException> { RecipeRuntime(recipe, burst = true) }
    }

    @Test fun `OBJ 三角面按面积抽样且法线能写入配方速度`() {
        val mesh = ParticleMeshSampler.readObj("""
            v 0 0 0
            v 4 0 0
            v 0 0 4
            v 0 10 0
            v 2 10 0
            v 0 10 2
            f 1 3 2
            f -3/1 -1/2 -2/3
        """.trimIndent().byteInputStream())
        val samples = ParticleMeshSampler.surface(mesh, 4096, 42)
        assertEquals(samples, ParticleMeshSampler.surface(mesh, 4096, 42))
        val largest = samples.count { it.position.y == 0.0 }
        assertTrue(largest in 3160..3400)
        assertTrue(samples.all { it.normal?.dot(Vec3(0.0, 1.0, 0.0)) == 1.0 })
        for (preferFast in listOf(true, false)) {
            val runtime = RecipeRuntime(ParticleRecipe(samples.take(1), birth = mapOf("vy" to "sample_ny")),
                burst = true, preferFast = preferFast)
            runtime.tick()
            assertEquals(1.0, runtime.particles.single().value("vy"))
        }
    }

    @Test fun `每次取样读取新姿势并共用重心权重插值法线和 UV`() {
        val original = ParticleMeshSampler.objProvider("""
            v 0 0 0
            v 4 0 0
            v 0 2 0
            vt 0 0
            vt 1 0
            vt 0 1
            vn 0 0 1
            vn 0 1 0
            f 1/1/1 2/2/2 -1/-1/-2
        """.trimIndent().byteInputStream()).triangles().single()
        var pose = Vec3.ZERO
        var snapshots = 0
        val provider = ParticleTriangleProvider {
            snapshots++
            listOf(original.copy(a = original.a.add(pose), b = original.b.add(pose), c = original.c.add(pose)))
        }
        val before = ParticleMeshSampler.points(provider, 32, 15)
        pose = Vec3(8.0, 4.0, -3.0)
        val after = ParticleMeshSampler.points(provider, 32, 15)
        assertEquals(2, snapshots)
        for ((first, second) in before.zip(after)) {
            val uv = assertNotNull(first.uv)
            assertEquals(first.position.x / 4.0, uv.u, 1e-12)
            assertEquals(first.position.y / 2.0, uv.v, 1e-12)
            val expected = Vec3(0.0, uv.u, 1.0 - uv.u).normalize()
            assertEquals(expected.y, first.normal.y, 1e-12)
            assertEquals(expected.z, first.normal.z, 1e-12)
            assertEquals(first.position.add(pose).x, second.position.x, 1e-12)
            assertEquals(first.position.add(pose).y, second.position.y, 1e-12)
            assertEquals(first.position.add(pose).z, second.position.z, 1e-12)
            assertEquals(uv, second.uv)
        }

        val samples = ParticleMeshSampler.surface(provider, 1, 15)
        val uv = assertNotNull(samples.single().meshUv)
        for (preferFast in listOf(true, false)) {
            val runtime = RecipeRuntime(ParticleRecipe(
                samples, where = "sample_has_uv", birth = mapOf("r" to "sample_tex_u", "g" to "sample_tex_v"),
            ), burst = true, preferFast = preferFast)
            runtime.tick()
            assertEquals(uv.u, runtime.particles.single().value("r"))
            assertEquals(uv.v, runtime.particles.single().value("g"))
        }
    }

    @Test fun `网格拒绝非三角面与坏索引`() {
        fun read(face: String) = ParticleMeshSampler.readObj(("v 0 0 0\nv 1 0 0\nv 0 1 0\n" + face).byteInputStream())
        assertFailsWith<IllegalArgumentException> { read("f 1 2 3 1") }
        assertFailsWith<IllegalArgumentException> { read("f 1 2 0") }
        assertFailsWith<IllegalArgumentException> { ParticleMeshSampler.surface(read("f 1 1 2"), 1) }
    }
}
