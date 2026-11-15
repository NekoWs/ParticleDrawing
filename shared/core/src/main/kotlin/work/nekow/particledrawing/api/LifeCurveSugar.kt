package work.nekow.particledrawing.api

import work.nekow.particledrawing.core.easing.EasingType
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.translation

/**
 * `fadeOut` / `shrinkTo` 这两种糖的曲线换算。
 *
 * 它们锚在寿命末尾，必须等寿命定下来才算得出关键帧时刻；逐颗生成、批量规格、发射器共用这一份。
 * 无限寿命（lifetime <= 0）没有末尾可言，直接报错，要求改用显式的 [ParticleCurve]。
 */
object LifeCurveSugar {

    /** 寿命最后 [ticks] tick 内 alpha 从 1 到 0。 */
    fun fadeOut(lifetimeTicks: Int, ticks: Int, easing: EasingType): ParticleCurve {
        requireLocalized(ticks > 0) { translation("error.curve.fade_duration") }
        requireLocalized(lifetimeTicks > 0) {
            translation("error.curve.fade_lifetime")
        }
        val start = (lifetimeTicks - ticks).coerceAtLeast(0)
        return ParticleCurve.alpha(
            CurveKey.at(start, 1f),
            CurveKey.at(lifetimeTicks, 0f, easing),
        )
    }

    /** 寿命最后 [ticks] tick 内尺寸从 1 到 [factor] 倍。 */
    fun shrinkTo(lifetimeTicks: Int, factor: Float, ticks: Int, easing: EasingType): ParticleCurve {
        requireLocalized(ticks > 0) { translation("error.curve.shrink_duration") }
        requireLocalized(factor >= 0f) { translation("error.curve.shrink_scale") }
        requireLocalized(lifetimeTicks > 0) {
            translation("error.curve.shrink_lifetime")
        }
        val start = (lifetimeTicks - ticks).coerceAtLeast(0)
        return ParticleCurve.scale(
            CurveKey.at(start, 1f),
            CurveKey.at(lifetimeTicks, factor, easing),
        )
    }
}
