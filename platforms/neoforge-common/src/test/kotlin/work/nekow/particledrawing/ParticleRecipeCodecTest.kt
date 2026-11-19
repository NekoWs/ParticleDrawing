package work.nekow.particledrawing

import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.animation.recipe.ParticleEntityQuery
import work.nekow.particledrawing.animation.recipe.ParticleProbe
import work.nekow.particledrawing.animation.recipe.RecipeCollision
import work.nekow.particledrawing.api.*
import work.nekow.particledrawing.core.network.*
import work.nekow.particledrawing.math.ResourceId
import work.nekow.particledrawing.math.Vec3 as CoreVec3
import java.util.UUID
import kotlin.test.*

class ParticleRecipeCodecTest {
    private fun roundTrip(p: ParticleRecipePayload): ParticleRecipePayload {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        try {
            ParticleRecipePayload.STREAM_CODEC.encode(buf, p)
            val result = ParticleRecipePayload.STREAM_CODEC.decode(buf)
            assertEquals(0, buf.readableBytes())
            return result
        } finally { buf.release() }
    }

    @Test fun `完整声明保留图片颜色公式顺序与锚点身份`() {
        val source = ParticleRecipe(listOf(ParticleSeed(Vec3(1.0, 2.0, 3.0), Color.CYAN, 0.7)), rate = 20, interval = 2,
            lifetime = 40, maxAlive = 100, follow = true, variables = linkedMapOf("radius" to 2.0), state = mapOf("phase" to 0.0),
            lets = linkedMapOf("a1" to "radius*2", "a2" to "a1+1"), birth = mapOf("x" to "u"), motion = mapOf("vx" to "-x*0.1"),
            where = "x>0", kill = "age>30", visual = ParticleVisual().texture("particle:minecraft:end_rod").anisoWorld(0.1f, 0.2f).glowing(true),
            lifeCurve = ParticleLifeCurve.EMPTY.plus(ParticleCurve.alpha(CurveKey(0f, 1f), CurveKey(40f, 0f))))
        val id = UUID.randomUUID(); val entity = UUID.randomUUID()
        val result = roundTrip(ParticleRecipePayload(id, RecipeOperation.START, source, RecipeAnchor(Vec3.ZERO, entity, 42, Vec3(0.0, 2.0, 0.0), true), true))
        val r = assertNotNull(result.recipe)
        r.validate()
        assertEquals(id, result.id); assertTrue(result.burst)
        assertEquals(source.seeds, r.seeds); assertEquals(source.motion, r.motion); assertEquals(source.lets.keys.toList(), r.lets.keys.toList())
        assertEquals(40, r.lifetime); assertEquals(2, r.interval); assertTrue(r.follow)
        assertEquals(entity, result.anchor?.entity); assertEquals(42, result.anchor?.entityId)
        assertEquals("particle:minecraft:end_rod", r.visual.texture)
        assertEquals(1, r.lifeCurve?.curves?.size)
    }

    @Test fun `操作类型和筛选修改都独立往返`() {
        val id = UUID.randomUUID()
        for (op in listOf(RecipeOperation.STOP, RecipeOperation.REMOVE)) assertEquals(op, roundTrip(ParticleRecipePayload(id, op)).operation)
        val edit = roundTrip(ParticleRecipePayload(id, RecipeOperation.EDIT, predicate = "age>20", changes = linkedMapOf("vx" to "-x", "vy" to "-y")))
        assertEquals("age>20", edit.predicate); assertEquals(listOf("vx", "vy"), edit.changes.keys.toList())
        assertTrue(roundTrip(ParticleRecipePayload(id, RecipeOperation.EDIT, deleteSelected = true)).deleteSelected)
        val anchor = RecipeAnchor(Vec3(40.0, -58.0, 20.0))
        assertEquals(anchor, roundTrip(ParticleRecipePayload(id, RecipeOperation.ANCHOR, anchor = anchor)).anchor)
    }

    @Test fun `声明超限在分配大列表前拒绝`() {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        try {
            buf.writeUUID(UUID.randomUUID()); buf.writeVarInt(RecipeOperation.START.ordinal); buf.writeVarInt(ParticleRecipe.MAX_SEEDS + 1)
            assertFailsWith<IllegalArgumentException> { ParticleRecipePayload.STREAM_CODEC.decode(buf) }
        } finally { buf.release() }
    }

