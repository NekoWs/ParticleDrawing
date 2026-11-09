package work.nekow.particledrawing.core.client

import kotlin.math.floor

/** 纵向帧图集：有限寿命播完整个序列，无限寿命默认每 60 tick 循环一次。 */
data class ParticleSpriteAnimation(val frameHeight: Int, val frames: Int, val fps: Double? = null) {
    init {
        require(frameHeight > 0 && frames > 0)
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
