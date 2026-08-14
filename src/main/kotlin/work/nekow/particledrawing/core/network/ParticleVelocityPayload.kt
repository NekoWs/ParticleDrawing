package work.nekow.particledrawing.core.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import java.util.UUID

/**
 * 粒子速度更新数据包。
 *
 * @param particleId 粒子 ID
 * @param vx/vy/vz 速度向量（blocks/tick）
 */
data class ParticleVelocityPayload(
    val particleId: UUID,
    val vx: Double, val vy: Double, val vz: Double
) : CustomPacketPayload {

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<ParticleVelocityPayload>(
            Identifier.fromNamespaceAndPath("particledrawing", "particle_velocity")
        )

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, ParticleVelocityPayload> =
            object : StreamCodec<FriendlyByteBuf, ParticleVelocityPayload> {
                override fun decode(buf: FriendlyByteBuf): ParticleVelocityPayload {
                    val id = StreamCodecs.UUID_CODEC.decode(buf)
                    val vx = buf.readDouble()
                    val vy = buf.readDouble()
                    val vz = buf.readDouble()
                    return ParticleVelocityPayload(id, vx, vy, vz)
                }

                override fun encode(buf: FriendlyByteBuf, p: ParticleVelocityPayload) {
                    StreamCodecs.UUID_CODEC.encode(buf, p.particleId)
                    buf.writeDouble(p.vx)
                    buf.writeDouble(p.vy)
                    buf.writeDouble(p.vz)
                }
            }
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
