package work.nekow.particledrawing.core.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.animation.program.AnimInstruction
import work.nekow.particledrawing.animation.program.InputChannel
import work.nekow.particledrawing.animation.program.PivotRef
import java.util.UUID

/**
 * 客户端动画程序协议族：
 * - [AnimationProgramPayload] 首次下发完整程序（粒子清单 + 时钟锚点 + 轴心 + 通道 + 变量 + 指令流）；
 * - [AnimationProgramAppendPayload] 向已激活程序追加指令（delay 游标推进后的新步骤）；
 * - [SetProgramVarPayload] 热更程序变量（公式字符串）；
 * - [StopAnimationProgramPayload] 停止程序，可选同时销毁粒子。
 *
 * 指令全部为纯数据；客户端按服务端 gameTime 锚点对齐时钟后本地求值。
 */

/** 编排动画程序编解码工具。 */
internal object AnimationProgramCodecs {
    fun writeInstructionList(buf: FriendlyByteBuf, list: List<AnimInstruction>) {
        buf.writeVarInt(list.size)
        for (ins in list) ins.write(buf)
    }

    fun readInstructionList(buf: FriendlyByteBuf): List<AnimInstruction> {
        val n = buf.readVarInt()
        return List(n) { AnimInstruction.read(buf) }
    }

    fun writeUuidList(buf: FriendlyByteBuf, ids: List<UUID>) {
        buf.writeVarInt(ids.size)
        for (id in ids) StreamCodecs.UUID_CODEC.encode(buf, id)
    }

    fun readUuidList(buf: FriendlyByteBuf): List<UUID> {
        val n = buf.readVarInt()
        return List(n) { StreamCodecs.UUID_CODEC.decode(buf) }
    }

    fun writeChannels(buf: FriendlyByteBuf, channels: List<InputChannel>) {
        buf.writeVarInt(channels.size)
        for (c in channels) {
            buf.writeUtf(c.slot)
            StreamCodecs.UUID_CODEC.encode(buf, c.uuid)
        }
    }

    fun readChannels(buf: FriendlyByteBuf): List<InputChannel> {
        val n = buf.readVarInt()
        return List(n) { InputChannel(buf.readUtf(), StreamCodecs.UUID_CODEC.decode(buf)) }
    }

    fun writeVars(buf: FriendlyByteBuf, vars: Map<String, Double>) {
        buf.writeVarInt(vars.size)
        for ((k, v) in vars) {
            buf.writeUtf(k)
            buf.writeDouble(v)
        }
    }

    fun readVars(buf: FriendlyByteBuf): Map<String, Double> {
        val n = buf.readVarInt()
        val out = LinkedHashMap<String, Double>(n)
        repeat(n) { out[buf.readUtf()] = buf.readDouble() }
        return out
    }

    fun writeVec(buf: FriendlyByteBuf, v: Vec3) {
        buf.writeDouble(v.x); buf.writeDouble(v.y); buf.writeDouble(v.z)
    }

    fun readVec(buf: FriendlyByteBuf): Vec3 = Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble())
}

/**
 * 首次下发动画程序。
 *
 * @param programId 程序 ID（= 粒子组 ID）
 * @param particleIds 受控粒子清单
 * @param anchorGameTime 下发时的服务端 gameTime：客户端据此对齐时钟消除漂移
 * @param initialPivot 初始轴心（客户端据此把各粒子 spawn 位置换算为相对偏移）
 * @param entities 实体注册表（下发顺序 = 句柄序号；公式经 get_entity_* 被动取值）
 * @param vars 程序初始变量
 * @param instructions 初始指令流
 */
