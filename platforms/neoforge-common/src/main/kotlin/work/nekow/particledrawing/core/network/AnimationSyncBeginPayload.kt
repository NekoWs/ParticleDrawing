package work.nekow.particledrawing.core.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

/**
 * 服务器 → 客户端「动画同步开始」信号（configurationToClient）。
 * 服务器在配置阶段发出，用于让客户端上报本地已有动画文件的哈希清单。
 *
 * 无负载，做成单例（object）；[STREAM_CODEC] 用 [StreamCodec.unit] 引用该单例。
 */
object AnimationSyncBeginPayload : CustomPacketPayload {

    @JvmField
    val TYPE = CustomPacketPayload.Type<AnimationSyncBeginPayload>(
        Identifier.fromNamespaceAndPath("particledrawing", "animation_sync_begin")
    )

    @JvmField
    val STREAM_CODEC: StreamCodec<FriendlyByteBuf, AnimationSyncBeginPayload> =
        StreamCodec.unit(AnimationSyncBeginPayload)

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
