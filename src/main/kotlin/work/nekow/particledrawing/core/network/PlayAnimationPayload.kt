package work.nekow.particledrawing.core.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import java.util.UUID

/**
 * 服务端下发「播放动画」：携带 .pdraw 工程文件 JSON 全文与播放原点。
 *
 * @param animationId 本次播放的唯一 ID（用于后续变量更新/停止）
 * @param originX/Y/Z 播放原点（世界坐标）
 * @param json .pdraw 工程文件 JSON 全文
 */
@Suppress("unused")
data class PlayAnimationPayload(
    val animationId: UUID,
    val originX: Double, val originY: Double, val originZ: Double,
    val json: String,
) : CustomPacketPayload {

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<PlayAnimationPayload>(
            Identifier.fromNamespaceAndPath("particledrawing", "play_animation")
        )

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, PlayAnimationPayload> =
            object : StreamCodec<FriendlyByteBuf, PlayAnimationPayload> {
                override fun decode(buf: FriendlyByteBuf): PlayAnimationPayload {
                    val id = StreamCodecs.UUID_CODEC.decode(buf)
                    val ox = buf.readDouble()
                    val oy = buf.readDouble()
                    val oz = buf.readDouble()
                    val json = String(buf.readByteArray(), Charsets.UTF_8)
                    return PlayAnimationPayload(id, ox, oy, oz, json)
                }

                override fun encode(buf: FriendlyByteBuf, p: PlayAnimationPayload) {
                    StreamCodecs.UUID_CODEC.encode(buf, p.animationId)
                    buf.writeDouble(p.originX)
                    buf.writeDouble(p.originY)
                    buf.writeDouble(p.originZ)
                    // 用字节数组而非 writeUtf：writeUtf 有 32767 字符硬上限，较大 .pdraw 工程会超限
                    buf.writeByteArray(p.json.toByteArray(Charsets.UTF_8))
                }
            }
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
