package work.nekow.particledrawing.animation.program

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.core.easing.EasingType
import java.util.UUID

/**
 * 客户端动画程序：编排式动画（ParticleGroup 链式调用）录制成的声明式指令流。
 * 服务端只下发一次定义，客户端按时间线本地求值并直写渲染——持续动画零带宽、帧率级平滑。
 *
 * 指令全部为纯数据（无代码），引用双端共有的事实（世界坐标 / 实体 UUID / 游戏时间锚点）。
 * 时间语义：每条指令的 [AnimInstruction.startTick] 是相对程序起点的 tick；
 * 客户端用 payload 携带的服务端 gameTime 锚点对齐时钟，消除漂移。
 */

/**
 * 动画指令类型：枚举序号即网络传输标签（VarInt），双端按同一顺序编解码。
 */
enum class InstructionType {
    FADE_IN, FADE_OUT, RECOLOR, SCALE_BY, TRANSLATE,
    ROTATE_ONCE, MOVE_PATH, SPIN, PULSE, STOP_CONTINUOUS, BIND_PIVOT, EXPRESSION;

    companion object {
        private val BY_ORDINAL = entries.toTypedArray()

        /** 序号反查；越界视为协议损坏并抛错。 */
        fun fromOrdinal(raw: Int): InstructionType =
            BY_ORDINAL.getOrNull(raw) ?: throw IllegalArgumentException("未知动画指令 tag=$raw")
    }
}

/** 变换基准点引用：固定世界坐标，或跟随某个实体的位置（+偏移）。 */
sealed class PivotRef {
    /** 本引用的种类标签（网络序号 = ordinal）。 */
    enum class Kind { FIXED, FOLLOW_ENTITY }

    abstract val kind: Kind

    /** 固定世界坐标。 */
    data class Fixed(val pos: Vec3) : PivotRef() {
        override val kind get() = Kind.FIXED
    }

