package work.nekow.particledrawing

import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.*
import work.nekow.particledrawing.core.network.*
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
}
