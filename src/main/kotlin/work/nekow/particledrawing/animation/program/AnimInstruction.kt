package work.nekow.particledrawing.animation.program

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.Orient
import work.nekow.particledrawing.core.easing.EasingType
import java.util.UUID

// 客户端动画程序：编排式动画（ParticleGroup 链式调用）录制成的声明式指令流，服务端下发一次，客户端本地求值直写渲染——持续动画零带宽。
// 指令全部为纯数据；startMs 是相对程序起点的毫秒，客户端用 payload 的 gameTime 锚点对齐时钟（1 game tick = 50ms）。

/**
 * 动画指令类型：枚举序号即网络传输标签（VarInt），双端按同一顺序编解码。
 */
enum class InstructionType {
    FADE_IN, FADE_OUT, RECOLOR, SCALE_BY, TRANSLATE,
    ROTATE_ONCE, MOVE_PATH, SPIN, PULSE, STOP_CONTINUOUS, BIND_PIVOT, EXPRESSION,
    MOVE_EACH, SCALE_TO;

    companion object {
        private val BY_ORDINAL = entries.toTypedArray()

        /** 序号反查；越界视为协议损坏并抛错。 */
        fun fromOrdinal(raw: Int): InstructionType =
            BY_ORDINAL.getOrNull(raw) ?: throw IllegalArgumentException("未知动画指令 tag=$raw")
    }
}

/**
 * 一条指令的**有限时长**（毫秒）；返回 null 表示它是无限持续的（`spin`、无限 `pulse`、表达式）。
 *
 * 「这段编排什么时候跑完」由它算出来：服务端据此排定兜底收尾，客户端据此上报完成。
 * 无限持续的指令不参与判定——它不给出终点，也拦不住后面有限指令的终点。
 */
internal fun AnimInstruction.finiteDurationMs(): Int? = when (this) {
    is AnimInstruction.Spin -> null
    is AnimInstruction.Expression -> if (durationMs <= 0) null else durationMs
    is AnimInstruction.Pulse -> if (cycles < 0) null else halfPeriodMs * 2 * cycles
    is AnimInstruction.FadeIn -> durationMs
    is AnimInstruction.FadeOut -> durationMs
    is AnimInstruction.Recolor -> durationMs
    is AnimInstruction.ScaleBy -> durationMs
    is AnimInstruction.ScaleTo -> durationMs
    is AnimInstruction.Translate -> durationMs
    is AnimInstruction.RotateOnce -> durationMs
    is AnimInstruction.MovePath -> durationMs
    is AnimInstruction.MoveEach -> durationMs
    is AnimInstruction.StopContinuous -> 0
    is AnimInstruction.BindPivot -> 0
}

/**
 * 变换基准点（轴心）引用：固定世界坐标，或跟随某个实体的位置（+偏移）。
 *
 * 只在 [AnimInstruction.BindPivot] 里下发——轴心是**程序级的状态**，绑定一次对之后所有旋转/缩放类指令生效；
 * 旋转类指令（[AnimInstruction.RotateOnce] / [AnimInstruction.Spin]）自己不带轴心，绕的就是当前绑定的那个。
 */
sealed class PivotRef {
    /** 本引用的种类标签（网络序号 = ordinal）。 */
    enum class Kind { FIXED, FOLLOW_ENTITY, MOVABLE }

    abstract val kind: Kind

    /** 固定世界坐标。 */
    data class Fixed(val pos: Vec3) : PivotRef() {
        override val kind get() = Kind.FIXED
    }

    /**
     * 跟随实体：轴心 = 实体位置 + [offset]，由客户端本地解析实体。
     * [local] 为 true 时整组连偏移一起按实体朝向旋转（组跟着实体转，用于「贴在身前/身侧」的编排）。
     */
    data class FollowEntity(val uuid: UUID, val offset: Vec3, val local: Boolean = false) : PivotRef() {
        override val kind get() = Kind.FOLLOW_ENTITY
    }

    /**
     * 可移动轴心：位置由服务端逐 tick 的样本更新（见 `ParticleGroup.updateAnchor`），
     * 客户端按**相邻两个样本**插值渲染；[orient] 为 [Orient.VELOCITY] 时整组随运动方向转向。
     *
     * 绑定一次即可（不像固定轴心那样每 tick 追加一条绑定指令），位置更新走专用小包。
     */
    data class Movable(
        val pos: Vec3,
        val velocity: Vec3 = Vec3.ZERO,
        val orient: Orient = Orient.VELOCITY,
    ) : PivotRef() {
        override val kind get() = Kind.MOVABLE
    }