data class AnimationProgramPayload(
    val programId: UUID,
    val particleIds: List<UUID>,
    val anchorGameTime: Long,
    val initialPivot: Vec3,
    val entities: List<InputChannel>,
    val vars: Map<String, Double>,
    val instructions: List<AnimInstruction>,
) : CustomPacketPayload {

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<AnimationProgramPayload>(
            Identifier.fromNamespaceAndPath("particledrawing", "anim_program")
        )

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, AnimationProgramPayload> =
            object : StreamCodec<FriendlyByteBuf, AnimationProgramPayload> {
                override fun decode(buf: FriendlyByteBuf): AnimationProgramPayload =
                    AnimationProgramPayload(
                        StreamCodecs.UUID_CODEC.decode(buf),
                        AnimationProgramCodecs.readUuidList(buf),
                        buf.readLong(),
                        AnimationProgramCodecs.readVec(buf),
                        AnimationProgramCodecs.readChannels(buf),
                        AnimationProgramCodecs.readVars(buf),
                        AnimationProgramCodecs.readInstructionList(buf),
                    )

                override fun encode(buf: FriendlyByteBuf, p: AnimationProgramPayload) {
                    StreamCodecs.UUID_CODEC.encode(buf, p.programId)
                    AnimationProgramCodecs.writeUuidList(buf, p.particleIds)
                    buf.writeLong(p.anchorGameTime)
                    AnimationProgramCodecs.writeVec(buf, p.initialPivot)
                    AnimationProgramCodecs.writeChannels(buf, p.entities)
                    AnimationProgramCodecs.writeVars(buf, p.vars)
                    AnimationProgramCodecs.writeInstructionList(buf, p.instructions)
                }
            }
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

/** 追加指令到已激活程序（增量、保序）。 */
data class AnimationProgramAppendPayload(
    val programId: UUID,
    val instructions: List<AnimInstruction>,
) : CustomPacketPayload {

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<AnimationProgramAppendPayload>(
            Identifier.fromNamespaceAndPath("particledrawing", "anim_program_append")
        )

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, AnimationProgramAppendPayload> =
            object : StreamCodec<FriendlyByteBuf, AnimationProgramAppendPayload> {
                override fun decode(buf: FriendlyByteBuf): AnimationProgramAppendPayload =
                    AnimationProgramAppendPayload(
                        StreamCodecs.UUID_CODEC.decode(buf),
                        AnimationProgramCodecs.readInstructionList(buf),
                    )

                override fun encode(buf: FriendlyByteBuf, p: AnimationProgramAppendPayload) {
                    StreamCodecs.UUID_CODEC.encode(buf, p.programId)
                    AnimationProgramCodecs.writeInstructionList(buf, p.instructions)
                }
            }
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

/** 热更程序变量：[value] 为公式字符串，在现有变量与实体通道环境下求值。 */
data class SetProgramVarPayload(
    val programId: UUID,
    val name: String,
    val value: String,
) : CustomPacketPayload {

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<SetProgramVarPayload>(
            Identifier.fromNamespaceAndPath("particledrawing", "anim_program_set_var")
        )

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, SetProgramVarPayload> =
            object : StreamCodec<FriendlyByteBuf, SetProgramVarPayload> {
                override fun decode(buf: FriendlyByteBuf): SetProgramVarPayload =
                    SetProgramVarPayload(StreamCodecs.UUID_CODEC.decode(buf), buf.readUtf(), buf.readUtf())

                override fun encode(buf: FriendlyByteBuf, p: SetProgramVarPayload) {
                    StreamCodecs.UUID_CODEC.encode(buf, p.programId)
                    buf.writeUtf(p.name)
                    buf.writeUtf(p.value)
                }
            }
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

/** 停止程序；[destroyParticles] 为 true 时同时销毁受控粒子。 */
data class StopAnimationProgramPayload(
    val programId: UUID,
    val destroyParticles: Boolean,
) : CustomPacketPayload {

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<StopAnimationProgramPayload>(
            Identifier.fromNamespaceAndPath("particledrawing", "anim_program_stop")
        )

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, StopAnimationProgramPayload> =
            object : StreamCodec<FriendlyByteBuf, StopAnimationProgramPayload> {
                override fun decode(buf: FriendlyByteBuf): StopAnimationProgramPayload =
                    StopAnimationProgramPayload(StreamCodecs.UUID_CODEC.decode(buf), buf.readBoolean())

                override fun encode(buf: FriendlyByteBuf, p: StopAnimationProgramPayload) {
                    StreamCodecs.UUID_CODEC.encode(buf, p.programId)
                    buf.writeBoolean(p.destroyParticles)
                }
            }
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

