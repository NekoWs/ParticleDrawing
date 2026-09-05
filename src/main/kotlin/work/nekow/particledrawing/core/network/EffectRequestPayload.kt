package work.nekow.particledrawing.core.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

/**
 * 客户端请求服务端下发指定 key 的特效字节（缓存缺失时）。
 */
@Suppress("unused")
data class EffectRequestPayload(
    val key: Identifier,
) : CustomPacketPayload {

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<EffectRequestPayload>(
            Identifier.fromNamespaceAndPath("particledrawing", "effect_request")
        )

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, EffectRequestPayload> =
            object : StreamCodec<FriendlyByteBuf, EffectRequestPayload> {
                override fun decode(buf: FriendlyByteBuf): EffectRequestPayload =
                    EffectRequestPayload(StreamCodecs.readIdentifier(buf))

                override fun encode(buf: FriendlyByteBuf, p: EffectRequestPayload) {
                    StreamCodecs.writeIdentifier(buf, p.key)
                }
            }
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}