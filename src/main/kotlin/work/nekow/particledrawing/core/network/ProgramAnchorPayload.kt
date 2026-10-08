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
 * 编排动画程序的**移动轴心样本**：服务端逐 tick 报一条「上一位置 → 当前位置」。
 *
 * 为什么带上一位置：这两条是服务端真实的一对相邻样本，客户端据此判断
 * 「是不是瞬移 / 断流刚恢复」（按跳变处理，不在地图上扫一条假轨迹），
 * 渲染上的相邻样本插值则由桥接粒子的 `xo/x` 在渲染帧完成——与 `track` 粒子同相位。
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
 * 热更程序变量并**在若干毫秒内渐变**到目标值（不是立即赋常量）。
 *
 * [value] 仍是公式字符串（与 [SetProgramVarPayload] 同一套求值环境），在收到那一刻求出目标值，
 * 之后每 tick 从当前值缓动过去——空间端点（光束末端、场中心）因此不会瞬移。
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
