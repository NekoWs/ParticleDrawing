package work.nekow.particledrawing.core.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

/**
 * 服务端按需下发特效的 .pdrawc 字节（客户端收到 PlayEffectPayload 但缓存缺失时请求）。
 */
@Suppress("unused")
data class EffectDataPayload(
    val key: Identifier,
    val data: ByteArray,
) : CustomPacketPayload {

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<EffectDataPayload>(
            Identifier.fromNamespaceAndPath("particledrawing", "effect_data")
        )

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, EffectDataPayload> =
            object : StreamCodec<FriendlyByteBuf, EffectDataPayload> {
                override fun decode(buf: FriendlyByteBuf): EffectDataPayload =
                    EffectDataPayload(StreamCodecs.readIdentifier(buf), buf.readByteArray())

                override fun encode(buf: FriendlyByteBuf, p: EffectDataPayload) {
                    StreamCodecs.writeIdentifier(buf, p.key)
                    buf.writeByteArray(p.data)
                }
            }
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}