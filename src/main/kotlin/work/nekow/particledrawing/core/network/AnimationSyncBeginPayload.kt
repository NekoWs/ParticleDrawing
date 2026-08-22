package work.nekow.particledrawing.core.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

/**
 * 服务器 → 客户端「动画同步开始」信号（configurationToClient）。
 * 由服务器配置任务在配置阶段发出，提示客户端上报本地已有动画文件哈希清单。
 *
 * 无负载信号，故做成单例（object）；[STREAM_CODEC] 用 [StreamCodec.unit] 引用该单例，
 * encode 时因恒为同一实例而满足 unit codec 的 identity 校验。
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