    companion object {
        fun write(buf: FriendlyByteBuf, ref: PivotRef) {
            when (ref) {
                is Fixed -> {
                    buf.writeVarInt(ref.kind.ordinal)
                    writeVec(buf, ref.pos)
                }
                is FollowEntity -> {
                    buf.writeVarInt(ref.kind.ordinal)
                    buf.writeUUID(ref.uuid)
                    writeVec(buf, ref.offset)
                    buf.writeBoolean(ref.local)
                }
                is Movable -> {
                    buf.writeVarInt(ref.kind.ordinal)
                    writeVec(buf, ref.pos)
                    writeVec(buf, ref.velocity)
                    buf.writeByte(if (ref.orient == Orient.VELOCITY) 0 else 1)
                }
            }
        }

        fun read(buf: FriendlyByteBuf): PivotRef = when (buf.readVarInt()) {
            Kind.FOLLOW_ENTITY.ordinal -> FollowEntity(buf.readUUID(), readVec(buf), buf.readBoolean())
            Kind.MOVABLE.ordinal -> Movable(
                readVec(buf), readVec(buf),
                if (buf.readByte().toInt() == 0) Orient.VELOCITY else Orient.WORLD,
            )
            else -> Fixed(readVec(buf))
        }
    }
}

/**
 * 实体绑定记录：把一个实体以 [handle] 名登记进程序的实体注册表（下发顺序 = 句柄序号）。
 * 公式内通过 `get_entity_<prop>(<handle>)` 被动取值（见 expr/Getters）；
 * 客户端每 tick 本地解析实体，零带宽同步。亦用作轴心跟随的内部载体。
 */
data class EntityBinding(val handle: String, val uuid: UUID)

// FriendlyByteBuf 原生提供 writeUUID/readUUID；Vec3 与缓动的编解码见下方工具函数。

internal fun writeVec(buf: FriendlyByteBuf, v: Vec3) {
    buf.writeDouble(v.x); buf.writeDouble(v.y); buf.writeDouble(v.z)
}
internal fun readVec(buf: FriendlyByteBuf): Vec3 = Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble())

/** 缓动序列化：复用 EasingType 的 DoubleArray 表示。 */
internal fun writeEasing(buf: FriendlyByteBuf, easing: EasingType) {
    val d = easing.serialize()
    for (v in d) buf.writeDouble(v)
}
internal fun readEasing(buf: FriendlyByteBuf): EasingType =
    EasingType.deserialize(DoubleArray(5) { buf.readDouble() })

/**
 * 动画指令基类。[startMs] 为相对程序起点的时间线时刻（毫秒，delay 游标编译产物）。
 * [type] 的 ordinal 即网络传输标签，见 [InstructionType]。
 */
sealed class AnimInstruction {
    abstract val startMs: Int
    abstract val type: InstructionType

    fun write(buf: FriendlyByteBuf) {
        buf.writeVarInt(type.ordinal)
        buf.writeVarInt(startMs)
        writeBody(buf)
    }

    protected abstract fun writeBody(buf: FriendlyByteBuf)

    /**
     * 平移时间线时刻：增量追加的指令由服务端按「从现在起」改写 startMs 时用
     * （见 `ParticleGroup` 的时间轴换算）；[delta] 为 0 时原样返回。
     */
    fun shiftStartMs(delta: Int): AnimInstruction {
        if (delta == 0) return this
        return when (this) {
            is FadeIn -> copy(startMs = startMs + delta)
            is FadeOut -> copy(startMs = startMs + delta)
            is Recolor -> copy(startMs = startMs + delta)
            is ScaleBy -> copy(startMs = startMs + delta)
            is Translate -> copy(startMs = startMs + delta)
            is RotateOnce -> copy(startMs = startMs + delta)
            is MovePath -> copy(startMs = startMs + delta)
            is Spin -> copy(startMs = startMs + delta)
            is Pulse -> copy(startMs = startMs + delta)
            is StopContinuous -> copy(startMs = startMs + delta)
            is BindPivot -> copy(startMs = startMs + delta)
            is Expression -> copy(startMs = startMs + delta)
            is MoveEach -> copy(startMs = startMs + delta)
            is ScaleTo -> copy(startMs = startMs + delta)
        }
    }

