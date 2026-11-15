package work.nekow.particledrawing.core.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import work.nekow.particledrawing.light.LightShape
import java.util.UUID

internal data class ParticleLightShapePayload(
    val particleId: UUID,
    val shape: LightShape?,
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
                    return ParticleLightShapePayload(id, shape)
                }

                override fun encode(buf: FriendlyByteBuf, value: ParticleLightShapePayload) {
                    StreamCodecs.UUID_CODEC.encode(buf, value.particleId)
                    buf.writeBoolean(value.shape != null)
                    value.shape?.let { ParticleVisualCodec.writeLightShape(buf, it) }
                }
            }
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
