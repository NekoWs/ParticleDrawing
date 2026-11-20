package work.nekow.particledrawing.api

import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.translation

/** 拖尾两端的宽度倍率、颜色与粒子结束后的淡出时长。 */
data class ParticleTrailStyle @JvmOverloads constructor(
    val lingerTicks: Int = 0,
    val tailWidth: Float = 0f,
    val headWidth: Float = 1f,
    val tailColor: Color? = null,
    val headColor: Color? = null,
) {
    init {
        requireLocalized(lingerTicks in 0..MAX_LINGER_TICKS) { translation("error.visual.trail_linger", MAX_LINGER_TICKS) }
        requireLocalized(tailWidth in 0f..MAX_WIDTH && headWidth in 0f..MAX_WIDTH) {
            translation("error.visual.trail_width", MAX_WIDTH.toInt())
        }
        requireLocalized(listOfNotNull(tailColor, headColor).all {
            it.r in 0f..1f && it.g in 0f..1f && it.b in 0f..1f && it.a in 0f..1f
        }) { translation("error.visual.trail_color") }
    }

    internal val byDistance get() = tailWidth != 0f || headWidth != 1f || tailColor != null || headColor != null
    internal val maxWidth get() = maxOf(tailWidth, headWidth)

    internal fun widthAt(progress: Float) = tailWidth + (headWidth - tailWidth) * progress

    internal fun colorAt(base: Color, progress: Float): Color =
        (tailColor ?: base.withAlpha(0f)).lerp(headColor ?: base, progress)

    companion object {
        @JvmField val DEFAULT = ParticleTrailStyle()
        const val MAX_LINGER_TICKS = 200
        const val MAX_WIDTH = 8f
    }
}
