package work.nekow.particledrawing.core.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import java.util.UUID

/**
 * 批量设置速度数据包：一个包覆盖多颗粒子，语义与 [ParticleVelocityPayload] 相同
 * （速度驱动接管位置、解除实体锚点，之后两端按同一规则逐 tick 积分）。
 *
 * 「每颗粒子各自的速度」在每 tick 都要重算时（被吸入的粒子速度随距离变化）用它：
 * 一包全组，不必每颗粒子一个包。
 *
 * @param updates 粒子 ID 与速度，按顺序一一对应
 */
@Suppress("unused")
data class ParticleVelocityBatchPayload(
    val updates: List<Update>,
) : CustomPacketPayload {

    /** 一条速度设置。 */
    data class Update(
        val particleId: UUID,
        val vx: Double, val vy: Double, val vz: Double,
    )

    companion object {
        /** 单包最大条数：畸形包显式拒绝，不静默截断。 */
        const val MAX_BATCH = 512

        @JvmField
        val TYPE = CustomPacketPayload.Type<ParticleVelocityBatchPayload>(
            Identifier.fromNamespaceAndPath("particledrawing", "particle_velocity_batch")
        )

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, ParticleVelocityBatchPayload> =
            object : StreamCodec<FriendlyByteBuf, ParticleVelocityBatchPayload> {
                override fun decode(buf: FriendlyByteBuf): ParticleVelocityBatchPayload {
                    val n = buf.readVarInt()
                    require(n in 0..MAX_BATCH) { "particle velocity batch too large: $n" }
                    val list = ArrayList<Update>(n)
                    repeat(n) {
                        list.add(
                            Update(
                                StreamCodecs.UUID_CODEC.decode(buf),
                                buf.readDouble(), buf.readDouble(), buf.readDouble(),
                            )
                        )
                    }
                    return ParticleVelocityBatchPayload(list)
                }

                override fun encode(buf: FriendlyByteBuf, p: ParticleVelocityBatchPayload) {
                    buf.writeVarInt(p.updates.size)
                    for (u in p.updates) {
                        StreamCodecs.UUID_CODEC.encode(buf, u.particleId)
                        buf.writeDouble(u.vx); buf.writeDouble(u.vy); buf.writeDouble(u.vz)
                    }
                }
            }
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
