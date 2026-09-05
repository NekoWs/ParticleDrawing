package work.nekow.particledrawing.core.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import java.util.UUID

/**
 * 服务端权威播放时钟同步：控制命令（seek/暂停/变速）或迟到加入时的进度对齐。
 */
@Suppress("unused")
data class ClockSyncPayload(
    val playbackId: UUID,
    val position: Double,
    val playing: Boolean,
    val speed: Double,
) : CustomPacketPayload {

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<ClockSyncPayload>(
            Identifier.fromNamespaceAndPath("particledrawing", "clock_sync")
        )

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, ClockSyncPayload> =
            object : StreamCodec<FriendlyByteBuf, ClockSyncPayload> {
                override fun decode(buf: FriendlyByteBuf): ClockSyncPayload =
                    ClockSyncPayload(
                        StreamCodecs.UUID_CODEC.decode(buf),
                        buf.readDouble(),
                        buf.readBoolean(),
                        buf.readDouble(),
                    )

                override fun encode(buf: FriendlyByteBuf, p: ClockSyncPayload) {
                    StreamCodecs.UUID_CODEC.encode(buf, p.playbackId)
                    buf.writeDouble(p.position)
                    buf.writeBoolean(p.playing)
                    buf.writeDouble(p.speed)
                }
            }
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}