package work.nekow.particledrawing.api

import work.nekow.particledrawing.core.easing.EasingType

/**
 * `fadeOut` / `shrinkTo` 这两种糖的曲线换算。
 *
 * 它们锚在寿命末尾，必须等寿命定下来才算得出关键帧时刻；逐颗生成、批量规格、发射器共用这一份。
 * 无限寿命（lifetime <= 0）没有末尾可言，直接报错，要求改用显式的 [ParticleCurve]。
 */
internal object LifeCurveSugar {

    /** 寿命最后 [ticks] tick 内 alpha 从 1 到 0。 */
    fun fadeOut(lifetimeTicks: Int, ticks: Int, easing: EasingType): ParticleCurve {
        require(ticks > 0) { "fadeOut 的时长必须为正" }
        require(lifetimeTicks > 0) {
            "fadeOut 需要有限寿命（lifetime > 0）；无限寿命请改用 curve(CurveChannel.ALPHA, ...) 显式给关键帧"
        }
        val start = (lifetimeTicks - ticks).coerceAtLeast(0)
        return ParticleCurve.alpha(
            CurveKey.at(start, 1f),
            CurveKey.at(lifetimeTicks, 0f, easing),
        )
    }

    /** 寿命最后 [ticks] tick 内尺寸从 1 到 [factor] 倍。 */
    fun shrinkTo(lifetimeTicks: Int, factor: Float, ticks: Int, easing: EasingType): ParticleCurve {
        require(ticks > 0) { "shrinkTo 的时长必须为正" }
        require(factor >= 0f) { "shrinkTo 的目标倍率不能为负" }
        require(lifetimeTicks > 0) {
            "shrinkTo 需要有限寿命（lifetime > 0）；无限寿命请改用 curve(CurveChannel.SCALE, ...) 显式给关键帧"
        }
        val start = (lifetimeTicks - ticks).coerceAtLeast(0)
        return ParticleCurve.scale(
            CurveKey.at(start, 1f),
            CurveKey.at(lifetimeTicks, factor, easing),
        )
    }
}
