package work.nekow.particledrawing.core.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import java.util.UUID

/**
 * 粒子发光光照等级更新数据包，用于运行时动态调整发光粒子的光照强度。
 *
 * @param particleId 粒子 ID
 * @param lightLevel 目标光照等级 (0-15)
 */
data class ParticleLightLevelPayload(
    val particleId: UUID,
    val lightLevel: Int
) : CustomPacketPayload {

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<ParticleLightLevelPayload>(
            Identifier.fromNamespaceAndPath("particledrawing", "particle_light_level")
        )

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, ParticleLightLevelPayload> =
            object : StreamCodec<FriendlyByteBuf, ParticleLightLevelPayload> {
                override fun decode(buf: FriendlyByteBuf): ParticleLightLevelPayload {
                    val id = StreamCodecs.UUID_CODEC.decode(buf)
                    val level = buf.readVarInt()
                    return ParticleLightLevelPayload(id, level)
                }

                override fun encode(buf: FriendlyByteBuf, p: ParticleLightLevelPayload) {
                    StreamCodecs.UUID_CODEC.encode(buf, p.particleId)
                    buf.writeVarInt(p.lightLevel)
                }
            }
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
