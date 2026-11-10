package work.nekow.particledrawing.core.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import work.nekow.particledrawing.api.*
import java.util.UUID

/** 命令生成器的声明、锚点更新和现存成员操作。操作码只追加。 */
enum class RecipeOperation { START, UPDATE, ANCHOR, STOP, REMOVE, EDIT, VARIABLE }

data class ParticleRecipePayload(
    val id: UUID,
    val operation: RecipeOperation,
    val recipe: ParticleRecipe? = null,
    val anchor: RecipeAnchor? = null,
    val burst: Boolean = false,
    val predicate: String = "true",
    val changes: Map<String, String> = emptyMap(),
    val deleteSelected: Boolean = false,
    val variables: Map<String, Double> = emptyMap(),
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        @JvmField val TYPE = CustomPacketPayload.Type<ParticleRecipePayload>(Identifier.fromNamespaceAndPath("particledrawing", "particle_recipe"))
        @JvmField val STREAM_CODEC: StreamCodec<FriendlyByteBuf, ParticleRecipePayload> = object : StreamCodec<FriendlyByteBuf, ParticleRecipePayload> {
            override fun encode(buf: FriendlyByteBuf, p: ParticleRecipePayload) {
                buf.writeUUID(p.id); buf.writeVarInt(p.operation.ordinal)
                when (p.operation) {
                    RecipeOperation.START -> { RecipeCodec.write(buf, requireNotNull(p.recipe)); RecipeCodec.writeAnchor(buf, requireNotNull(p.anchor)); buf.writeBoolean(p.burst) }
                    RecipeOperation.UPDATE -> RecipeCodec.write(buf, requireNotNull(p.recipe))
                    RecipeOperation.ANCHOR -> RecipeCodec.writeAnchor(buf, requireNotNull(p.anchor))
                    RecipeOperation.EDIT -> { buf.writeUtf(p.predicate, ParticleRecipe.MAX_EXPRESSION); RecipeCodec.writeStrings(buf, p.changes); buf.writeBoolean(p.deleteSelected) }
                    RecipeOperation.VARIABLE -> RecipeCodec.writeNumbers(buf, p.variables)
                    RecipeOperation.STOP, RecipeOperation.REMOVE -> Unit
                }
            }

            override fun decode(buf: FriendlyByteBuf): ParticleRecipePayload {
                val id = buf.readUUID()
                val operation = RecipeOperation.entries.getOrNull(buf.readVarInt()) ?: throw IllegalArgumentException("未知生成器操作")
                return when (operation) {
                    RecipeOperation.START -> ParticleRecipePayload(id, operation, RecipeCodec.read(buf), RecipeCodec.readAnchor(buf), buf.readBoolean())
                    RecipeOperation.UPDATE -> ParticleRecipePayload(id, operation, recipe = RecipeCodec.read(buf))
                    RecipeOperation.ANCHOR -> ParticleRecipePayload(id, operation, anchor = RecipeCodec.readAnchor(buf))
                    RecipeOperation.EDIT -> ParticleRecipePayload(id, operation, predicate = buf.readUtf(ParticleRecipe.MAX_EXPRESSION), changes = RecipeCodec.readStrings(buf), deleteSelected = buf.readBoolean())
                    RecipeOperation.VARIABLE -> ParticleRecipePayload(id, operation, variables = RecipeCodec.readNumbers(buf))
                    RecipeOperation.STOP, RecipeOperation.REMOVE -> ParticleRecipePayload(id, operation)
                }
            }
        }
    }
}

internal object RecipeCodec {
    fun write(buf: FriendlyByteBuf, r: ParticleRecipe) {
        buf.writeVarInt(r.seeds.size)
        for (seed in r.seeds) {
            StreamCodecs.writeVec(buf, seed.position)
            buf.writeFloat(seed.color.r); buf.writeFloat(seed.color.g); buf.writeFloat(seed.color.b); buf.writeFloat(seed.color.a)
            buf.writeDouble(seed.u)
        }
        buf.writeVarInt(r.rate); buf.writeVarInt(r.interval); buf.writeVarInt(r.lifetime); buf.writeVarInt(r.maxAlive)
        buf.writeBoolean(r.follow)
        writeNumbers(buf, r.variables); writeNumbers(buf, r.state)
        writeStrings(buf, r.lets); writeStrings(buf, r.birth); writeStrings(buf, r.motion)
        buf.writeUtf(r.where, ParticleRecipe.MAX_EXPRESSION); buf.writeUtf(r.kill, ParticleRecipe.MAX_EXPRESSION)
        ParticleVisualCodec.write(buf, r.visual); ParticleCurveCodec.write(buf, r.lifeCurve)
    }

    fun read(buf: FriendlyByteBuf): ParticleRecipe {
        val seeds = List(count(buf, ParticleRecipe.MAX_SEEDS)) {
            ParticleSeed(StreamCodecs.readVec(buf), Color.of(buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat()), buf.readDouble())
        }
        return ParticleRecipe(seeds, buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean(),
            readNumbers(buf), readNumbers(buf), readStrings(buf), readStrings(buf), readStrings(buf),
            buf.readUtf(ParticleRecipe.MAX_EXPRESSION), buf.readUtf(ParticleRecipe.MAX_EXPRESSION),
            ParticleVisualCodec.read(buf) ?: ParticleVisual(), ParticleCurveCodec.read(buf))
    }

    fun writeAnchor(buf: FriendlyByteBuf, a: RecipeAnchor) {
        StreamCodecs.writeVec(buf, a.position); buf.writeBoolean(a.entity != null)
        a.entity?.let(buf::writeUUID); buf.writeVarInt(a.entityId)
        StreamCodecs.writeVec(buf, a.offset); buf.writeBoolean(a.local)
    }

    fun readAnchor(buf: FriendlyByteBuf) = RecipeAnchor(StreamCodecs.readVec(buf), if (buf.readBoolean()) buf.readUUID() else null,
        buf.readVarInt(), StreamCodecs.readVec(buf), buf.readBoolean())

    fun writeStrings(buf: FriendlyByteBuf, values: Map<String, String>) {
        buf.writeVarInt(values.size)
        values.forEach { (k, v) -> buf.writeUtf(k, 48); buf.writeUtf(v, ParticleRecipe.MAX_EXPRESSION) }
    }

    fun readStrings(buf: FriendlyByteBuf): Map<String, String> = linkedMapOf<String, String>().apply {
        repeat(count(buf, ParticleRecipe.MAX_FIELDS)) { val k = buf.readUtf(48); require(k !in this); put(k, buf.readUtf(ParticleRecipe.MAX_EXPRESSION)) }
    }

    fun writeNumbers(buf: FriendlyByteBuf, values: Map<String, Double>) {
        buf.writeVarInt(values.size); values.forEach { (k, v) -> buf.writeUtf(k, 48); buf.writeDouble(v) }
    }

    fun readNumbers(buf: FriendlyByteBuf): Map<String, Double> = linkedMapOf<String, Double>().apply {
        repeat(count(buf, ParticleRecipe.MAX_FIELDS)) { val k = buf.readUtf(48); require(k !in this); put(k, buf.readDouble()) }
    }

    private fun count(buf: FriendlyByteBuf, max: Int) = buf.readVarInt().also { require(it in 0..max) { "粒子生成声明超限" } }
}
