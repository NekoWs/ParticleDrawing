package work.nekow.particledrawing

import work.nekow.particledrawing.animation.recipe.CollisionMath
import work.nekow.particledrawing.animation.recipe.ParticleWorld
import work.nekow.particledrawing.animation.recipe.RecipeCollision
import work.nekow.particledrawing.animation.recipe.RecipeRuntime
import work.nekow.particledrawing.api.ParticleRecipe
import work.nekow.particledrawing.api.ParticleSeed
import work.nekow.particledrawing.math.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 碰撞：公式算不出方块信息，所以这块是独立能力。这里钉住两件事：
 * 响应本身（回退哪个轴、速度如何衰减）以及配方运行时的落地表现。
 */
class ParticleCollisionTest {

    /** 地面在 y = [floor]，可选再放一堵 x >= [wall] 的墙。 */
    private class FakeWorld(val floor: Double = 0.0, val wall: Double = Double.MAX_VALUE) : ParticleWorld {
        override fun isSolid(x: Double, y: Double, z: Double) = y <= floor || x >= wall
        override fun isLiquid(x: Double, y: Double, z: Double) = y <= floor
    }

    private fun state(x: Double, y: Double, z: Double, vx: Double, vy: Double, vz: Double) =
        doubleArrayOf(x, y, z, vx, vy, vz)

    @Test
    fun `落地时回退竖直位置并反弹`() {
        val world = FakeWorld(floor = 0.0)
        // 上一步在 y=0.2，这一步落到 y=-0.3：撞进地面
        val s = state(0.0, -0.3, 0.0, 0.0, -0.5, 0.0)
        assertTrue(CollisionMath.resolve(RecipeCollision.SOLID, world, 0.0, 0.2, 0.0, s, 0.5, 0.7))
        assertEquals(0.2, s[CollisionMath.POS_Y], 1e-9)
        assertEquals(0.25, s[CollisionMath.VEL_Y], 1e-9)
    }

    @Test
    fun `反弹为零时直接停在地面`() {
        val world = FakeWorld()
        val s = state(0.0, -0.3, 0.0, 0.0, -0.5, 0.0)
        CollisionMath.resolve(RecipeCollision.SOLID, world, 0.0, 0.2, 0.0, s, 0.0, 1.0)
        assertEquals(0.2, s[CollisionMath.POS_Y], 1e-9)
        assertEquals(0.0, s[CollisionMath.VEL_Y], 1e-9)
    }

    @Test
    fun `落地时按摩擦削减水平速度`() {
        val world = FakeWorld()
        val s = state(0.0, -0.3, 0.0, 0.8, -0.5, 0.4)
        CollisionMath.resolve(RecipeCollision.SOLID, world, 0.0, 0.2, 0.0, s, 0.4, 0.5)
        assertEquals(0.4, s[CollisionMath.VEL_X], 1e-9)
        assertEquals(0.2, s[CollisionMath.VEL_Z], 1e-9)
    }

    @Test
    fun `撞墙时回退水平位置并反射水平速度`() {
        val world = FakeWorld(floor = -100.0, wall = 1.0)
        // 上一步 x=0.8，这一步 x=1.4：撞墙
        val s = state(1.4, 0.0, 0.0, 0.6, 0.0, 0.0)
        assertTrue(CollisionMath.resolve(RecipeCollision.SOLID, world, 0.8, 0.0, 0.0, s, 0.5, 1.0))
        assertEquals(0.8, s[CollisionMath.POS_X], 1e-9)
        assertEquals(-0.3, s[CollisionMath.VEL_X], 1e-9)
    }

    @Test
    fun `悬空时不改动任何值`() {
        val world = FakeWorld()
        val s = state(0.0, 5.0, 0.0, 0.1, -0.2, 0.3)
        val copy = s.copyOf()
        assertFalse(CollisionMath.resolve(RecipeCollision.SOLID, world, 0.0, 5.1, 0.0, s, 0.5, 0.5))
        assertTrue(s.contentEquals(copy))
    }

    @Test
    fun `不碰撞模式下永远不响应`() {
        val world = FakeWorld()
        val s = state(0.0, -5.0, 0.0, 0.0, -0.5, 0.0)
        assertFalse(CollisionMath.resolve(RecipeCollision.NONE, world, 0.0, 5.0, 0.0, s, 0.5, 0.5))
    }

    @Test
    fun `液体模式只对液体生效`() {
        val world = FakeWorld(floor = 0.0)
        // 地面同时是「实心」和「液体」，但实心模式与液体模式都应各自能挡住
        val solid = state(0.0, -0.3, 0.0, 0.0, -0.5, 0.0)
        assertTrue(CollisionMath.resolve(RecipeCollision.LIQUID, world, 0.0, 0.2, 0.0, solid, 0.0, 1.0))
        assertEquals(0.2, solid[CollisionMath.POS_Y], 1e-9)
    }

    @Test
    fun `配方打开碰撞后粒子不会陷进地面`() {
        val world = FakeWorld(floor = 1.0)
        val recipe = ParticleRecipe(
            listOf(ParticleSeed(Vec3(0.0, 3.0, 0.0))),
            rate = 1,
            lifetime = 60,
            birth = mapOf("vy" to "-0.4"),
            collision = RecipeCollision.SOLID,
        )
        val runtime = RecipeRuntime(recipe, burst = true, world = world)
        repeat(20) { runtime.tick() }
        assertTrue(runtime.particles.isNotEmpty(), "粒子应当还在")
        for (particle in runtime.particles) {
            assertTrue(particle.position.y >= 1.0 - 1e-9, "粒子陷进了地面：${particle.position.y}")
        }
    }

    @Test
    fun `没有世界查询时碰撞配置不生效`() {
        val recipe = ParticleRecipe(
            listOf(ParticleSeed(Vec3(0.0, 3.0, 0.0))),
            rate = 1,
            lifetime = 60,
            birth = mapOf("vy" to "-0.4"),
            collision = RecipeCollision.SOLID,
        )
        val runtime = RecipeRuntime(recipe, burst = true)
        repeat(20) { runtime.tick() }
        val lowest = runtime.particles.minOfOrNull { it.position.y } ?: return
        assertTrue(lowest < 1.0, "没有世界时应当自由下落，实际最低 $lowest")
    }
}
