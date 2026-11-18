package work.nekow.particledrawing.core.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import work.nekow.particledrawing.api.ForceFieldKind
import work.nekow.particledrawing.api.ParticleForceField
import work.nekow.particledrawing.api.RecipeAnchor
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.translation
import work.nekow.particledrawing.platform.toCore

internal data class FieldPlacement(val spec: ParticleForceField, val anchor: RecipeAnchor)

internal data class ParticleFieldSnapshot(val fields: List<FieldPlacement>) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ParticleFieldSnapshot>(Identifier.fromNamespaceAndPath("particledrawing", "field_snapshot"))
        val CODEC: StreamCodec<FriendlyByteBuf, ParticleFieldSnapshot> = object : StreamCodec<FriendlyByteBuf, ParticleFieldSnapshot> {
            override fun encode(buf: FriendlyByteBuf, value: ParticleFieldSnapshot) {
                buf.writeVarInt(value.fields.size)
                for ((spec, anchor) in value.fields) {
                    buf.writeVarInt(spec.kind.ordinal)
                    buf.writeDouble(spec.radius)
                    buf.writeDouble(spec.strength)
                    StreamCodecs.writeVec(buf, spec.direction)
                    buf.writeDouble(spec.falloff)
                    buf.writeInt(spec.seed)
                    RecipeCodec.writeAnchor(buf, anchor)
                }
            }

            override fun decode(buf: FriendlyByteBuf): ParticleFieldSnapshot {
                val count = buf.readVarInt()
                requireLocalized(count in 0..ParticleForceField.MAX_FIELDS) { translation("error.field.limit", ParticleForceField.MAX_FIELDS) }
                return ParticleFieldSnapshot(List(count) {
                    val kind = buf.readVarInt()
                    requireLocalized(kind in ForceFieldKind.entries.indices) { translation("error.field.kind") }
                    val spec = ParticleForceField(
                        ForceFieldKind.entries[kind], buf.readDouble(), buf.readDouble(),
                        StreamCodecs.readVec(buf).toCore(), buf.readDouble(), buf.readInt(),
                    )
                    FieldPlacement(spec, RecipeCodec.readAnchor(buf))
                })
            }
        }
    }
}
