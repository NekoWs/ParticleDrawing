package work.nekow.particledrawing.animation

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.api.ParticleStyle
import work.nekow.particledrawing.animation.expr.Keyframe
import work.nekow.particledrawing.core.easing.EasingType

/**
 * 解析后的粒子动画（对应网页编辑器导出的 .pdraw 工程文件）。
 */
class ParticleAnimation(
    val loop: Boolean,
    val particles: List<AnimParticle>,
    val tracks: List<AnimTrack>,
    val groups: Map<String, List<String>>,
    val functions: List<FunctionObject> = emptyList()
)

/**
 * 函数对象（.pdraw 的 f 字段）：公式代码块 + 变量，客户端实时求值生成派生粒子。
 */
class FunctionObject(
    val id: String,
    val name: String,
    val center: DoubleArray,
    val count: Int,
    val style: ParticleStyle,
    val code: String,
    val vars: Map<String, FunctionVar>,
    val duration: Int,
    val step: Int
)

/**
 * 函数对象变量：表达式（无关键帧时求值）或关键帧（关键帧优先，b[2] 缓动）。
 * expr/kf 可变，支持服务端下发变量更新。
 */
class FunctionVar(
    var expr: String,
    var kf: List<Keyframe>
)

/**
 * 动画中的单个粒子定义。
 */
class AnimParticle(
    val id: String,
    val style: ParticleStyle,
    val color: Color,
    val scale: Float,
    val glowing: Boolean,
    val lightLevel: Int,
    val pos: Vec3,
    val vel: Vec3
)

/**
 * 一条分量轨道，作用于一组目标（按 id 或 "g:name" 或 "f:fxId"）的某个分量。
 *
 * @param pr 分量轨道标识，如 "pos.x" / "rot.y" / "col.a" / "scl"
 * @param mode SET=关键帧值为绝对值；OP=关键帧值为增量（叠加到每个成员的基础值上）
 */
class AnimTrack(
    val pr: String,
    val ids: List<String>,
    val keyframes: List<AnimKeyframe>,
    val mode: Mode
) {
    enum class Mode { SET, OP }
}

/**
 * 单个关键帧。
 *
 * @param tick 触发时刻（tick）
 * @param value 目标标量值（rot 为度，渲染时转弧度）
 * @param easing 到下一个关键帧的缓动类型
 */
class AnimKeyframe(
    val tick: Int,
    val value: Double,
    val easing: EasingType
)