    companion object {
        fun read(buf: FriendlyByteBuf): AnimInstruction {
            val type = InstructionType.fromOrdinal(buf.readVarInt())
            val startMs = buf.readVarInt()
            return when (type) {
                InstructionType.FADE_IN -> FadeIn(startMs, buf.readVarInt(), readEasing(buf))
                InstructionType.FADE_OUT -> FadeOut(startMs, buf.readVarInt(), readEasing(buf))
                InstructionType.RECOLOR -> Recolor(startMs, buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readVarInt(), readEasing(buf))
                InstructionType.SCALE_BY -> ScaleBy(startMs, buf.readFloat(), buf.readVarInt(), readEasing(buf))
                InstructionType.TRANSLATE -> Translate(startMs, readVec(buf), buf.readVarInt(), readEasing(buf))
                InstructionType.ROTATE_ONCE -> RotateOnce(startMs, readVec(buf), buf.readDouble(), buf.readVarInt(), readEasing(buf))
                InstructionType.MOVE_PATH -> {
                    val n = buf.readVarInt()
                    MovePath(startMs, List(n) { readVec(buf) }, buf.readVarInt(), readEasing(buf))
                }
                InstructionType.SPIN -> Spin(startMs, readVec(buf), buf.readDouble())
                InstructionType.PULSE -> Pulse(startMs, buf.readFloat(), buf.readVarInt(), buf.readVarInt())
                InstructionType.STOP_CONTINUOUS -> StopContinuous(startMs)
                InstructionType.BIND_PIVOT -> BindPivot(startMs, PivotRef.read(buf))
                InstructionType.EXPRESSION -> Expression(startMs, buf.readUtf(), buf.readVarInt())
                InstructionType.MOVE_EACH -> MoveEach(startMs, buf.readFloat(), buf.readVarInt(), readEasing(buf))
                InstructionType.SCALE_TO -> ScaleTo(startMs, buf.readFloat(), buf.readVarInt(), readEasing(buf))
            }
        }
    }

    // —— 外观 ——

    /** 整组淡入：alpha 因子从 0 缓动到 1。 */
    data class FadeIn(
        override val startMs: Int,
        val durationMs: Int,
        val easing: EasingType,
    ) : AnimInstruction() {
        override val type get() = InstructionType.FADE_IN
        override fun writeBody(buf: FriendlyByteBuf) {
            buf.writeVarInt(durationMs); writeEasing(buf, easing)
        }
    }

    /** 整组淡出：alpha 因子缓动到 0；结束后由服务端销毁组。 */
    data class FadeOut(
        override val startMs: Int,
        val durationMs: Int,
        val easing: EasingType,
    ) : AnimInstruction() {
        override val type get() = InstructionType.FADE_OUT
        override fun writeBody(buf: FriendlyByteBuf) {
            buf.writeVarInt(durationMs); writeEasing(buf, easing)
        }
    }

    /** 重着色到目标 RGBA。 */
    data class Recolor(
        override val startMs: Int,
        val r: Float, val g: Float, val b: Float, val a: Float,
        val durationMs: Int,
        val easing: EasingType,
    ) : AnimInstruction() {
        override val type get() = InstructionType.RECOLOR
        override fun writeBody(buf: FriendlyByteBuf) {
            buf.writeFloat(r); buf.writeFloat(g); buf.writeFloat(b); buf.writeFloat(a)
            buf.writeVarInt(durationMs); writeEasing(buf, easing)
        }
    }

    /** 等比缩放：粒子大小与到轴心的距离 ×[ratio]，**在当前倍率之上相乘**（不会把已有倍率重置回 1）。 */
    data class ScaleBy(
        override val startMs: Int,
        val ratio: Float,
        val durationMs: Int,
        val easing: EasingType,
    ) : AnimInstruction() {
        override val type get() = InstructionType.SCALE_BY
        override fun writeBody(buf: FriendlyByteBuf) {
            buf.writeFloat(ratio); buf.writeVarInt(durationMs); writeEasing(buf, easing)
        }
    }

    /**
     * 把组级倍率缓动到**绝对目标** [target]（1 = 原始尺寸，0 = 完全收起不绘制）。
     *
     * 与 [ScaleBy] 的区别只在「终点怎么算」：本指令的终点是写死的 [target]，
     * 起点则是**执行那一刻**的当前合成倍率——所以可以从任意倍率接退场、也可以从 0 展开，
     * 中途追加的缩放不会把已经累积的倍率清掉。
     */
    data class ScaleTo(
        override val startMs: Int,
        val target: Float,
        val durationMs: Int,
        val easing: EasingType,
    ) : AnimInstruction() {
        override val type get() = InstructionType.SCALE_TO
        override fun writeBody(buf: FriendlyByteBuf) {
            buf.writeFloat(target); buf.writeVarInt(durationMs); writeEasing(buf, easing)
        }
    }

    // —— 变换 ——

    /** 组平移 [delta]（世界空间）。 */
    data class Translate(
        override val startMs: Int,
        val delta: Vec3,
        val durationMs: Int,
        val easing: EasingType,
    ) : AnimInstruction() {
        override val type get() = InstructionType.TRANSLATE
        override fun writeBody(buf: FriendlyByteBuf) {
            writeVec(buf, delta); buf.writeVarInt(durationMs); writeEasing(buf, easing)
        }
    }

