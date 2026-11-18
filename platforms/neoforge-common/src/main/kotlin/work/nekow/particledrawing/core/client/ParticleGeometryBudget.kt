package work.nekow.particledrawing.core.client

import work.nekow.particledrawing.config.ParticleDrawingConfig

/** 同一帧中管状拖尾和模型共用额外顶点预算。 */
internal object ParticleGeometryBudget {
    private var remaining = 200_000

    fun beginFrame() { remaining = ParticleDrawingConfig.CLIENT.maxParticleGeometryQuads.get() }

    fun reserve(quads: Int): Boolean {
        if (quads > remaining) return false
        remaining -= quads
        return true
    }
}
