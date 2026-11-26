package work.nekow.particledrawing.core.client

import kotlin.math.floor

/** 粒子帧图集。旧资源帧纵向排列，预排图集可使用多列。 */
data class ParticleSpriteAnimation(
    val frameHeight: Int, val frames: Int, val fps: Double? = null, val frameWidth: Int = 0, val columns: Int = 1,
) {
    init {
        require(frameHeight > 0 && frames > 0 && columns > 0 && (frameWidth > 0 || columns == 1))
        require(fps == null || (fps.isFinite() && fps > 0 && fps <= 120))
    }
    fun frameAt(ageTicks: Double, lifetimeTicks: Int): Int {
        val age = ageTicks.coerceAtLeast(0.0)
        if (fps != null) return (floor(age * fps / 20.0) % frames).toInt()
        if (lifetimeTicks > 0) {
            return floor(age / lifetimeTicks * (frames - 1)).toInt().coerceIn(0, frames - 1)
        }
        return (floor(age / 60.0 * frames) % frames).toInt()
    }
}
