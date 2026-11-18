package work.nekow.particledrawing

import work.nekow.particledrawing.animation.recipe.CollisionMath
import work.nekow.particledrawing.animation.recipe.ParticleWorld
import work.nekow.particledrawing.animation.recipe.RecipeCollision
import work.nekow.particledrawing.animation.recipe.RecipeFrame
import work.nekow.particledrawing.animation.recipe.RecipeRuntime
import work.nekow.particledrawing.animation.recipe.SurfaceHit
import work.nekow.particledrawing.api.ParticleRecipe
import work.nekow.particledrawing.api.ParticleSeed
import work.nekow.particledrawing.math.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ParticleCollisionTest {
    @Test
    fun `在首个碰撞面分开处理法线和切向速度`() {
        val hit = assertNotNull(CollisionMath.resolve(
            RecipeCollision.SOLID, TestSurface(0.0), Vec3(0.0, 0.2, 0.0), Vec3(0.8, -0.3, 0.4),
            Vec3(0.8, -0.5, 0.4), bounce = 0.5, friction = 0.5,
        ))
        assertEquals(CollisionMath.SURFACE_MARGIN, hit.position.y, 1e-9)
        assertEquals(0.32, hit.position.x, 1e-9)
        assertEquals(Vec3(0.4, 0.25, 0.2), hit.velocity)
    }

    @Test
    fun `快速穿过薄液层仍从入射面反弹`() {
        val surface = TestSurface(0.0, RecipeCollision.LIQUID, bottom = -0.125)
        val from = Vec3(0.0, 2.0, 0.0)
        val to = Vec3(0.0, -3.0, 0.0)
        val v = Vec3(0.0, -5.0, 0.0)
        assertNotNull(CollisionMath.resolve(RecipeCollision.LIQUID, surface, from, to, v, 0.5, 1.0))
        assertNull(CollisionMath.resolve(RecipeCollision.SOLID, surface, from, to, v, 0.5, 1.0))
        assertNull(CollisionMath.resolve(RecipeCollision.NONE, surface, from, to, v, 0.5, 1.0))
    }

    @Test
    fun `方块内部出生可以离开且过长步长会报错`() {
        val world = TestSurface(0.0)
        val from = Vec3(0.0, -0.2, 0.0)
        val to = Vec3(0.0, 0.3, 0.0)
        assertNull(CollisionMath.resolve(RecipeCollision.SOLID, world, from, to, to.subtract(from), 0.5, 1.0))
        assertFailsWith<IllegalArgumentException> {
            CollisionMath.resolve(RecipeCollision.SOLID, world, from, Vec3(0.0, 65.0, 0.0), to, 0.5, 1.0)
        }
    }

    @Test
    fun `显式位置在快慢路径均从上一位置扫过平移后的地面`() {
        for (fast in listOf(true, false)) {
            val recipe = ParticleRecipe(
                listOf(ParticleSeed(Vec3(0.0, 2.0, 0.0))),
                collision = RecipeCollision.SOLID, motion = mapOf("y" to "-3"),
            )
            val runtime = RecipeRuntime(recipe, burst = true, preferFast = fast, world = TestSurface(10.0))
            val anchor = RecipeFrame(Vec3(4.0, 10.0, -8.0))
            runtime.tickInFrame(anchor)
            runtime.tickInFrame(anchor)
            assertEquals(CollisionMath.SURFACE_MARGIN, runtime.particles.single().y, 1e-9)
        }
    }

    @Test
    fun `局部速度在旋转锚点下按世界法线反弹`() {
        val recipe = ParticleRecipe(listOf(ParticleSeed(Vec3.ZERO)), collision = RecipeCollision.SOLID,
            birth = mapOf("vy" to "-1"), bounce = 0.5)
        val anchor = RecipeFrame(Vec3(8.0, 0.3, 4.0), yaw = 90f, pitch = 30f, local = true)
        val runtime = RecipeRuntime(recipe, burst = true, world = TestSurface(0.0))
        runtime.tickInFrame(anchor)
        runtime.tickInFrame(anchor)
        val particle = runtime.particles.single()
        assertEquals(CollisionMath.SURFACE_MARGIN, anchor.position(particle.x, particle.y, particle.z).y, 1e-7)
        val worldVelocity = anchor.velocity(Vec3(particle.value("vx"), particle.value("vy"), particle.value("vz")))
        assertEquals(0.5 * kotlin.math.cos(Math.toRadians(30.0)), worldVelocity.y, 1e-7)
    }

    @Test
    fun `跟随锚点从出生时的位置开始做世界碰撞`() {
        val recipe = ParticleRecipe(listOf(ParticleSeed(Vec3.ZERO)), collision = RecipeCollision.SOLID, follow = true)
        val runtime = RecipeRuntime(recipe, burst = true, world = TestSurface(0.0))
        runtime.tickInFrame(RecipeFrame(Vec3(0.0, 1.0, 0.0)))
        runtime.tickInFrame(RecipeFrame(Vec3(0.0, -1.0, 0.0)))
        assertEquals(1.0 + CollisionMath.SURFACE_MARGIN, runtime.particles.single().y, 1e-9)
    }

    @Test
    fun `移除列表只记录本次离开的成员`() {
        val runtime = RecipeRuntime(ParticleRecipe(listOf(ParticleSeed(Vec3.ZERO)), lifetime = 2), burst = true)
        runtime.tick()
        val particle = runtime.particles.single()
        runtime.tick()
        assertEquals(emptyList(), runtime.removedParticles)
        runtime.tick()
        assertEquals(listOf(particle), runtime.removedParticles)
        runtime.tick()
        assertEquals(emptyList(), runtime.removedParticles)
    }
}

internal class TestSurface(
    private val top: Double,
    private val material: RecipeCollision = RecipeCollision.SOLID,
    private val bottom: Double = Double.NEGATIVE_INFINITY,
) : ParticleWorld {
    override fun cast(mode: RecipeCollision, from: Vec3, to: Vec3): SurfaceHit? {
        if (mode != material) return null
        if (from.y in bottom..top) return SurfaceHit(from, UP, inside = true)
        if (from.y <= top || to.y > top) return null
        return SurfaceHit(from.add(to.subtract(from).scale((from.y - top) / (from.y - to.y))), UP)
    }

    private companion object {
        val UP = Vec3(0.0, 1.0, 0.0)
    }
}
