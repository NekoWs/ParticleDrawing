package work.nekow.particledrawing

import work.nekow.particledrawing.animation.recipe.ParticleWorld
import work.nekow.particledrawing.animation.recipe.RecipeCollision
import work.nekow.particledrawing.animation.recipe.RecipeFrame
import work.nekow.particledrawing.animation.recipe.SurfaceHit
import work.nekow.particledrawing.animation.recipe.SyncedRecipe
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.api.ParticleEvent
import work.nekow.particledrawing.api.ParticleRecipe
import work.nekow.particledrawing.api.ParticleSeed
import work.nekow.particledrawing.api.ParticleTrailStyle
import work.nekow.particledrawing.api.ParticleVisual
import work.nekow.particledrawing.api.RecipeEmission
import work.nekow.particledrawing.i18n.LocalizedFailure
import work.nekow.particledrawing.math.Vec3
import work.nekow.particledrawing.render.RibbonHistory
import kotlin.test.*

class SyncedRecipeTest {
    private val floor = ParticleWorld { _, from, to ->
        if (from.y > 0.0 && to.y <= 0.0) {
            SurfaceHit(from.lerp(to, from.y / (from.y - to.y)), Vec3(0.0, 1.0, 0.0))
        } else null
    }
    private val seed = ParticleSeed(Vec3.ZERO)
    private val recipe = ParticleRecipe(listOf(seed), rate = 1, maxAlive = 8,
        lifetime = 12, visual = ParticleVisual().apply { trailTicks = 6 })

    @Test fun `晚加入的快照保留撞击前后的路径和已出生的子粒子`() {
        val child = recipe.copy(
            lifetime = 8,
            birth = mapOf("r" to "1", "g" to "0", "b" to "0", "vx" to "event_vx"),
        )
        val source = SyncedRecipe(
            recipe.copy(collision = RecipeCollision.SOLID, bounce = 0.75, friction = 0.0,
                birth = mapOf("vx" to "0.3", "vy" to "-0.75"),
                emissions = listOf(RecipeEmission(ParticleEvent.CONTACT, child, decalRadius = 0.5, wrapDecal = true))),
            burst = true, world = floor,
        )
        val anchor = RecipeFrame(Vec3(0.0, 1.0, 0.0))
        repeat(4) { source.tick(anchor) }
        val keyframe = source.snapshot(includeHistory = true)
        assertEquals(4L, keyframe.tick)
        val parent = keyframe.particles.single { it.color == Color.WHITE }
        val event = keyframe.particles.single { it.color == Color.RED }
        assertEquals(3, parent.age)
        assertEquals(1, event.age)
        assertEquals(1.0, parent.history.first().y)
        assertTrue(parent.history[2].y < parent.history[1].y)
        assertTrue(parent.position.y > parent.history[2].y)
        assertEquals(parent.position, parent.history.last())
        val decal = assertNotNull(event.decal)
        assertEquals(0.0, decal.position.y)
        assertTrue(decal.wrap)
        assertTrue(event.position.x > event.history.first().x)

        val lateTrail = RibbonHistory(6, parent.position).apply { restore(parent.history) }
        source.tick(anchor)
        val update = source.snapshot(includeHistory = false).particles.single { it.id == parent.id }
        assertTrue(update.history.isEmpty())
        lateTrail.record(update.position)
        assertEquals(source.snapshot(true).particles.single { it.id == parent.id }.history, lateTrail.oldestFirst())
        assertEquals(4, parent.history.size)

        source.edit("true", emptyMap(), remove = true)
        repeat(2) { source.tick(anchor) }
        assertEquals(event.id, source.snapshot(true).particles.single().id)
        repeat(8) { source.tick(anchor) }
        assertTrue(source.finished)
        assertTrue(source.snapshot(true).particles.isEmpty())
    }

    @Test fun `持续配方停发后仍保存现存成员直到自然结束`() {
        val source = SyncedRecipe(recipe, burst = false, world = floor)
        repeat(3) { source.tick(RecipeFrame.IDENTITY) }
        source.runtime.emitting = false
        repeat(5) { source.tick(RecipeFrame.IDENTITY) }
        assertEquals(listOf(7, 6, 5), source.snapshot(true).particles.map { it.age })
        assertFalse(source.finished)
        repeat(7) { source.tick(RecipeFrame.IDENTITY) }
        assertTrue(source.finished)
    }

    @Test fun `同步上限和客户端专用选项在启动前报错`() {
        for (bad in listOf(recipe.copy(rate = 65), recipe.copy(maxAlive = 65), recipe.copy(simulationRange = 32.0))) {
            assertFailsWith<IllegalArgumentException> { SyncedRecipe.validate(bad) }.also { assertIs<LocalizedFailure>(it) }
        }
    }

    @Test fun `主粒子结束后晚加入者仍可看到渐隐尾迹`() {
        val visual = recipe.visual.copy().apply { trailStyle = ParticleTrailStyle(lingerTicks = 4) }
        val source = SyncedRecipe(recipe.copy(visual = visual, lifetime = 4, birth = mapOf("vx" to "0.5")), true, floor)
        repeat(5) { source.tick(RecipeFrame.IDENTITY) }
        val first = source.snapshot(true).particles.single()
        assertEquals(0, first.trailAge)
        assertEquals(0, first.light)
        assertTrue(first.history.first().x < first.history.last().x)
        assertEquals(first.position, first.history.last())
        assertEquals(first.history, source.activeTrails().getValue(first.id).oldestFirst())
        repeat(3) { source.tick(RecipeFrame.IDENTITY) }
        val late = source.snapshot(true).particles.single()
        assertEquals(first.id, late.id)
        assertEquals(first.history, late.history)
        assertEquals(3, late.trailAge)
        assertFalse(source.finished)
        source.tick(RecipeFrame.IDENTITY)
        assertTrue(source.finished)
    }
}
