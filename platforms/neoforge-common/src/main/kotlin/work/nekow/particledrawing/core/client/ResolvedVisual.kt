package work.nekow.particledrawing.core.client

import work.nekow.particledrawing.animation.UvData
import work.nekow.particledrawing.api.ParticleVisual
import work.nekow.particledrawing.light.LightShape

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
    val lightIntensity: Float,
    val emitsLight: Boolean,
    val lightOnly: Boolean,
    /** 光源形状（射灯锥角、半径、衰减）；null = 点光。 */
    val lightShape: LightShape? = null,
    /** 长轴是否跟随速度。 */
    val alignVelocity: Boolean = false,
    /** 拉丝强度；只在 [alignVelocity] 为真时使用。 */
    val stretch: Double = 0.0,
) {
    companion object {
        /** 无外观规格：纯白方块、广告牌、无自转、原始光强、不独立投光。 */
        val DEFAULT = ResolvedVisual(null, true, ClientParticleEngine.ZERO_SPIN, true, false, null, 1f, false, false)

        fun of(visual: ParticleVisual?, scale: Float): ResolvedVisual {
            if (visual == null) return DEFAULT
            val entry = visual.texture?.let { TextureCache.get(it) }
            val uv = visual.toUvData(entry?.width ?: 0, entry?.height ?: 0)
            // 速度对齐要逐刻改写这份数组，所以必须是每颗粒子独占的可写副本。
            val spin = if (!visual.billboard || visual.alignVelocity) {
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
                if (visual.alignVelocity) floatArrayOf(1f, 1f, 0f)
                else visual.resolvedAniso(scale, ParticleVisual.texScaleOf(uv, entry?.width ?: 0, entry?.height ?: 0)),
                visual.lightIntensity,
                visual.emitsLight,
                visual.lightOnly,
                visual.lightShape,
                visual.alignVelocity,
                visual.stretch,
            )
        }
    }
}
