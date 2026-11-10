package work.nekow.particledrawing

import work.nekow.particledrawing.math.Vec3
import work.nekow.particledrawing.animation.recipe.RecipeRuntime
import work.nekow.particledrawing.api.*
import kotlin.test.*

class ParticleRecipeRuntimeTest {
    private fun recipe(count: Int = 1, rate: Int = 1) = ParticleRecipe(ParticleSampling.count(count), rate = rate)

    @Test fun `按预算出生且每颗有独立年龄和寿命`() {
        val r = RecipeRuntime(recipe(5, 2).copy(lifetime = 3), true)
        r.tick(); assertEquals(listOf(0, 0), r.particles.map { it.age })
        r.tick(); assertEquals(listOf(1, 1, 0, 0), r.particles.map { it.age })
        r.tick(); assertEquals(5, r.particles.size); assertFalse(r.emitting)
        r.tick(); assertEquals(listOf(2, 2, 1), r.particles.map { it.age })
        r.tick(); assertEquals(1, r.particles.size)
        r.tick(); assertTrue(r.finished)
    }

    @Test fun `间隔暂停出生与停止都不冻结现存粒子`() {
        val r = RecipeRuntime(recipe(3).copy(interval = 2, lifetime = 8, birth = mapOf("vx" to "0.1")), false)
        r.tick(); r.tick(); assertEquals(1, r.particles.size)
        r.tick(false); assertEquals(1, r.particles.size)
        r.emitting = false
        r.tick(); assertEquals(0.3, r.particles.single().position.x, 1e-9)
        repeat(8) { r.tick() }; assertTrue(r.finished)
    }

    @Test fun `达到存活上限时保留尚未生成的样本`() {
        val r = RecipeRuntime(recipe(4, 4).copy(lifetime = 2, maxAlive = 2), true)
        r.tick(); assertEquals(2L, r.cursor)
        r.tick(); assertEquals(2L, r.cursor)
        r.tick(); assertEquals(listOf(2L, 3L), r.particles.map { it.index })
        assertFalse(r.emitting)
    }

    @Test fun `空间条件只生成符合要求的网格点`() {
        val points = ParticleSampling.grid(Vec3(-1.0, -1.0, 0.0), Vec3(1.0, 1.0, 0.0), 1.0)
        val r = RecipeRuntime(ParticleRecipe(points, rate = 20, where = "x*x+y*y <= 1"), true)
        r.tick(); assertEquals(5, r.particles.size); assertEquals(9L, r.cursor)
    }

    @Test fun `采样参数与发射器时间和粒子年龄分开`() {
        val r = RecipeRuntime(recipe(3).copy(birth = mapOf("x" to "u", "y" to "t/50"), motion = mapOf("z" to "age")), true)
        repeat(3) { r.tick() }
        assertEquals(listOf(0.0, 0.5, 1.0), r.particles.map { it.position.x })
        assertEquals(listOf(0.0, 1.0, 2.0), r.particles.map { it.position.y })
        assertEquals(listOf(2.0, 1.0, 0.0), r.particles.map { it.position.z })
    }

    @Test fun `运动读取实际位置速度且多个输出使用同一输入快照`() {
        val seed = ParticleSeed(Vec3(1.0, 2.0, 0.0))
        val r = RecipeRuntime(ParticleRecipe(listOf(seed), birth = mapOf("vx" to "0.1"),
            motion = linkedMapOf("x" to "y", "y" to "x", "vx" to "vx*2")), true)
        r.tick(); r.tick()
        assertEquals(Vec3(2.0, 1.0, 0.0), r.particles.single().position)
        assertEquals(0.2, r.particles.single().attributes["vx"])
    }

    @Test fun `先施力后积分且 kill 会真正移除`() {
        val r = RecipeRuntime(recipe().copy(birth = mapOf("ax" to "0.1"), kill = "x >= 0.3"), true)
        r.tick(); r.tick(); assertEquals(0.1, r.particles.single().position.x, 1e-9)
        r.tick(); assertEquals(0.3, r.particles.single().position.x, 1e-9)
        r.tick(); assertTrue(r.finished)
    }

    @Test fun `state 在粒子之间隔离并逐刻保留`() {
        val r = RecipeRuntime(recipe(2).copy(state = mapOf("phase" to 0.0), motion = mapOf("phase" to "phase+1", "x" to "phase")), true)
        repeat(3) { r.tick() }
        assertEquals(listOf(2.0, 1.0), r.particles.map { it.attributes["phase"] })
        assertEquals(listOf(1.0, 0.0), r.particles.map { it.position.x })
    }

