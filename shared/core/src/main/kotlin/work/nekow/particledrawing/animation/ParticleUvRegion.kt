package work.nekow.particledrawing.animation

import work.nekow.particledrawing.core.client.ParticleSpriteAnimation

/** 一次渲染提交使用同一个帧矩形，四条边按整张贴图归一化。 */
internal class ParticleUvRegion {
    var u0 = 0f
        private set
    var u1 = 1f
        private set
    var v0 = 0f
        private set
    var v1 = 1f
        private set

    fun update(
        uv: UvData?,
        texWidth: Int,
        texHeight: Int,
        elapsedSeconds: Double,
        sprite: ParticleSpriteAnimation?,
        ageTicks: Double,
        lifetimeTicks: Int,
    ) {
        if (uv == null || uv.mode == UvData.Mode.FILL) {
            u0 = 0f; u1 = 1f; v0 = 0f; v1 = 1f
            return
        }

        var x = uv.uvStart[0]
        var y = uv.uvStart[1]
        if (uv.mode == UvData.Mode.ANIMATED) {
            val total = uv.effectiveMaxFrame(uv.autoFrames(texWidth, texHeight))
            if (total > 1) {
                val raw = (elapsedSeconds * uv.fps.coerceAtLeast(0.001f)).toLong()
                val frame = if (uv.loop) (raw % total).toInt() else minOf(raw, (total - 1).toLong()).toInt()
                if (frame > 0) {
                    val cols = uv.frameColumns(texWidth)
                    if (cols > 0) {
                        x += frame % cols * uv.uvStep[0]
                        y += frame / cols * uv.uvStep[1]
                    }
                }
            }
        }
        if (sprite != null) {
            val frame = sprite.frameAt(ageTicks, lifetimeTicks)
            x += frame % sprite.columns * sprite.frameWidth
            y += frame / sprite.columns * sprite.frameHeight
        }
        val width = uv.uvSize[0].takeIf { it > 0 } ?: sprite?.frameWidth?.takeIf { it > 0 } ?: texWidth
        val height = uv.uvSize[1].takeIf { it > 0 } ?: sprite?.frameHeight ?: texHeight
        // 平铺帧之间不留空隙。采样位置收进半个像素，线性过滤时不会读到相邻帧。
        val inset = if (sprite != null && sprite.frameWidth > 0) 0.5f else 0f
        u0 = ((x + inset) / texWidth).coerceIn(0f, 1f)
        u1 = ((x + width - inset) / texWidth).coerceIn(0f, 1f)
        v0 = ((y + inset) / texHeight).coerceIn(0f, 1f)
        v1 = ((y + height - inset) / texHeight).coerceIn(0f, 1f)
    }
}
