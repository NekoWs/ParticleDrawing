package work.nekow.particledrawing.core.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import java.util.UUID

/**
 * 客户端 → 服务端：指定版本的退场观察已全部到端点。
 *
 * 带 [version] 是为了丢弃重定向前的迟到完成信号；服务端只接受当前版本的回报。
 */
data class ParticleExitCompletePayload(
    val exitId: UUID,
    val version: Int,
) : CustomPacketPayload {

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<ParticleExitCompletePayload>(
            Identifier.fromNamespaceAndPath("particledrawing", "particle_exit_complete")
        )

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, ParticleExitCompletePayload> =
            object : StreamCodec<FriendlyByteBuf, ParticleExitCompletePayload> {
                override fun decode(buf: FriendlyByteBuf): ParticleExitCompletePayload =
                    ParticleExitCompletePayload(
                        StreamCodecs.UUID_CODEC.decode(buf),
                        ByteBufCodecs.VAR_INT.decode(buf),
                    )

                override fun encode(buf: FriendlyByteBuf, p: ParticleExitCompletePayload) {
                    StreamCodecs.UUID_CODEC.encode(buf, p.exitId)
                    ByteBufCodecs.VAR_INT.encode(buf, p.version)
                }
            }
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
