package work.nekow.particledrawing.core.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import java.util.UUID

/**
 * 客户端 → 服务端：这段编排动画的有限指令已全部跑完，渲染侧收尾完成。
 *
 * 服务端据此触发 `ParticleGroup.onAnimationComplete` 与 `retire` 的销毁，销毁时刻与客户端真正到零对齐。
 * 只有 spin / 无限 pulse / 表达式这类没有有限时长指令的程序不发这个包。
 */
data class ProgramCompletePayload(
    val programId: UUID,
) : CustomPacketPayload {

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<ProgramCompletePayload>(
            Identifier.fromNamespaceAndPath("particledrawing", "program_complete")
        )

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, ProgramCompletePayload> =
            object : StreamCodec<FriendlyByteBuf, ProgramCompletePayload> {
                override fun decode(buf: FriendlyByteBuf): ProgramCompletePayload =
                    ProgramCompletePayload(StreamCodecs.UUID_CODEC.decode(buf))

                override fun encode(buf: FriendlyByteBuf, p: ProgramCompletePayload) {
                    StreamCodecs.UUID_CODEC.encode(buf, p.programId)
                }
            }
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
