package work.nekow.particledrawing.core.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import work.nekow.particledrawing.animation.recipe.RecipeDecal
import work.nekow.particledrawing.animation.recipe.RecipeLook
import work.nekow.particledrawing.animation.recipe.RecipeParticleSample
import work.nekow.particledrawing.animation.recipe.SyncedRecipe
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.api.ParticleRecipe
import work.nekow.particledrawing.api.ParticleVisual
import work.nekow.particledrawing.api.RecipeEmission
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.translation
import work.nekow.particledrawing.math.Vec3
import work.nekow.particledrawing.platform.toCore
import java.util.UUID

internal data class RecipeStateRow(
    val id: Long, val lookId: Long, val position: Vec3, val color: Color, val scale: Float,
    val age: Int, val lifetime: Int, val glowing: Boolean, val light: Int, val decal: RecipeDecal?,
    val history: List<Vec3>,
) {
    constructor(sample: RecipeParticleSample, lookId: Long, history: List<Vec3>) : this(
        sample.id, lookId, sample.position, sample.color, sample.scale, sample.age, sample.lifetime,
        sample.glowing, sample.light, sample.decal, history,
    )
}

/** 外观先于引用它的帧发送；新入视野的成员随帧携带尾迹。 */
internal data class ParticleRecipeStatePayload(val id: UUID, val content: Content) : CustomPacketPayload {
    sealed interface Content {
        data class Look(val id: Long, val look: RecipeLook) : Content
        data class Frame(val tick: Long, val rows: List<RecipeStateRow>) : Content
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ParticleRecipeStatePayload>(Identifier.fromNamespaceAndPath("particledrawing", "recipe_state"))
        val CODEC: StreamCodec<FriendlyByteBuf, ParticleRecipeStatePayload> = object : StreamCodec<FriendlyByteBuf, ParticleRecipeStatePayload> {
            override fun encode(buf: FriendlyByteBuf, payload: ParticleRecipeStatePayload) {
                buf.writeUUID(payload.id)
                when (val content = payload.content) {
                    is Content.Look -> {
                        buf.writeBoolean(true)
                        buf.writeVarLong(content.id)
                        ParticleVisualCodec.write(buf, content.look.visual)
                        ParticleCurveCodec.write(buf, content.look.curve)
                    }
                    is Content.Frame -> {
                        buf.writeBoolean(false)
                        buf.writeVarLong(content.tick)
                        buf.writeVarInt(content.rows.size)
                        for (row in content.rows) writeRow(buf, row)
                    }
                }
            }

            override fun decode(buf: FriendlyByteBuf): ParticleRecipeStatePayload {
                val id = buf.readUUID()
                val content = if (buf.readBoolean()) {
                    val lookId = buf.readVarLong()
                    requireState(lookId >= 0)
                    Content.Look(lookId, RecipeLook(ParticleVisualCodec.read(buf) ?: ParticleVisual(), ParticleCurveCodec.read(buf)))
                } else {
                    val tick = buf.readVarLong()
                    requireState(tick >= 0)
                    val rows = List(count(buf, SyncedRecipe.MAX_PARTICLES)) { readRow(buf) }
                    requireState(rows.map { it.id }.toSet().size == rows.size)
                    Content.Frame(tick, rows)
                }
                return ParticleRecipeStatePayload(id, content)
            }
        }

        private fun writeRow(buf: FriendlyByteBuf, row: RecipeStateRow) {
            buf.writeVarLong(row.id)
            buf.writeVarLong(row.lookId)
            StreamCodecs.writeVec(buf, row.position)
            buf.writeFloat(row.color.r); buf.writeFloat(row.color.g); buf.writeFloat(row.color.b); buf.writeFloat(row.color.a)
            buf.writeFloat(row.scale)
            buf.writeVarInt(row.age); buf.writeVarInt(row.lifetime)
            buf.writeByte(row.light or if (row.glowing) 16 else 0)
            buf.writeBoolean(row.decal != null)
            row.decal?.let {
                StreamCodecs.writeVec(buf, it.position)
                StreamCodecs.writeVec(buf, it.normal)
                buf.writeDouble(it.radius)
                buf.writeBoolean(it.wrap)
            }
            buf.writeVarInt(row.history.size)
            for (point in row.history) StreamCodecs.writeVec(buf, point)
        }

        private fun readRow(buf: FriendlyByteBuf): RecipeStateRow {
            val id = buf.readVarLong()
            val look = buf.readVarLong()
            val position = readPoint(buf)
            val color = Color.of(buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat())
            val scale = buf.readFloat()
            val age = buf.readVarInt()
            val lifetime = buf.readVarInt()
            val light = buf.readUnsignedByte().toInt()
            requireState(id >= 0 && look >= 0 && scale.isFinite() && scale >= 0f && age >= 0 &&
                (lifetime == -1 || (lifetime <= ParticleRecipe.MAX_LIFETIME && age < lifetime)) && light in 0..31 &&
                color.r in 0f..1f && color.g in 0f..1f && color.b in 0f..1f && color.a in 0f..1f)
            val decal = if (buf.readBoolean()) {
                val at = readPoint(buf)
                val normal = readPoint(buf)
                val radius = buf.readDouble()
                requireState(radius > 0.0 && radius <= RecipeEmission.MAX_DECAL_RADIUS && normal.lengthSqr() in 0.99..1.01)
                RecipeDecal(at, normal, radius, buf.readBoolean())
            } else null
            val history = List(count(buf, ParticleVisual.MAX_TRAIL_TICKS + 1)) { readPoint(buf) }
            requireState(history.isEmpty() || history.last() == position)
            return RecipeStateRow(id, look, position, color, scale, age, lifetime, light and 16 != 0, light and 15, decal, history)
        }

        private fun readPoint(buf: FriendlyByteBuf) = StreamCodecs.readVec(buf).toCore().also {
            requireState(it.x.isFinite() && it.y.isFinite() && it.z.isFinite())
        }

        private fun count(buf: FriendlyByteBuf, max: Int) = buf.readVarInt().also { requireState(it in 0..max) }

        private fun requireState(ok: Boolean) = requireLocalized(ok) { translation("error.packet.recipe_state") }
    }
}
