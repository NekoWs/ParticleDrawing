package work.nekow.particledrawing.core.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import java.util.UUID

/**
 * 批量直设位置数据包：一个包覆盖多颗粒子，语义与 [ParticleTrackPayload] 相同：
 * 客户端每 tick 消费一条，按 partialTick 在相邻两条之间插值。
 *
 * @param updates 粒子 ID 与目标世界坐标，按顺序一一对应
 */
@Suppress("unused")
data class ParticleTrackBatchPayload(
    val updates: List<Track>,
) : CustomPacketPayload {

    init {
        // 超限直接报错，发送端按 BatchChunking 拆包后下发
        require(updates.size <= MAX_BATCH) { "粒子位置批量超限: ${updates.size} > $MAX_BATCH" }
    }

    /** 一条直设位置。 */
    data class Track(
        val particleId: UUID,
        val x: Double, val y: Double, val z: Double,
    )

    companion object {
        /** 单包最大条数，超限显式拒绝。 */
        const val MAX_BATCH = 512

        @JvmField
        val TYPE = CustomPacketPayload.Type<ParticleTrackBatchPayload>(
            Identifier.fromNamespaceAndPath("particledrawing", "particle_track_batch")
        )

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, ParticleTrackBatchPayload> =
            object : StreamCodec<FriendlyByteBuf, ParticleTrackBatchPayload> {
                override fun decode(buf: FriendlyByteBuf): ParticleTrackBatchPayload {
                    val n = buf.readVarInt()
                    require(n in 0..MAX_BATCH) { "particle track batch too large: $n" }
                    val list = ArrayList<Track>(n)
                    repeat(n) {
                        list.add(
                            Track(
                                StreamCodecs.UUID_CODEC.decode(buf),
                                buf.readDouble(), buf.readDouble(), buf.readDouble(),
                            )
                        )
                    }
                    return ParticleTrackBatchPayload(list)
                }

                override fun encode(buf: FriendlyByteBuf, p: ParticleTrackBatchPayload) {
                    buf.writeVarInt(p.updates.size)
                    for (u in p.updates) {
                        StreamCodecs.UUID_CODEC.encode(buf, u.particleId)
                        buf.writeDouble(u.x); buf.writeDouble(u.y); buf.writeDouble(u.z)
                    }
                }
            }
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
