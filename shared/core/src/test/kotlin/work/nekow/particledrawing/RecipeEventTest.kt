package work.nekow.particledrawing

import work.nekow.particledrawing.animation.recipe.*
import work.nekow.particledrawing.api.ParticleEvent
import work.nekow.particledrawing.api.ParticleRecipe
import work.nekow.particledrawing.api.ParticleSampling
import work.nekow.particledrawing.api.RecipeEmission
import work.nekow.particledrawing.math.ResourceId
import work.nekow.particledrawing.math.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecipeEventTest {
    private val seeds = ParticleSampling.count(1)
    private val iron = ResourceId.parse("minecraft:iron_block")
    private val childRecipe = ParticleRecipe(seeds, lifetime = 12, birth = mapOf(
        "vx" to "event_nx * abs(event_vy)",
        "vy" to "event_ny * abs(event_vy)",
    ))

    @Test fun `接触事件在两个求值路径使用真实落点与命中材质`() {
        for (preferFast in listOf(true, false)) {
            val position = Vec3(4.0, 2.0, -3.0)
            val impact = position.add(0.0, -1.0, 0.0)
            val normal = Vec3(0.0, 1.0, 0.0)
            val world = ParticleWorld { _, _, _ -> SurfaceHit(impact, normal, material = iron) }
            val matched = RecipeEmission(ParticleEvent.CONTACT, childRecipe, iron, removeParent = true)
            val wrongMaterial = matched.copy(material = ResourceId.parse("minecraft:stone"))
            val runtime = RecipeRuntime(ParticleRecipe(
                seeds, birth = mapOf("vy" to "-2"), collision = RecipeCollision.SOLID,
                emissions = listOf(wrongMaterial, matched),
            ), burst = true, preferFast = preferFast, world = world)

            runtime.tickInFrame(RecipeFrame(position))
            assertTrue(runtime.events.isEmpty())
            runtime.tickInFrame(RecipeFrame(position))
            assertTrue(runtime.particles.isEmpty())
            val trigger = runtime.events.single()
            assertEquals(matched, trigger.rule)
            assertEquals(impact, trigger.input.position)
            assertEquals(iron, trigger.input.material)
            assertEquals(-2.0, trigger.input.velocity.y)

            val child = RecipeRuntime(childRecipe, burst = true, preferFast = preferFast)
            child.emitting = false
            assertEquals(1, child.emitAt(RecipeFrame(impact), trigger.input, 1))
            assertEquals(2.0, child.particles.single().value("vy"))
            child.tick()
            val droplet = child.particles.single()
            assertEquals(impact.add(0.0, 2.0, 0.0), child.frameOf(droplet).position(droplet.x, droplet.y, droplet.z))
        }
    }

    @Test fun `持续撞击默认只触发一次 管理删除不产生死亡事件`() {
        val runtime = RecipeRuntime(ParticleRecipe(
            seeds, motion = mapOf("y" to "-1"), collision = RecipeCollision.SOLID,
            emissions = listOf(RecipeEmission(ParticleEvent.CONTACT, childRecipe), RecipeEmission(ParticleEvent.DEATH, childRecipe)),
        ), burst = true, world = ParticleWorld { _, _, _ -> SurfaceHit(Vec3.ZERO, Vec3(0.0, 1.0, 0.0)) })
        runtime.tickInFrame(RecipeFrame(Vec3(0.0, 1.0, 0.0)))
        runtime.tickInFrame(RecipeFrame(Vec3(0.0, 1.0, 0.0)))
        assertEquals(ParticleEvent.CONTACT, runtime.events.single().rule.event)
        runtime.tickInFrame(RecipeFrame(Vec3(0.0, 1.0, 0.0)))
        assertTrue(runtime.events.isEmpty())
        runtime.edit("true", emptyMap(), remove = true)
        assertTrue(runtime.events.isEmpty())
        assertTrue(runtime.finished)
    }

    @Test fun `出生筛选读取变换后的位置 快慢路径和现存成员操作一致`() {
        val requested = mutableListOf<Pair<Vec3, Set<ParticleWorldField>>>()
        val world = object : ParticleWorld {
            override fun cast(mode: RecipeCollision, from: Vec3, to: Vec3): SurfaceHit? = null
            override fun sample(position: Vec3, fields: Set<ParticleWorldField>): ParticleWorldSample {
                requested += position to fields
                return ParticleWorldSample(loaded = true, openSky = position.x >= 6, blockLight = 12)
            }
        }
        for (preferFast in listOf(true, false)) {
            requested.clear()
            val runtime = RecipeRuntime(ParticleRecipe(
                seeds, birth = mapOf("x" to "2"), where = "open_sky && world_x == 6",
                motion = mapOf("x" to "x + 1"),
            ), burst = true, preferFast = preferFast, world = world)
            runtime.tickInFrame(RecipeFrame(Vec3(4.0, 0.0, 0.0)))
            assertEquals(2.0, runtime.particles.single().x)
            assertEquals(Vec3(6.0, 0.0, 0.0), requested.last().first)
            assertEquals(setOf(ParticleWorldField.OPEN_SKY), requested.last().second)
            assertEquals(1, runtime.edit("block_light == 12", emptyMap(), remove = false))
            assertEquals(setOf(ParticleWorldField.BLOCK_LIGHT), requested.last().second)
        }
        requested.clear()
        RecipeRuntime(ParticleRecipe(seeds), burst = true, world = world).tick()
        assertTrue(requested.isEmpty())
    }

    @Test fun `出生和自然死亡事件可以选用不同配方`() {
        val birth = RecipeEmission(ParticleEvent.BIRTH, childRecipe)
        val death = RecipeEmission(ParticleEvent.DEATH, childRecipe.copy(lifetime = 2))
        val runtime = RecipeRuntime(ParticleRecipe(seeds, lifetime = 1, emissions = listOf(birth, death)), burst = true)
        runtime.tick()
        assertEquals(birth, runtime.events.single().rule)
        assertFalse(runtime.finished)
        runtime.tick()
        assertEquals(death, runtime.events.single().rule)
        assertTrue(runtime.finished)
    }

    @Test fun `递归和无界子配方被拒绝`() {
        val root = ParticleRecipe(seeds)
        for (child in listOf(childRecipe.copy(lifetime = -1),
            childRecipe.copy(emissions = listOf(RecipeEmission(ParticleEvent.BIRTH, childRecipe))))) {
            assertFailsWith<IllegalArgumentException> {
                root.copy(emissions = listOf(RecipeEmission(ParticleEvent.BIRTH, child))).validate()
            }
        }
    }
}
