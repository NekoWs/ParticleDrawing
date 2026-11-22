package work.nekow.particledrawing.core.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.animation.program.AnimInstruction
import work.nekow.particledrawing.animation.program.EntityBinding
import work.nekow.particledrawing.animation.program.PivotRef
import java.util.UUID
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.translation

// 客户端动画程序协议族：首次下发完整程序，Append 追加指令，SetProgramVar 热更变量，Stop 停止程序。
// 指令为纯数据；客户端按服务端 gameTime 锚点对齐时钟后本地求值。

/** 编排动画程序编解码工具。 */
internal object AnimationProgramCodecs {

    /** 单包指令条数上限。 */
    const val MAX_INSTRUCTIONS_PER_PAYLOAD = 512

    /** 一个组的成员上限：受控清单一次发完。 */
    const val MAX_PROGRAM_MEMBERS = 8192
    /** 单条编排里实体绑定与变量数量的上限。 */
    const val MAX_ENTITY_BINDINGS = 1024
    const val MAX_VARS = 1024

    // 一个坐标列表可占约 384 KiB，单独分包以免多段形变挤进同一份载荷。
    fun instructionChunks(instructions: List<AnimInstruction>): Sequence<List<AnimInstruction>> = sequence {
        var start = 0
        for ((index, instruction) in instructions.withIndex()) {
            if (instruction is AnimInstruction.Morph && instruction.slots != null) {
                if (start < index) yield(instructions.subList(start, index))
                yield(listOf(instruction))
                start = index + 1
            } else if (index - start + 1 == MAX_INSTRUCTIONS_PER_PAYLOAD) {
                yield(instructions.subList(start, index + 1))
                start = index + 1
            }
        }
        if (start < instructions.size) yield(instructions.subList(start, instructions.size))
    }

    fun snapshotInstructions(instructions: List<AnimInstruction>): List<AnimInstruction> {
        requireLocalized(instructions.size <= MAX_INSTRUCTIONS_PER_PAYLOAD) {
            translation("error.packet.instruction_limit", instructions.size, MAX_INSTRUCTIONS_PER_PAYLOAD)
        }
        // Netty 可能在服务端继续追加指令后才编码这个包。
        return java.util.List.copyOf(instructions)
    }

    fun writeInstructionList(buf: FriendlyByteBuf, list: List<AnimInstruction>) {
        buf.writeVarInt(list.size)
        for (ins in list) ins.write(buf)
    }

    fun readInstructionList(buf: FriendlyByteBuf): List<AnimInstruction> {
        val n = buf.readVarInt()
        requireLocalized(n in 0..MAX_INSTRUCTIONS_PER_PAYLOAD) { translation("error.packet.instruction_count", n) }
        return List(n) { AnimInstruction.read(buf) }
    }

    fun writeUuidList(buf: FriendlyByteBuf, ids: List<UUID>) {
        buf.writeVarInt(ids.size)
        for (id in ids) StreamCodecs.UUID_CODEC.encode(buf, id)
    }

    fun readUuidList(buf: FriendlyByteBuf): List<UUID> {
        val n = buf.readVarInt()
        requireLocalized(n in 0..MAX_PROGRAM_MEMBERS) { translation("error.packet.program_members", n, MAX_PROGRAM_MEMBERS) }
        return List(n) { StreamCodecs.UUID_CODEC.decode(buf) }
    }

    fun writeEntityBindings(buf: FriendlyByteBuf, bindings: List<EntityBinding>) {
        buf.writeVarInt(bindings.size)
        for (b in bindings) {
            buf.writeUtf(b.handle)
            StreamCodecs.UUID_CODEC.encode(buf, b.uuid)
        }
    }

    fun readEntityBindings(buf: FriendlyByteBuf): List<EntityBinding> {
        val n = buf.readVarInt()
        requireLocalized(n in 0..MAX_ENTITY_BINDINGS) { translation("error.packet.program_members", n, MAX_ENTITY_BINDINGS) }
        return List(n) { EntityBinding(buf.readUtf(), StreamCodecs.UUID_CODEC.decode(buf)) }
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
        requireLocalized(n in 0..MAX_VARS) { translation("error.packet.program_members", n, MAX_VARS) }
        val out = LinkedHashMap<String, Double>(n)
        repeat(n) { out[buf.readUtf()] = buf.readDouble() }
        return out
    }

}

/**
 * 首次下发动画程序。
 *
 * @param programId 程序 ID（= 粒子组 ID）
 * @param particleIds 受控粒子清单
 * @param anchorGameTime 下发时的服务端 gameTime：客户端据此对齐时钟消除漂移
 * @param programStartGameTime 程序原始起点（服务端 gameTime）；迟到补发时与 anchorGameTime 不同
 * @param initialPivot 初始轴心（客户端据此把各粒子 spawn 位置换算为相对偏移）
 * @param entityBindings 实体注册表（下发顺序 = 句柄序号；公式经 get_entity_* 被动取值）
 * @param vars 程序初始变量
 * @param instructions 初始指令流
 */
class AnimationProgramPayload(
    val programId: UUID,
    val particleIds: List<UUID>,
    val anchorGameTime: Long,
    val programStartGameTime: Long,
    val initialPivot: Vec3,
    val entityBindings: List<EntityBinding>,
    val vars: Map<String, Double>,
    instructions: List<AnimInstruction>,
) : CustomPacketPayload {

    val instructions: List<AnimInstruction> = AnimationProgramCodecs.snapshotInstructions(instructions)

    init {
        requireLocalized(particleIds.size <= AnimationProgramCodecs.MAX_PROGRAM_MEMBERS) {
            translation("error.packet.program_members", particleIds.size, AnimationProgramCodecs.MAX_PROGRAM_MEMBERS)
        }
    }

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
                        buf.readLong(),
                        StreamCodecs.readVec(buf),
                        AnimationProgramCodecs.readEntityBindings(buf),
                        AnimationProgramCodecs.readVars(buf),
                        AnimationProgramCodecs.readInstructionList(buf),
                    )

                override fun encode(buf: FriendlyByteBuf, p: AnimationProgramPayload) {
                    StreamCodecs.UUID_CODEC.encode(buf, p.programId)
                    AnimationProgramCodecs.writeUuidList(buf, p.particleIds)
                    buf.writeLong(p.anchorGameTime)
                    buf.writeLong(p.programStartGameTime)
                    StreamCodecs.writeVec(buf, p.initialPivot)
                    AnimationProgramCodecs.writeEntityBindings(buf, p.entityBindings)
                    AnimationProgramCodecs.writeVars(buf, p.vars)
                    AnimationProgramCodecs.writeInstructionList(buf, p.instructions)
                }
            }
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

/** 追加指令到已激活程序（增量、保序）。 */
class AnimationProgramAppendPayload(
    val programId: UUID,
    instructions: List<AnimInstruction>,
) : CustomPacketPayload {

    val instructions: List<AnimInstruction> = AnimationProgramCodecs.snapshotInstructions(instructions)

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

