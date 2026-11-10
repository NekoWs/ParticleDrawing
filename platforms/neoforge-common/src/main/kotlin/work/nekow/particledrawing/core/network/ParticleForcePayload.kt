package work.nekow.particledrawing.core.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import java.util.UUID

/**
 * 粒子加速度（力）数据包：只在开始施力时下发一次，之后服务端与客户端按同一规则逐 tick 积分
 * （速度 += 加速度，位置 += 速度），中途不再发包。
 *
 * @param particleId 粒子 ID
 * @param ax/ay/az 加速度（blocks/tick²）
 * @param ticks 施力 tick 数：>0 为有限 tick；<0 为无限，直到被下一次力/速度/位置指令覆盖
 */
@Suppress("unused")
data class ParticleForcePayload(
    val particleId: UUID,
    val ax: Double, val ay: Double, val az: Double,
    val ticks: Int,
) : CustomPacketPayload {

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<ParticleForcePayload>(
            Identifier.fromNamespaceAndPath("particledrawing", "particle_force")
        )

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, ParticleForcePayload> =
            object : StreamCodec<FriendlyByteBuf, ParticleForcePayload> {
                override fun decode(buf: FriendlyByteBuf): ParticleForcePayload {
                    val id = StreamCodecs.UUID_CODEC.decode(buf)
                    val ax = buf.readDouble()
                    val ay = buf.readDouble()
                    val az = buf.readDouble()
                    val ticks = ByteBufCodecs.VAR_INT.decode(buf)
                    return ParticleForcePayload(id, ax, ay, az, ticks)
                }

                override fun encode(buf: FriendlyByteBuf, p: ParticleForcePayload) {
                    StreamCodecs.UUID_CODEC.encode(buf, p.particleId)
                    buf.writeDouble(p.ax)
                    buf.writeDouble(p.ay)
                    buf.writeDouble(p.az)
                    ByteBufCodecs.VAR_INT.encode(buf, p.ticks)
                }
            }
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
