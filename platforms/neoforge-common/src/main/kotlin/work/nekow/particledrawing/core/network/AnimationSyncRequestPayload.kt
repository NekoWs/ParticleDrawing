package work.nekow.particledrawing.core.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

/**
 * 客户端 → 服务器「动画文件同步请求」（playToServer）。
 *
 * 携带本地已有 .pdrawc 文件的 SHA-1 清单，服务器据此只下发缺失或内容不同的文件。
 *
 * @param hashes 相对文件名 -> SHA-1 hex（小写）
 */
@Suppress("unused")
data class AnimationSyncRequestPayload(
    val hashes: Map<String, String>,
) : CustomPacketPayload {

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<AnimationSyncRequestPayload>(
            Identifier.fromNamespaceAndPath("particledrawing", "animation_sync_request")
        )

        /** 单次上报的哈希条目上限，防超大 varint 触发内存分配。 */
        private const val MAX_HASHES = 100_000

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, AnimationSyncRequestPayload> =
            object : StreamCodec<FriendlyByteBuf, AnimationSyncRequestPayload> {
                override fun decode(buf: FriendlyByteBuf): AnimationSyncRequestPayload {
                    val n = buf.readVarInt()
                    if (n < 0 || n > MAX_HASHES) {
                        throw IllegalArgumentException("animation_sync_request 哈希条目数超限: $n")
                    }
                    val map = HashMap<String, String>(n)
                    repeat(n) {
                        map[buf.readUtf()] = buf.readUtf()
                    }
                    return AnimationSyncRequestPayload(map)
                }

                override fun encode(buf: FriendlyByteBuf, p: AnimationSyncRequestPayload) {
                    buf.writeVarInt(p.hashes.size)
                    for ((name, hash) in p.hashes) {
                        buf.writeUtf(name)
                        buf.writeUtf(hash)
                    }
                }
            }
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
