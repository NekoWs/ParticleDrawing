package work.nekow.particledrawing.core.client

import work.nekow.particledrawing.animation.ParticleUvRegion
import work.nekow.particledrawing.animation.UvData

/** 把本帧的时钟与已加载贴图交给核心选帧。 */
internal fun ParticleUvRegion.updateFromTexture(
    entry: TextureCache.Entry,
    uv: UvData?,
    animationStartNanos: Long,
    clock: RenderParticle?,
    age: Int,
    partialTick: Float,
) {
    val elapsed = if (uv?.mode == UvData.Mode.ANIMATED) {
        (System.nanoTime() - animationStartNanos) / 1_000_000_000.0
    } else 0.0
    val ageTicks = if (entry.animation != null) {
        clock?.ageTicks(partialTick)?.toDouble() ?: (age + partialTick).toDouble()
    } else 0.0
    update(uv, entry.width, entry.height, elapsed, entry.animation, ageTicks, clock?.lifetimeTicks() ?: -1)
}
