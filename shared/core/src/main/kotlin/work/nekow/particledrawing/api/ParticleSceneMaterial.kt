package work.nekow.particledrawing.api

import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.translation

/** 读取本帧场景的粒子外观。两项均为零时沿用原粒子材质。 */
data class ParticleSceneMaterial(
    val depthFade: Float = 0f,
    val distortion: Float = 0f,
) {
    init {
        requireLocalized(depthFade in 0f..16f) { translation("error.visual.depth_fade") }
        requireLocalized(distortion in 0f..32f) { translation("error.visual.distortion") }
    }

    val active: Boolean get() = depthFade > 0f || distortion > 0f

    companion object {
        @JvmField val NONE = ParticleSceneMaterial()
    }
}