    @Test fun `变量热更新只发送变量不重发采样点`() {
        val p = ParticleRecipePayload(UUID.randomUUID(), RecipeOperation.VARIABLE, variables = mapOf("radius" to 3.5))
        val buf = FriendlyByteBuf(Unpooled.buffer())
        try {
            ParticleRecipePayload.STREAM_CODEC.encode(buf, p)
            assertTrue(buf.readableBytes() < 64)
        } finally { buf.release() }
        val result = roundTrip(p)
        assertEquals(mapOf("radius" to 3.5), result.variables)
        assertNull(result.recipe)
    }
    @Test fun `碰撞配置随配方往返`() {
        val source = ParticleRecipe(listOf(ParticleSeed(Vec3.ZERO)), collision = RecipeCollision.SOLID, bounce = 0.25, friction = 0.8)
        val result = roundTrip(ParticleRecipePayload(UUID.randomUUID(), RecipeOperation.START, source, RecipeAnchor(Vec3.ZERO)))
        val r = assertNotNull(result.recipe)
        assertEquals(RecipeCollision.SOLID, r.collision)
        assertEquals(0.25, r.bounce, 1e-9)
        assertEquals(0.8, r.friction, 1e-9)
    }

    @Test fun `默认配方保持不碰撞`() {
        val source = ParticleRecipe(listOf(ParticleSeed(Vec3.ZERO)))
        val result = roundTrip(ParticleRecipePayload(UUID.randomUUID(), RecipeOperation.START, source, RecipeAnchor(Vec3.ZERO)))
        val r = assertNotNull(result.recipe)
        assertEquals(RecipeCollision.NONE, r.collision)
    }
    @Test fun `子发射配置随配方往返`() {
        val source = ParticleRecipe(listOf(ParticleSeed(Vec3.ZERO)), subEmit = SubEmit(count = 12, lifetime = 8, speed = 0.3, scale = 0.4))
        val result = roundTrip(ParticleRecipePayload(UUID.randomUUID(), RecipeOperation.START, source, RecipeAnchor(Vec3.ZERO)))
        val sub = assertNotNull(assertNotNull(result.recipe).subEmit)
        assertEquals(12, sub.count)
        assertEquals(8, sub.lifetime)
        assertEquals(0.3, sub.speed, 1e-9)
        assertEquals(0.4, sub.scale, 1e-9)
    }

    @Test fun `没有子发射时往返也是空`() {
        val source = ParticleRecipe(listOf(ParticleSeed(Vec3.ZERO)))
        val result = roundTrip(ParticleRecipePayload(UUID.randomUUID(), RecipeOperation.START, source, RecipeAnchor(Vec3.ZERO)))
        assertEquals(null, assertNotNull(result.recipe).subEmit)
    }

    @Test fun `接触规则与子配方随声明往返`() {
        val seeds = ParticleSampling.count(3).map { it.copy(normal = CoreVec3(0.0, 1.0, 0.0)) }
        val child = ParticleRecipe(seeds, lifetime = 13, birth = mapOf("vy" to "event_ny * abs(event_vy)"))
        val rule = RecipeEmission(ParticleEvent.CONTACT, child, ResourceId.parse("minecraft:iron_block"), removeParent = true, decalRadius = 0.5)
        val source = ParticleRecipe(
            seeds, collision = RecipeCollision.ALL, collisionHalfSize = 0.5, collisionSteps = 3,
            emissions = listOf(rule), spacing = 1.25, entityQuery = ParticleEntityQuery(6.0, ResourceId.parse("minecraft:bee")),
            neighborRadius = 1.5, probes = listOf(ParticleProbe(
                6.0, RecipeCollision.SOLID, CoreVec3(1.0, 0.0, 0.0), ResourceId.parse("minecraft:iron_block"))),
            simulationRange = 20.0,
        )
        val payload = roundTrip(ParticleRecipePayload(UUID.randomUUID(), RecipeOperation.START, source, RecipeAnchor(Vec3.ZERO)))
        val recipe = assertNotNull(payload.recipe)
        recipe.validate()
        val decoded = recipe.emissions.single()
        assertEquals(RecipeCollision.ALL, recipe.collision)
        assertEquals(0.5, recipe.collisionHalfSize)
        assertEquals(3, recipe.collisionSteps)
        assertEquals(source.entityQuery, recipe.entityQuery)
        assertEquals(source.neighborRadius, recipe.neighborRadius)
        assertEquals(source.probes, recipe.probes)
        assertEquals(source.simulationRange, recipe.simulationRange)
        assertEquals(1.25, recipe.spacing)
        assertEquals(rule.material, decoded.material)
        assertEquals(rule.event, decoded.event)
        assertTrue(decoded.removeParent)
        assertEquals(0.5, decoded.decalRadius)
        assertEquals(child.birth, decoded.child.birth)
        assertEquals(child.seeds, decoded.child.seeds)
    }
}
