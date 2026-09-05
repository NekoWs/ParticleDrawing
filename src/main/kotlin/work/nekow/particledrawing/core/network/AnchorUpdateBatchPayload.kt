package work.nekow.particledrawing.core.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import java.util.UUID

/**
 * 服务端每 tick 批量下发的可移动锚点更新（一次一包，避免 N 个播放实例 N 个包）。
 */
@Suppress("unused")
data class AnchorUpdateBatchPayload(
    val updates: List<AnchorUpdate>,
) : CustomPacketPayload {

    data class AnchorUpdate(
        val playbackId: UUID,
        val x: Double, val y: Double, val z: Double,
        val vx: Double, val vy: Double, val vz: Double,
    )

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<AnchorUpdateBatchPayload>(
            Identifier.fromNamespaceAndPath("particledrawing", "anchor_update_batch")
        )

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, AnchorUpdateBatchPayload> =
            object : StreamCodec<FriendlyByteBuf, AnchorUpdateBatchPayload> {
                override fun decode(buf: FriendlyByteBuf): AnchorUpdateBatchPayload {
                    val n = buf.readVarInt()
                    val list = ArrayList<AnchorUpdate>(n)
                    repeat(n) {
                        list.add(
                            AnchorUpdate(
                                StreamCodecs.UUID_CODEC.decode(buf),
                                buf.readDouble(), buf.readDouble(), buf.readDouble(),
                                buf.readDouble(), buf.readDouble(), buf.readDouble(),
                            )
                        )
                    }
                    return AnchorUpdateBatchPayload(list)
                }

                override fun encode(buf: FriendlyByteBuf, p: AnchorUpdateBatchPayload) {
                    buf.writeVarInt(p.updates.size)
                    for (u in p.updates) {
                        StreamCodecs.UUID_CODEC.encode(buf, u.playbackId)
                        buf.writeDouble(u.x); buf.writeDouble(u.y); buf.writeDouble(u.z)
                        buf.writeDouble(u.vx); buf.writeDouble(u.vy); buf.writeDouble(u.vz)
                    }
                }
            }
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}