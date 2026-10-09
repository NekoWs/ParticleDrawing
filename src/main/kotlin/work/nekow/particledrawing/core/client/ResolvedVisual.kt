package work.nekow.particledrawing.core.client

import work.nekow.particledrawing.animation.UvData
import work.nekow.particledrawing.api.ParticleVisual

// 外观规格的客户端解析：把协议侧的 ParticleVisual（贴图名 / 子矩形 / 各向异性 / 朝向 / 加色）
// 翻成渲染层要的形态。网络来的 spawn 包与客户端本地发射器共用这段解析。

/**
 * 解析后的外观：[uv] 只在贴图有名字时非空（贴图没到货时按名回落，尺寸系数等贴图到货后由
 * [BridgeParticle] 重解析），[scaleArray] 只在给了各向异性时非空。
 *
 * 各字段只读复用：同一份可发给多颗粒子，下游只读或就地拷贝。
 */
internal class ResolvedVisual(
    val uv: UvData?,
    val billboard: Boolean,
    val spin: DoubleArray,
    val spinLocal: Boolean,
    val additive: Boolean,
    val scaleArray: FloatArray?,
) {
    companion object {
        /** 无外观规格：纯白方块、广告牌、无自转。 */
        val DEFAULT = ResolvedVisual(null, true, ClientParticleEngine.ZERO_SPIN, true, false, null)

        fun of(visual: ParticleVisual?, scale: Float): ResolvedVisual {
            if (visual == null) return DEFAULT
            val entry = visual.texture?.let { TextureCache.get(it) }
            val uv = visual.toUvData(entry?.width ?: 0, entry?.height ?: 0)
            val spin = if (!visual.billboard) {
                doubleArrayOf(visual.spinXDeg, visual.spinYDeg, visual.spinZDeg)
            } else {
                ClientParticleEngine.ZERO_SPIN
            }
            return ResolvedVisual(
                uv,
                visual.billboard,
                spin,
                visual.spinLocal,
                visual.additive,
                visual.resolvedAniso(scale, ParticleVisual.texScaleOf(uv, entry?.width ?: 0, entry?.height ?: 0)),
            )
        }
    }
}
