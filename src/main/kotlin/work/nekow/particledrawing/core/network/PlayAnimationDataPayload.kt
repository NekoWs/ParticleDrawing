package work.nekow.particledrawing.core.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import work.nekow.particledrawing.animation.ParticleAnimation
import java.util.UUID

/**
 * 服务端下发「代码生成的动画」：直接携带已构建的 [ParticleAnimation]（结构化二进制，不验签），
 * 客户端解析后与 .pdrawc 播放走同一条本地播放/渲染链路。
 *
 * @param animationId 本次播放的唯一 ID（用于后续变量更新/停止）
 * @param originX/Y/Z 播放原点（世界坐标）
 * @param startGameTick 播放开始时刻的服务端维度 gameTime（服务端权威进度时钟起点）
 * @param animation 代码生成的动画定义
 */
@Suppress("unused")
data class PlayAnimationDataPayload(
    val animationId: UUID,
    val originX: Double, val originY: Double, val originZ: Double,
    val startGameTick: Long,
    val animation: ParticleAnimation,
) : CustomPacketPayload {

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<PlayAnimationDataPayload>(
            Identifier.fromNamespaceAndPath("particledrawing", "play_animation_data")
        )

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, PlayAnimationDataPayload> =
            object : StreamCodec<FriendlyByteBuf, PlayAnimationDataPayload> {
                override fun decode(buf: FriendlyByteBuf): PlayAnimationDataPayload {
                    val id = StreamCodecs.UUID_CODEC.decode(buf)
                    val ox = buf.readDouble()
                    val oy = buf.readDouble()
                    val oz = buf.readDouble()
                    val startGameTick = buf.readVarLong()
                    val animation = ParticleAnimationCodec.read(buf)
                    return PlayAnimationDataPayload(id, ox, oy, oz, startGameTick, animation)
                }

                override fun encode(buf: FriendlyByteBuf, p: PlayAnimationDataPayload) {
                    StreamCodecs.UUID_CODEC.encode(buf, p.animationId)
                    buf.writeDouble(p.originX)
                    buf.writeDouble(p.originY)
                    buf.writeDouble(p.originZ)
                    buf.writeVarLong(p.startGameTick)
                    ParticleAnimationCodec.write(buf, p.animation)
                }
            }
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}