package work.nekow.particledrawing.core.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import work.nekow.particledrawing.core.easing.EasingType
import work.nekow.particledrawing.light.LightShape
import java.util.UUID

internal enum class LightShapePart { ALL, DIRECTION, FOCUS, FALLOFF }

internal data class ParticleLightShapePayload(
    val particleId: UUID,
    val shape: LightShape?,
    val part: LightShapePart = LightShapePart.ALL,
    val durationTicks: Int = 0,
    val easing: EasingType = EasingType.LINEAR,
) : CustomPacketPayload {

    companion object {
        val TYPE = CustomPacketPayload.Type<ParticleLightShapePayload>(
            Identifier.fromNamespaceAndPath("particledrawing", "particle_light_shape")
        )

        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, ParticleLightShapePayload> =
            object : StreamCodec<FriendlyByteBuf, ParticleLightShapePayload> {
                override fun decode(buf: FriendlyByteBuf): ParticleLightShapePayload {
                    val id = StreamCodecs.UUID_CODEC.decode(buf)
                    val shape = if (buf.readBoolean()) ParticleVisualCodec.readLightShape(buf) else null
                    val part = LightShapePart.entries[buf.readUnsignedByte().toInt()]
                    val durationTicks = buf.readVarInt()
                    val easing = if (durationTicks > 0) readEasingCompact(buf) else EasingType.LINEAR
                    return ParticleLightShapePayload(id, shape, part, durationTicks, easing)
                }

                override fun encode(buf: FriendlyByteBuf, value: ParticleLightShapePayload) {
                    StreamCodecs.UUID_CODEC.encode(buf, value.particleId)
                    buf.writeBoolean(value.shape != null)
                    value.shape?.let { ParticleVisualCodec.writeLightShape(buf, it) }
                    buf.writeByte(value.part.ordinal)
                    buf.writeVarInt(value.durationTicks)
                    if (value.durationTicks > 0) writeEasingCompact(buf, value.easing)
                }
            }
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
