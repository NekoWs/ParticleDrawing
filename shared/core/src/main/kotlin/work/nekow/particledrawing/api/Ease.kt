package work.nekow.particledrawing.api

import work.nekow.particledrawing.core.easing.EasingType

/**
 * 公开缓动入口，避免新代码直接依赖 `core.easing`。
 *
 * [type] 只供 PD 内部转换；既有 API 的 [EasingType] 参数仍完全兼容。
 */
class Ease private constructor(val type: EasingType) {

    override fun equals(other: Any?): Boolean = other is Ease && type == other.type
    override fun hashCode(): Int = type.hashCode()
    override fun toString(): String = "Ease(${type.name ?: "custom"})"

    companion object {
        @JvmField val LINEAR = Ease(EasingType.LINEAR)
        @JvmField val IN = Ease(EasingType.EASE_IN)
        @JvmField val OUT = Ease(EasingType.EASE_OUT)
        @JvmField val IN_OUT = Ease(EasingType.EASE_IN_OUT)
        @JvmField val IN_QUAD = Ease(EasingType.EASE_IN_QUAD)
        @JvmField val OUT_QUAD = Ease(EasingType.EASE_OUT_QUAD)
        @JvmField val IN_OUT_QUAD = Ease(EasingType.EASE_IN_OUT_QUAD)
        @JvmField val IN_CUBIC = Ease(EasingType.EASE_IN_CUBIC)
        @JvmField val OUT_CUBIC = Ease(EasingType.EASE_OUT_CUBIC)
        @JvmField val IN_OUT_CUBIC = Ease(EasingType.EASE_IN_OUT_CUBIC)
        @JvmField val IN_BOUNCE = Ease(EasingType.EASE_IN_BOUNCE)
        @JvmField val OUT_BOUNCE = Ease(EasingType.EASE_OUT_BOUNCE)
        @JvmField val IN_ELASTIC = Ease(EasingType.EASE_IN_ELASTIC)
        @JvmField val OUT_ELASTIC = Ease(EasingType.EASE_OUT_ELASTIC)

        /** 阶跃：保持上一关键帧，直到目标时刻才跳变。 */
        @JvmField val STEP = Ease(EasingType.NONE)

        // 兼容常见写法
        @JvmField val EASE_IN = IN
        @JvmField val EASE_OUT = OUT
        @JvmField val EASE_IN_OUT = IN_OUT

        @JvmStatic
        fun custom(x1: Double, y1: Double, x2: Double, y2: Double): Ease {
            require(x1.isFinite() && y1.isFinite() && x2.isFinite() && y2.isFinite()) {
                "缓动控制点必须是有限值"
            }
            return Ease(EasingType.custom(x1, y1, x2, y2))
        }

        @JvmStatic
        fun of(type: EasingType): Ease = Ease(type)
    }
}

fun Ease.toEasingType(): EasingType = type