    @Test fun `let 可以分条推导变量且更新变量不重置粒子`() {
        val spec = recipe().copy(variables = mapOf("speed" to 0.1), lets = linkedMapOf("a1" to "speed*2", "a2" to "a1+speed"), motion = mapOf("vx" to "a2"))
        val r = RecipeRuntime(spec, true)
        r.tick(); r.tick(); assertEquals(0.3, r.particles.single().position.x, 1e-9)
        r.update(spec.copy(variables = mapOf("speed" to 0.2)))
        r.tick(); assertEquals(0.9, r.particles.single().position.x, 1e-9); assertEquals(2, r.particles.single().age)
    }

    @Test fun `条件修改和删除只作用于当前选中的成员`() {
        val r = RecipeRuntime(recipe(5, 5).copy(birth = mapOf("x" to "u*4")), false)
        r.tick()
        assertEquals(2, r.edit("x>2", mapOf("vy" to "0.2"), false))
        assertEquals(2, r.particles.count { it.attributes["vy"] == 0.2 })
        assertEquals(2, r.edit("x<2", emptyMap(), true))
        assertEquals(listOf(2.0, 3.0, 4.0), r.particles.map { it.position.x })
        r.tick(); assertEquals(8, r.particles.size)
        assertEquals(2, r.particles.count { it.attributes["vy"] == 0.2 })
    }

    @Test fun `公式可以使用向量和矩阵变换`() {
        val r = RecipeRuntime(recipe().copy(birth = mapOf("x" to "(mat3(vec3(0,1,0),vec3(1,0,0),vec3(0,0,1))*vec3(2,3,4)).x", "y" to "vec3(1,2,3).rotateZ(PI/2).y")), true)
        r.tick()
        assertEquals(3.0, r.particles.single().position.x, 1e-9)
        assertEquals(1.0, r.particles.single().position.y, 1e-9)
    }

    @Test fun `无限寿命必须显式给出且仍可用条件销毁`() {
        val r = RecipeRuntime(recipe().copy(lifetime = -1, kill = "age >= 100"), true)
        repeat(100) { r.tick() }; assertEquals(99, r.particles.single().age)
        r.tick(); assertTrue(r.finished)
    }

    @Test fun `非法配置与未知符号提前报错`() {
        for (r in listOf(recipe().copy(lifetime = 0), recipe().copy(rate = 0), recipe().copy(maxAlive = 0),
            recipe().copy(birth = mapOf("oops" to "1")), recipe().copy(motion = mapOf("vx" to "missing+1")),
            recipe().copy(variables = mapOf("x" to 2.0)), recipe().copy(variables = mapOf("same" to 1.0), state = mapOf("same" to 0.0)),
            recipe().copy(lets = linkedMapOf("first" to "second+1", "second" to "2")),
            recipe().copy(motion = mapOf("x" to "this.spawn()")))) {
            assertFailsWith<IllegalArgumentException> { r.validate() }
        }
    }

    @Test fun `运行错误不转换为默认粒子`() {
        val r = RecipeRuntime(recipe().copy(birth = mapOf("x" to "sqrt(-1)")), true)
        assertFailsWith<IllegalArgumentException> { r.tick() }
        assertTrue(r.particles.isEmpty())
    }

    @Test fun `回收上界同时考虑发射间隔和小存活容量`() {
        for (capacity in listOf(1, 2, 5)) for (interval in listOf(1, 7, 30)) for (rate in listOf(1, 8)) {
            val spec = recipe(9, rate).copy(maxAlive = capacity, interval = interval, lifetime = 2)
            val runtime = RecipeRuntime(spec, true)
            val budget = RecipeRuntime.retirementBudget(spec)
            while (!runtime.finished && runtime.ticks <= budget) runtime.tick()
            assertTrue(runtime.finished, "capacity=$capacity interval=$interval rate=$rate 超过了 $budget")
            assertEquals(9L, runtime.cursor)
        }
    }

    @Test fun `初始位置保存出生公式的结果而非原点占位样本`() {
        val runtime = RecipeRuntime(recipe(2, 2).copy(birth = mapOf("x" to "2+u", "z" to "1"),
            motion = mapOf("x" to "dx*cos(PI/2)-dz*sin(PI/2)", "z" to "dx*sin(PI/2)+dz*cos(PI/2)")), true)
        runtime.tick(); runtime.tick()
        runtime.particles.forEach { assertEquals(-1.0, it.position.x, 1e-9) }
        assertEquals(2.0, runtime.particles[0].position.z, 1e-9)
        assertEquals(3.0, runtime.particles[1].position.z, 1e-9)
        runtime.tick()
        assertEquals(2.0, runtime.particles[0].position.z, 1e-9)
    }
}
