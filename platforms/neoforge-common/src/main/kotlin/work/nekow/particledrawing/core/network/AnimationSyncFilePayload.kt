package work.nekow.particledrawing.core.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

/**
 * 服务器 → 客户端「动画文件内容块」（playToClient，增量同步差异文件）。
 *
 * 文件按 [CHUNK_SIZE] 拆分逐块发送；客户端按 [name] 累积，收到 `eof=true` 的块后拼接写盘。
 * `eof=false` 且 data 为空的块表示空文件。
 *
 * @param name 相对文件名，含目录，如 "foo.pdrawc"
 * @param eof 是否为该文件的最后一块
 * @param data 本块字节内容
 */
@Suppress("unused")
data class AnimationSyncFilePayload(
    val name: String,
    val eof: Boolean,
    val data: ByteArray,
) : CustomPacketPayload {

    companion object {
        /** 单包文件字节上限（256 KiB）。 */
        const val CHUNK_SIZE: Int = 256 * 1024

        @JvmField
        val TYPE = CustomPacketPayload.Type<AnimationSyncFilePayload>(
            Identifier.fromNamespaceAndPath("particledrawing", "animation_sync_file")
        )

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, AnimationSyncFilePayload> =
            object : StreamCodec<FriendlyByteBuf, AnimationSyncFilePayload> {
                override fun decode(buf: FriendlyByteBuf): AnimationSyncFilePayload {
                    val name = buf.readUtf()
                    val eof = buf.readBoolean()
                    val data = buf.readByteArray()
                    return AnimationSyncFilePayload(name, eof, data)
                }

                override fun encode(buf: FriendlyByteBuf, p: AnimationSyncFilePayload) {
                    buf.writeUtf(p.name)
                    buf.writeBoolean(p.eof)
                    buf.writeByteArray(p.data)
                }
            }
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
