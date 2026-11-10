package work.nekow.particledrawing.core.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import work.nekow.particledrawing.animation.program.readEasing
import work.nekow.particledrawing.animation.program.writeEasing
import work.nekow.particledrawing.core.easing.EasingType
import java.util.UUID

/**
 * 编排动画程序的移动轴心样本：服务端逐 tick 报一条「上一位置 → 当前位置」。
 *
 * 两条是服务端真实相邻的一对样本，客户端据此判断瞬移与断流恢复并按跳变处理；
 * 渲染侧的相邻样本插值由桥接粒子的 xo/x 在渲染帧完成，与直设位置粒子同相位。
 *
 * 绑定一次轴心（`ParticleGroup.anchor` + `BindPivot(Movable)`）之后，位置就只走这个小包，
 * 不再每 tick 追加一条绑定指令。
 */
data class ProgramAnchorPayload(
    val programId: UUID,
    val prevX: Double, val prevY: Double, val prevZ: Double,
    val x: Double, val y: Double, val z: Double,
    val vx: Double, val vy: Double, val vz: Double,
) : CustomPacketPayload {

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<ProgramAnchorPayload>(
            Identifier.fromNamespaceAndPath("particledrawing", "program_anchor")
        )

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, ProgramAnchorPayload> =
            object : StreamCodec<FriendlyByteBuf, ProgramAnchorPayload> {
                override fun decode(buf: FriendlyByteBuf): ProgramAnchorPayload =
                    ProgramAnchorPayload(
                        StreamCodecs.UUID_CODEC.decode(buf),
                        buf.readDouble(), buf.readDouble(), buf.readDouble(),
                        buf.readDouble(), buf.readDouble(), buf.readDouble(),
                        buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    )

                override fun encode(buf: FriendlyByteBuf, p: ProgramAnchorPayload) {
                    StreamCodecs.UUID_CODEC.encode(buf, p.programId)
                    buf.writeDouble(p.prevX); buf.writeDouble(p.prevY); buf.writeDouble(p.prevZ)
                    buf.writeDouble(p.x); buf.writeDouble(p.y); buf.writeDouble(p.z)
                    buf.writeDouble(p.vx); buf.writeDouble(p.vy); buf.writeDouble(p.vz)
                }
            }
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

/**
 * 热更程序变量并在若干毫秒内渐变到目标值。
 *
 * [value] 是公式字符串（与 [SetProgramVarPayload] 同一套求值环境），在收到那一刻求出目标值，
 * 之后每 tick 从当前值缓动过去。
 */
data class SetProgramVarEasePayload(
    val programId: UUID,
    val name: String,
    val value: String,
    val durationMs: Int,
    val easing: EasingType,
) : CustomPacketPayload {

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<SetProgramVarEasePayload>(
            Identifier.fromNamespaceAndPath("particledrawing", "anim_program_ease_var")
        )

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, SetProgramVarEasePayload> =
            object : StreamCodec<FriendlyByteBuf, SetProgramVarEasePayload> {
                override fun decode(buf: FriendlyByteBuf): SetProgramVarEasePayload =
                    SetProgramVarEasePayload(
                        StreamCodecs.UUID_CODEC.decode(buf),
                        buf.readUtf(),
                        buf.readUtf(),
                        buf.readVarInt(),
                        readEasing(buf),
                    )

                override fun encode(buf: FriendlyByteBuf, p: SetProgramVarEasePayload) {
                    StreamCodecs.UUID_CODEC.encode(buf, p.programId)
                    buf.writeUtf(p.name)
                    buf.writeUtf(p.value)
                    buf.writeVarInt(p.durationMs)
                    writeEasing(buf, p.easing)
                }
            }
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
