package work.nekow.particledrawing.api

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.core.easing.EasingType

/**
 * 一次批量生成里的一颗粒子。
 *
 * 纯数据：一次可攒一批交给 [ParticleManager.spawnAll] / [ParticleBatch.spawnAll] 用一个包发完，
 * 逐颗控制仍用 [ParticleHandle.Builder]。字段含义与 Builder 的对应方法一致。
 */
class ParticleSpawnSpec {

    var position: Vec3 = Vec3.ZERO
    var color: Color = Color.WHITE
    var scale: Float = 1f
    var lifetime: Int = -1
    var glowing: Boolean = false
    var lightLevel: Int = 15
    var visual: ParticleVisual? = null
    var lifeCurve: ParticleLifeCurve? = null

    /** 上一 tick 的位置（与 [position] 组成首帧插值段）；null = 直接在当前位置出生。 */
    var prev: Vec3? = null

    fun position(pos: Vec3): ParticleSpawnSpec = apply { this.position = pos }

    fun position(x: Number, y: Number, z: Number): ParticleSpawnSpec =
        apply { this.position = Vec3(x.toDouble(), y.toDouble(), z.toDouble()) }

    fun color(color: Color): ParticleSpawnSpec = apply { this.color = color }

    fun color(r: Int, g: Int, b: Int, a: Int = 255): ParticleSpawnSpec =
        apply { this.color = Color.ofInt(r, g, b, a) }

    /** 缩放（编辑器单位：渲染整宽 = 值 × 0.2 格 × 贴图尺寸系数）。 */
    fun scale(scale: Float): ParticleSpawnSpec = apply { this.scale = scale }

    /** 寿命（tick）；-1 = 永存。 */
    fun lifetime(ticks: Int): ParticleSpawnSpec = apply { this.lifetime = ticks }

    fun glowing(glowing: Boolean): ParticleSpawnSpec = apply { this.glowing = glowing }

    fun lightLevel(level: Int): ParticleSpawnSpec = apply { this.lightLevel = level.coerceIn(0, 15) }

    /** 外观规格（贴图 / UV / 各向异性 / 朝向 / 加色）；按拷贝存。 */
    fun visual(visual: ParticleVisual): ParticleSpawnSpec = apply { this.visual = visual.copy() }

    /** 直接给一条寿命曲线。 */
    fun curve(curve: ParticleCurve): ParticleSpawnSpec =
        apply { lifeCurve = (lifeCurve ?: ParticleLifeCurve.EMPTY).plus(curve) }

    /** 给某个通道的关键帧。 */
    fun curve(channel: CurveChannel, keys: List<CurveKey>): ParticleSpawnSpec =
        apply { lifeCurve = (lifeCurve ?: ParticleLifeCurve.EMPTY).plus(ParticleCurve(channel, keys)) }

    /** 直接给整套寿命曲线。 */
    fun lifeCurve(curve: ParticleLifeCurve): ParticleSpawnSpec = apply { this.lifeCurve = curve }

    /** 透明度曲线（乘数）。 */
    fun alphaCurve(vararg keys: CurveKey): ParticleSpawnSpec = curve(ParticleCurve.alpha(*keys))

    /** 尺寸曲线（乘数；>1 变大、<1 缩小）。 */
    fun sizeCurve(vararg keys: CurveKey): ParticleSpawnSpec = curve(ParticleCurve.scale(*keys))

    /** RGB 三条颜色曲线（乘数，逐通道给关键帧）。 */
    fun colorCurve(red: List<CurveKey>, green: List<CurveKey>, blue: List<CurveKey>): ParticleSpawnSpec = apply {
        curve(ParticleCurve(CurveChannel.RED, red))
        curve(ParticleCurve(CurveChannel.GREEN, green))
        curve(ParticleCurve(CurveChannel.BLUE, blue))
    }

    /** 寿命最后 [ticks] tick 内 alpha 淡到 0（需要有限寿命）。 */
    fun fadeOut(ticks: Int, easing: EasingType = EasingType.EASE_IN): ParticleSpawnSpec = apply {
        fadeOutTicks = ticks
        fadeOutEasing = easing
    }

    /** 寿命最后 [ticks] tick 内缩到 [factor] 倍（需要有限寿命）。 */
    fun shrinkTo(factor: Float, ticks: Int, easing: EasingType = EasingType.EASE_IN): ParticleSpawnSpec = apply {
        shrinkTarget = factor
        shrinkTicks = ticks
        shrinkEasing = easing
    }

    /**
     * 首帧插值端点：第一帧从 [prev] 渲染到 [position]，与 `track` 的段同语义。
     */
    fun prevPosition(prev: Vec3): ParticleSpawnSpec = apply { this.prev = prev }

    internal var fadeOutTicks: Int = 0
    internal var fadeOutEasing: EasingType = EasingType.EASE_IN
    internal var shrinkTarget: Float = 1f
    internal var shrinkTicks: Int = 0
    internal var shrinkEasing: EasingType = EasingType.EASE_IN

    /** 展开 [fadeOut] / [shrinkTo] 后的最终曲线（需要寿命已知）。 */
    internal fun resolvedLifeCurve(): ParticleLifeCurve? {
        if (fadeOutTicks <= 0 && shrinkTicks <= 0) return lifeCurve
        var out = lifeCurve ?: ParticleLifeCurve.EMPTY
        if (fadeOutTicks > 0) out = out.plus(LifeCurveSugar.fadeOut(lifetime, fadeOutTicks, fadeOutEasing))
        if (shrinkTicks > 0) out = out.plus(LifeCurveSugar.shrinkTo(lifetime, shrinkTarget, shrinkTicks, shrinkEasing))
        return out
    }

    /** 复制一份（含曲线引用；曲线不可变，可共享）。 */
    fun copy(): ParticleSpawnSpec {
        val c = ParticleSpawnSpec()
        c.position = position
        c.color = color
        c.scale = scale
        c.lifetime = lifetime
        c.glowing = glowing
        c.lightLevel = lightLevel
        c.visual = visual?.copy()
        c.lifeCurve = lifeCurve
        c.prev = prev
        c.fadeOutTicks = fadeOutTicks
        c.fadeOutEasing = fadeOutEasing
        c.shrinkTarget = shrinkTarget
        c.shrinkTicks = shrinkTicks
        c.shrinkEasing = shrinkEasing
        return c
    }

    companion object {
        @JvmStatic
        fun at(x: Number, y: Number, z: Number): ParticleSpawnSpec =
            ParticleSpawnSpec().position(x, y, z)

        @JvmStatic
        fun at(position: Vec3): ParticleSpawnSpec = ParticleSpawnSpec().position(position)
    }
}
