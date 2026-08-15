package work.nekow.particledrawing.animation

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.api.ParticleStyle
import work.nekow.particledrawing.core.easing.EasingType

/**
 * 解析后的粒子动画（对应网页编辑器导出的轻量 JSON 格式）。
 */
class ParticleAnimation(
    val loop: Boolean,
    val particles: List<AnimParticle>,
    val tracks: List<AnimTrack>,
    val groups: Map<String, List<String>>
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
 * 一条关键帧轨道，作用于一组粒子（按 id 或 "g:name" 或 "all"）的某个属性。
 *
 * @param mode SET=关键帧值为绝对值（所有成员设为该值）；OP=关键帧值为增量（叠加到每个成员的基础值上）
 */
class AnimTrack(
    val property: Property,
    val ids: List<String>,
    val keyframes: List<AnimKeyframe>,
    val mode: Mode
) {
    enum class Property {
        POSITION,
        ROTATION,
        VELOCITY,
        COLOR,
        SCALE;

        companion object {
            fun from(wire: String): Property = when (wire) {
                "rot" -> ROTATION
                "vel" -> VELOCITY
                "col" -> COLOR
                "scl" -> SCALE
                else -> POSITION
            }
        }
    }

    enum class Mode { SET, OP }
}

/**
 * 单个关键帧。
 *
 * @param tick 触发时刻（tick）
 * @param value 目标值：position=[x,y,z]，color=[r,g,b,a]，scale=单元素数组
 * @param easing 到下一个关键帧的缓动类型
 */
class AnimKeyframe(
    val tick: Int,
    val value: DoubleArray,
    val easing: EasingType
)
