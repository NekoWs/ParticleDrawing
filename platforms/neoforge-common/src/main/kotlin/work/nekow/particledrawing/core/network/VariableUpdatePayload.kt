package work.nekow.particledrawing.core.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import java.util.UUID

/**
 * 服务端下发「变量更新」：更新某次播放中函数对象的变量值。
 *
 * @param animationId 目标播放 ID
 * @param variable 变量名
 * @param value 变量表达式/值（字符串）
 */
@Suppress("unused")
data class VariableUpdatePayload(
    val animationId: UUID,
    val variable: String,
    val value: String,
) : CustomPacketPayload {

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<VariableUpdatePayload>(
            Identifier.fromNamespaceAndPath("particledrawing", "variable_update")
        )

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, VariableUpdatePayload> =
            object : StreamCodec<FriendlyByteBuf, VariableUpdatePayload> {
                override fun decode(buf: FriendlyByteBuf): VariableUpdatePayload {
                    val id = StreamCodecs.UUID_CODEC.decode(buf)
                    val v = buf.readUtf()
                    val value = buf.readUtf()
                    return VariableUpdatePayload(id, v, value)
                }

                override fun encode(buf: FriendlyByteBuf, p: VariableUpdatePayload) {
                    StreamCodecs.UUID_CODEC.encode(buf, p.animationId)
                    buf.writeUtf(p.variable)
                    buf.writeUtf(p.value)
                }
            }
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
