package work.nekow.particledrawing.core.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import java.util.UUID

/**
 * 批量施力数据包：一个包覆盖多颗粒子，语义与 [ParticleForcePayload] 相同
 * （力驱动接管位置、解除实体锚点，之后两端按同一规则逐 tick 积分）。
 *
 * 每颗粒子各自的加速度、共用一个 [ticks]，用于每 tick 按距离重算一次力的场合。
 *
 * @param ticks 施力 tick 数：>0 为有限 tick；<0 为无限；0 为清除
 * @param updates 粒子 ID 与加速度，按顺序一一对应
 */
@Suppress("unused")
data class ParticleForceBatchPayload(
    val ticks: Int,
    val updates: List<Update>,
) : CustomPacketPayload {

    init {
        // 超限直接报错，发送端按 BatchChunking 拆包后下发
        require(updates.size <= MAX_BATCH) { "粒子力批量超限: ${updates.size} > $MAX_BATCH" }
    }

    /** 一条施力。 */
    data class Update(
        val particleId: UUID,
        val ax: Double, val ay: Double, val az: Double,
    )

    companion object {
        /** 单包最大条数，超限显式拒绝。 */
        const val MAX_BATCH = 512

        @JvmField
        val TYPE = CustomPacketPayload.Type<ParticleForceBatchPayload>(
            Identifier.fromNamespaceAndPath("particledrawing", "particle_force_batch")
        )

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, ParticleForceBatchPayload> =
            object : StreamCodec<FriendlyByteBuf, ParticleForceBatchPayload> {
                override fun decode(buf: FriendlyByteBuf): ParticleForceBatchPayload {
                    val ticks = ByteBufCodecs.VAR_INT.decode(buf)
                    val n = buf.readVarInt()
                    require(n in 0..MAX_BATCH) { "particle force batch too large: $n" }
                    val list = ArrayList<Update>(n)
                    repeat(n) {
                        list.add(
                            Update(
                                StreamCodecs.UUID_CODEC.decode(buf),
                                buf.readDouble(), buf.readDouble(), buf.readDouble(),
                            )
                        )
                    }
                    return ParticleForceBatchPayload(ticks, list)
                }

                override fun encode(buf: FriendlyByteBuf, p: ParticleForceBatchPayload) {
                    ByteBufCodecs.VAR_INT.encode(buf, p.ticks)
                    buf.writeVarInt(p.updates.size)
                    for (u in p.updates) {
                        StreamCodecs.UUID_CODEC.encode(buf, u.particleId)
                        buf.writeDouble(u.ax); buf.writeDouble(u.ay); buf.writeDouble(u.az)
                    }
                }
            }
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
