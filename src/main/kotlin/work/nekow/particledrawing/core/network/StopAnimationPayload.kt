package work.nekow.particledrawing.core.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import java.util.UUID

/**
 * 服务端下发「停止动画」：停止并销毁指定播放的粒子。
 *
 * @param animationId 目标播放 ID；null 表示停止全部
 */
@Suppress("unused")
data class StopAnimationPayload(
    val animationId: UUID?,
) : CustomPacketPayload {

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<StopAnimationPayload>(
            Identifier.fromNamespaceAndPath("particledrawing", "stop_animation")
        )

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, StopAnimationPayload> =
            object : StreamCodec<FriendlyByteBuf, StopAnimationPayload> {
                override fun decode(buf: FriendlyByteBuf): StopAnimationPayload =
                    StopAnimationPayload(StreamCodecs.readNullableUUID(buf))

                override fun encode(buf: FriendlyByteBuf, p: StopAnimationPayload) {
                    StreamCodecs.writeNullableUUID(buf, p.animationId)
                }
            }
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
