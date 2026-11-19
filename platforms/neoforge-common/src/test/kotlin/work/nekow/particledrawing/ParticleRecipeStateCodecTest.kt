package work.nekow.particledrawing

import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import work.nekow.particledrawing.animation.recipe.RecipeDecal
import work.nekow.particledrawing.animation.recipe.RecipeLook
import work.nekow.particledrawing.animation.recipe.SyncedRecipe
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.api.CurveKey
import work.nekow.particledrawing.api.ParticleCurve
import work.nekow.particledrawing.api.ParticleLifeCurve
import work.nekow.particledrawing.api.ParticleVisual
import work.nekow.particledrawing.core.network.ParticleRecipeStatePayload
import work.nekow.particledrawing.core.network.ParticleRecipeStatePayload.Content
import work.nekow.particledrawing.core.network.RecipeStateRow
import work.nekow.particledrawing.math.Vec3
import java.util.UUID
import kotlin.test.*

class ParticleRecipeStateCodecTest {
    private val id = UUID(2, 4)

    @Test fun `最大首帧保留完整尾迹且独立增量帧保持紧凑`() {
        val trail = List(ParticleVisual.MAX_TRAIL_TICKS + 1) { Vec3(it / 3.0, it.toDouble(), -300.0) }
        val decal = RecipeDecal(trail.first(), Vec3(0.0, 1.0, 0.0), 0.75, true)
        val rows = List(SyncedRecipe.MAX_PARTICLES) {
            RecipeStateRow(it.toLong(), 7, trail.last(), Color.ORANGE, 0.75f, 38, 51, true, 12, decal, trail)
        }
        for (rs in listOf(rows, rows.map { it.copy(history = emptyList()) })) {
            val original = ParticleRecipeStatePayload(id, Content.Frame(100L, rs))
            val buf = FriendlyByteBuf(Unpooled.buffer())
            try {
                ParticleRecipeStatePayload.CODEC.encode(buf, original)
                assertTrue(buf.readableBytes() < if (rs === rows) 128 * 1024 else 16 * 1024)
                assertEquals(original, ParticleRecipeStatePayload.CODEC.decode(buf))
                assertEquals(0, buf.readableBytes())
            } finally { buf.release() }
        }
    }

    @Test fun `外观与寿命曲线独立于逐刻位置传输`() {
        val curve = ParticleLifeCurve(listOf(ParticleCurve.alpha(CurveKey(0f, 1f), CurveKey(40f, 0f))))
        val visual = ParticleVisual().apply { trailTicks = 16 }
        val buf = FriendlyByteBuf(Unpooled.buffer())
        try {
            val original = ParticleRecipeStatePayload(id, Content.Look(16, RecipeLook(visual, curve)))
            ParticleRecipeStatePayload.CODEC.encode(buf, original)
            val decoded = ParticleRecipeStatePayload.CODEC.decode(buf)
            assertEquals(id, decoded.id)
            val look = assertIs<Content.Look>(decoded.content)
            assertEquals(16L, look.id)
            assertEquals(16, look.look.visual.trailTicks)
            assertEquals(curve.curves.single().keys.map { it.value }, look.look.curve?.curves?.single()?.keys?.map { it.value })
        } finally { buf.release() }
    }

    @Test fun `超限成员数量在读数据前拒绝`() {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        try {
            buf.writeUUID(id); buf.writeBoolean(false); buf.writeVarLong(100L)
            buf.writeVarInt(SyncedRecipe.MAX_PARTICLES + 1)
            assertFailsWith<IllegalArgumentException> { ParticleRecipeStatePayload.CODEC.decode(buf) }
        } finally { buf.release() }
    }
}