    /** 绕**当前轴心绑定**一次性旋转（轴心只由 [BindPivot] 决定，见 `ParticleGroup.setPivot`/`followEntity`）。 */
    data class RotateOnce(
        override val startMs: Int,
        val axis: Vec3,
        val radians: Double,
        val durationMs: Int,
        val easing: EasingType,
    ) : AnimInstruction() {
        override val type get() = InstructionType.ROTATE_ONCE
        override fun writeBody(buf: FriendlyByteBuf) {
            writeVec(buf, axis)
            buf.writeDouble(radians); buf.writeVarInt(durationMs); writeEasing(buf, easing)
        }
    }

    /** 折线路径移动：途经点为绝对坐标（从当前位置出发依次经过）。 */
    data class MovePath(
        override val startMs: Int,
        val points: List<Vec3>,
        val durationMs: Int,
        val easing: EasingType,
    ) : AnimInstruction() {
        override val type get() = InstructionType.MOVE_PATH
        override fun writeBody(buf: FriendlyByteBuf) {
            buf.writeVarInt(points.size)
            for (p in points) writeVec(buf, p)
            buf.writeVarInt(durationMs); writeEasing(buf, easing)
        }
    }

    // —— 持续（客户端积分，零带宽） ——

    /**
     * 无限匀速旋转，直到 [StopContinuous]。
     *
     * 同 [RotateOnce]：绕**当前轴心绑定**转（只认 [BindPivot] 设的轴心），指令里不带轴心。
     */
    data class Spin(
        override val startMs: Int,
        val axis: Vec3,
        val radiansPerMs: Double,
    ) : AnimInstruction() {
        override val type get() = InstructionType.SPIN
        override fun writeBody(buf: FriendlyByteBuf) {
            writeVec(buf, axis); buf.writeDouble(radiansPerMs)
        }
    }

    /** 呼吸脉冲：1× ↔ [peakRatio]× 往复；[cycles] 负数无限。 */
    data class Pulse(
        override val startMs: Int,
        val peakRatio: Float,
        val halfPeriodMs: Int,
        val cycles: Int,
    ) : AnimInstruction() {
        override val type get() = InstructionType.PULSE
        override fun writeBody(buf: FriendlyByteBuf) {
            buf.writeFloat(peakRatio); buf.writeVarInt(halfPeriodMs); buf.writeVarInt(cycles)
        }
    }

    /** 冻结此前全部持续型指令（spin/pulse 保持当前状态不再推进）。 */
    data class StopContinuous(override val startMs: Int) : AnimInstruction() {
        override val type get() = InstructionType.STOP_CONTINUOUS
        override fun writeBody(buf: FriendlyByteBuf) {}
    }

    /** 切换/设置变换基准点（轴心）：绑定到实体后，之后所有旋转/缩放类指令都绕它算。 */
    data class BindPivot(
        override val startMs: Int,
        val pivot: PivotRef,
    ) : AnimInstruction() {
        override val type get() = InstructionType.BIND_PIVOT
        override fun writeBody(buf: FriendlyByteBuf) {
            PivotRef.write(buf, pivot)
        }
    }

    /**
     * 表达式指令：整段标量公式代码（专用旧式语法：i/n/t、[x,y,z]=...；
     * 与 .pdraw 函数对象的 this 脚本语言不同）每粒子每 tick 求值。
     * 输出 [x,y,z] 为世界绝对坐标，可用被动输入 getter（get_entity_* /get_world_*）、
     * 内建 i/n/t、全套标量数学函数与程序变量；一旦出现即接管位置/颜色/缩放的最终解释权，
     * FADE 因子仍叠加在输出的 alpha 之上。
     */
    data class Expression(
        override val startMs: Int,
        val code: String,
        /** 有限时长（毫秒）；<=0 = 一直求值（默认）。到期后不再求值，粒子停在最后一帧的状态。 */
        val durationMs: Int = 0,
    ) : AnimInstruction() {
        override val type get() = InstructionType.EXPRESSION
        override fun writeBody(buf: FriendlyByteBuf) {
            buf.writeUtf(code)
            buf.writeVarInt(durationMs)
        }
    }

    /**
     * **逐成员各自方向**的平移：每颗粒子沿自己「相对轴心的偏移」方向外移
     * `offsetScale × |偏移|`（对「球面碎片向外飞」是现成语义）。
     *
     * 与 [Translate] 的区别：[Translate] 整组一个位移向量，表达不了「各飞各的」——
     * 那样只能一片一个组，组数直接等于 arm 日志行数与 arm 开销。本指令整组一个包就够。
     */
    data class MoveEach(
        override val startMs: Int,
        val offsetScale: Float,
        val durationMs: Int,
        val easing: EasingType,
    ) : AnimInstruction() {
        override val type get() = InstructionType.MOVE_EACH
        override fun writeBody(buf: FriendlyByteBuf) {
            buf.writeFloat(offsetScale); buf.writeVarInt(durationMs); writeEasing(buf, easing)
        }
    }
}
