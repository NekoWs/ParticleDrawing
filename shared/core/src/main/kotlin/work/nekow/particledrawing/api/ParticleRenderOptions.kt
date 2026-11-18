package work.nekow.particledrawing.api

import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.translation

/** 依赖粒子运动或附近表面的外观设置。 */
data class ParticleRenderOptions(
    val alignVelocity: Boolean = false,
    val stretch: Double = 0.6,
    val softFade: Double = 0.0,
    val trailTicks: Int = 0,
) {
    val active: Boolean get() = alignVelocity || softFade > 0.0 || trailTicks > 0

    init {
        validateStretch(stretch)
        validateSoftFade(softFade)
        validateTrail(trailTicks)
    }

    companion object {
        @JvmField val DEFAULT = ParticleRenderOptions()

        internal fun validateStretch(value: Double) {
            requireLocalized(value in 0.0..VelocityStretch.MAX_STRETCH) {
                translation("error.visual.stretch", VelocityStretch.MAX_STRETCH.toInt())
            }
        }

        internal fun validateSoftFade(value: Double) {
            requireLocalized(value in 0.0..SoftFade.MAX_DISTANCE) {
                translation("error.visual.soft_fade", SoftFade.MAX_DISTANCE.toInt())
            }
        }

        internal fun validateTrail(value: Int) {
            requireLocalized(value in 0..ParticleVisual.MAX_TRAIL_TICKS) {
                translation("error.visual.trail_ticks", ParticleVisual.MAX_TRAIL_TICKS)
            }
        }
    }
}