    /** 跟随实体：轴心 = 实体渲染位置 + [offset]，由客户端本地解析实体。 */
    data class FollowEntity(val uuid: UUID, val offset: Vec3) : PivotRef() {
        override val kind get() = Kind.FOLLOW_ENTITY
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
                }
            }
        }

        fun read(buf: FriendlyByteBuf): PivotRef = when (buf.readVarInt()) {
            Kind.FOLLOW_ENTITY.ordinal -> FollowEntity(buf.readUUID(), readVec(buf))
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
 * 动画指令基类。[startTick] 为相对程序起点的时间线时刻（delay 游标编译产物）。
 * [type] 的 ordinal 即网络传输标签，见 [InstructionType]。
 */
sealed class AnimInstruction {
    abstract val startTick: Int
    abstract val type: InstructionType

    fun write(buf: FriendlyByteBuf) {
        buf.writeVarInt(type.ordinal)
        buf.writeVarInt(startTick)
        writeBody(buf)
    }

    protected abstract fun writeBody(buf: FriendlyByteBuf)

    companion object {
        fun read(buf: FriendlyByteBuf): AnimInstruction {
            val type = InstructionType.fromOrdinal(buf.readVarInt())
            val startTick = buf.readVarInt()
            return when (type) {
                InstructionType.FADE_IN -> FadeIn(startTick, buf.readVarInt(), readEasing(buf))
                InstructionType.FADE_OUT -> FadeOut(startTick, buf.readVarInt(), readEasing(buf))
                InstructionType.RECOLOR -> Recolor(startTick, buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readVarInt(), readEasing(buf))
                InstructionType.SCALE_BY -> ScaleBy(startTick, buf.readFloat(), buf.readVarInt(), readEasing(buf))
                InstructionType.TRANSLATE -> Translate(startTick, readVec(buf), buf.readVarInt(), readEasing(buf))
                InstructionType.ROTATE_ONCE -> RotateOnce(startTick, PivotRef.read(buf), readVec(buf), buf.readDouble(), buf.readVarInt(), readEasing(buf))
                InstructionType.MOVE_PATH -> {
                    val n = buf.readVarInt()
                    MovePath(startTick, List(n) { readVec(buf) }, buf.readVarInt(), readEasing(buf))
                }
                InstructionType.SPIN -> Spin(startTick, PivotRef.read(buf), readVec(buf), buf.readDouble())
                InstructionType.PULSE -> Pulse(startTick, buf.readFloat(), buf.readVarInt(), buf.readVarInt())
                InstructionType.STOP_CONTINUOUS -> StopContinuous(startTick)
                InstructionType.BIND_PIVOT -> BindPivot(startTick, PivotRef.read(buf))
                InstructionType.EXPRESSION -> Expression(startTick, buf.readUtf())
            }
        }
    }

    // ---- 外观 ----

    /** 整组淡入：alpha 因子从 0 缓动到 1。 */
    data class FadeIn(
        override val startTick: Int,
        val durationTicks: Int,
        val easing: EasingType,
    ) : AnimInstruction() {
        override val type get() = InstructionType.FADE_IN
        override fun writeBody(buf: FriendlyByteBuf) {
            buf.writeVarInt(durationTicks); writeEasing(buf, easing)
        }
    }

    /** 整组淡出：alpha 因子缓动到 0；结束后由服务端销毁组。 */
    data class FadeOut(
        override val startTick: Int,
        val durationTicks: Int,
        val easing: EasingType,
    ) : AnimInstruction() {
        override val type get() = InstructionType.FADE_OUT
        override fun writeBody(buf: FriendlyByteBuf) {
            buf.writeVarInt(durationTicks); writeEasing(buf, easing)
        }
    }

    /** 重着色到目标 RGBA。 */
    data class Recolor(
        override val startTick: Int,
        val r: Float, val g: Float, val b: Float, val a: Float,
        val durationTicks: Int,
        val easing: EasingType,
    ) : AnimInstruction() {
        override val type get() = InstructionType.RECOLOR
        override fun writeBody(buf: FriendlyByteBuf) {
            buf.writeFloat(r); buf.writeFloat(g); buf.writeFloat(b); buf.writeFloat(a)
            buf.writeVarInt(durationTicks); writeEasing(buf, easing)
        }
    }

    /** 等比缩放：粒子大小 ×[ratio]。 */
    data class ScaleBy(
        override val startTick: Int,
        val ratio: Float,
        val durationTicks: Int,
        val easing: EasingType,
    ) : AnimInstruction() {
        override val type get() = InstructionType.SCALE_BY
        override fun writeBody(buf: FriendlyByteBuf) {
            buf.writeFloat(ratio); buf.writeVarInt(durationTicks); writeEasing(buf, easing)
        }
    }

    // ---- 变换 ----

    /** 组平移 [delta]（世界空间）。 */
    data class Translate(
        override val startTick: Int,
        val delta: Vec3,
        val durationTicks: Int,
        val easing: EasingType,
    ) : AnimInstruction() {
        override val type get() = InstructionType.TRANSLATE
        override fun writeBody(buf: FriendlyByteBuf) {
            writeVec(buf, delta); buf.writeVarInt(durationTicks); writeEasing(buf, easing)
        }
    }

    /** 绕基准点一次性旋转。 */
    data class RotateOnce(
        override val startTick: Int,
        val pivot: PivotRef,
        val axis: Vec3,
        val radians: Double,
        val durationTicks: Int,
        val easing: EasingType,
    ) : AnimInstruction() {
        override val type get() = InstructionType.ROTATE_ONCE
        override fun writeBody(buf: FriendlyByteBuf) {
            PivotRef.write(buf, pivot); writeVec(buf, axis)
            buf.writeDouble(radians); buf.writeVarInt(durationTicks); writeEasing(buf, easing)
        }
    }

    /** 折线路径移动：途经点为绝对坐标（从当前位置出发依次经过）。 */
    data class MovePath(
        override val startTick: Int,
        val points: List<Vec3>,
        val durationTicks: Int,
        val easing: EasingType,
    ) : AnimInstruction() {
        override val type get() = InstructionType.MOVE_PATH
        override fun writeBody(buf: FriendlyByteBuf) {
            buf.writeVarInt(points.size)
            for (p in points) writeVec(buf, p)
            buf.writeVarInt(durationTicks); writeEasing(buf, easing)
        }
    }

    // ---- 持续（客户端积分，零带宽） ----

    /** 无限匀速旋转，直到 [StopContinuous]。 */
    data class Spin(
        override val startTick: Int,
        val pivot: PivotRef,
        val axis: Vec3,
        val radiansPerTick: Double,
    ) : AnimInstruction() {
        override val type get() = InstructionType.SPIN
        override fun writeBody(buf: FriendlyByteBuf) {
            PivotRef.write(buf, pivot); writeVec(buf, axis); buf.writeDouble(radiansPerTick)
        }
    }

    /** 呼吸脉冲：1× ↔ [peakRatio]× 往复；[cycles] 负数无限。 */
    data class Pulse(
        override val startTick: Int,
        val peakRatio: Float,
        val halfPeriodTicks: Int,
        val cycles: Int,
    ) : AnimInstruction() {
        override val type get() = InstructionType.PULSE
        override fun writeBody(buf: FriendlyByteBuf) {
            buf.writeFloat(peakRatio); buf.writeVarInt(halfPeriodTicks); buf.writeVarInt(cycles)
        }
    }

    /** 冻结此前全部持续型指令（spin/pulse 保持当前状态不再推进）。 */
    data class StopContinuous(override val startTick: Int) : AnimInstruction() {
        override val type get() = InstructionType.STOP_CONTINUOUS
        override fun writeBody(buf: FriendlyByteBuf) {}
    }

    /** 切换/设置变换基准点（可切到跟随实体）。 */
    data class BindPivot(
        override val startTick: Int,
        val pivot: PivotRef,
    ) : AnimInstruction() {
        override val type get() = InstructionType.BIND_PIVOT
        override fun writeBody(buf: FriendlyByteBuf) {
            PivotRef.write(buf, pivot)
        }
    }

    /**
     * 表达式指令：整段函数对象代码（编辑器同款语法）每粒子每 tick 求值。
     * 输出 [x,y,z] 为世界绝对坐标，可用被动输入 getter（get_entity_* /get_world_*）、
     * 内建 i/n/t、全套数学函数与程序变量；一旦出现即接管位置/颜色/缩放的最终解释权，
     * FADE 因子仍叠加在输出的 alpha 之上。
     */
    data class Expression(
        override val startTick: Int,
        val code: String,
    ) : AnimInstruction() {
        override val type get() = InstructionType.EXPRESSION
        override fun writeBody(buf: FriendlyByteBuf) {
            buf.writeUtf(code)
        }
    }
}
