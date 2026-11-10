package work.nekow.particledrawing.core.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

/**
 * 服务器 → 客户端「动画同步完成」信号（playToClient）。
 *
 * 服务器发完所有差异文件后发出，客户端收到即视为同步结束，可解除配置阶段的阻塞并进入世界。
 *
 * @param fileCount 下发的文件总数，无差异时为 0
 */
@Suppress("unused")
data class AnimationSyncDonePayload(
    val fileCount: Int,
) : CustomPacketPayload {

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<AnimationSyncDonePayload>(
            Identifier.fromNamespaceAndPath("particledrawing", "animation_sync_done")
        )

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, AnimationSyncDonePayload> =
            object : StreamCodec<FriendlyByteBuf, AnimationSyncDonePayload> {
                override fun decode(buf: FriendlyByteBuf): AnimationSyncDonePayload =
                    AnimationSyncDonePayload(buf.readVarInt())

                override fun encode(buf: FriendlyByteBuf, p: AnimationSyncDonePayload) {
                    buf.writeVarInt(p.fileCount)
                }
            }
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
