package work.nekow.particledrawing.core.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

/**
 * 客户端 → 服务器「动画文件同步请求」（playToServer）。
 *
 * 客户端在配置阶段携带本地已有动画文件（.pdraw 与 textures 目录下的 .png）的 SHA-1 哈希清单，
 * 服务器据此做增量同步：只下发客户端缺失或内容不同的文件。
 *
 * @param hashes 相对文件名（如 "foo.pdraw"、"textures/bar.png"） -> SHA-1 hex（小写）
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

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, AnimationSyncRequestPayload> =
            object : StreamCodec<FriendlyByteBuf, AnimationSyncRequestPayload> {
                override fun decode(buf: FriendlyByteBuf): AnimationSyncRequestPayload {
                    val n = buf.readVarInt()
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
