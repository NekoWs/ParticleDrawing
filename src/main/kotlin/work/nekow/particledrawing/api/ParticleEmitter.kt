package work.nekow.particledrawing.api

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.core.easing.EasingType
import work.nekow.particledrawing.core.network.EmitterParams
import work.nekow.particledrawing.core.network.EmitterUpdatePayload

/**
 * 发射口径：按锚点走过的里程发射，还是按时间发射。
 */
enum class EmitMode {
    /** 每走过 [spacing] 格发射一颗；拖尾用，密度与速度无关。 */
    DISTANCE,

    /** 每过 [intervalMs] 毫秒发射一颗，与移动速度无关。 */
    TIME,
}

/**
 * 运行时发射器：服务端声明一次，客户端按渲染帧沿锚点推进里程/时间并生成粒子（带宽 O(1)）。
 *
 * 游戏语义留在调用侧：「拖尾长度按格给、寿命 = 长度 ÷ 速度」这类换算先算好，
 * 再翻译成 [spacing] / [life] / [scale]，发射器只管机制。
 * 与 [ParticleGroup] 的分工：组先有一批粒子再整组变换，发射器按锚点运动持续产出粒子。
 */
class ParticleEmitter internal constructor(
    private val manager: ParticleManager,
    private var anchor: Anchor,
) {

    private var mode: EmitMode = EmitMode.DISTANCE
    private var spacingBlocks: Double = 0.25
    private var intervalMillis: Int = 50
    private var lifetimeTicks: Int = 10
    private var color: Color = Color.WHITE
    private var scale: Float = 1f
    private var glowing: Boolean = false
    private var lightLevel: Int = 15
    private var velocity: Vec3 = Vec3.ZERO
    private var maxAlive: Int = 4096
    private var jitterBlocks: Double = 0.0
    private var offsetAlongBlocks: Double = 0.0
    private var visualSpec: ParticleVisual? = null
    private var lifeCurve: ParticleLifeCurve? = null
    private var fadeOutTicks: Int = 0
    private var fadeOutEasing: EasingType = EasingType.EASE_IN
    private var shrinkTarget: Float = 1f
    private var shrinkTicks: Int = 0
    private var shrinkEasing: EasingType = EasingType.EASE_IN

    /** 发射器当前生效的锚点（[updateAnchor] 之后的读回值）。 */
    fun anchor(): Anchor = anchor

    /**
     * 按里程发射：锚点每走过 [blocks] 格发射一颗（默认口径）；
     * 投射物快慢不影响粒子密度，长度按格给。
     */
    fun spacing(blocks: Double): ParticleEmitter {
        require(blocks > 0.0) { "spacing 必须为正（每多少格一颗）" }
        mode = EmitMode.DISTANCE
        spacingBlocks = blocks
        return this
    }

    /** 按时间发射：每 [ticks] tick 一颗（1 tick = 50ms）。 */
    fun interval(ticks: Int): ParticleEmitter {
        require(ticks > 0) { "interval 必须为正（每多少 tick 一颗）" }
        mode = EmitMode.TIME
        intervalMillis = ticks * 50
        return this
    }

    /** 按时间发射：每 [ms] 毫秒一颗，可给到渲染帧粒度。 */
    fun intervalMs(ms: Int): ParticleEmitter {
        require(ms > 0) { "intervalMs 必须为正（每多少毫秒一颗）" }
        mode = EmitMode.TIME
        intervalMillis = ms
        return this
    }

    /** 每颗粒子的寿命（tick）；必须为正。 */
    fun life(ticks: Int): ParticleEmitter {
        require(ticks > 0) { "life 必须为正（每颗粒子活多少 tick）" }
        lifetimeTicks = ticks
        return this
    }

    /** 每颗粒子的颜色。 */
    fun color(color: Color): ParticleEmitter = apply { this.color = color }

    /** 每颗粒子的颜色（整数分量）。 */
    fun color(r: Int, g: Int, b: Int, a: Int = 255): ParticleEmitter = apply { this.color = Color.ofInt(r, g, b, a) }

    /** 每颗粒子的缩放（编辑器单位：渲染整宽 = 值 × 0.2 格 × 贴图尺寸系数）。 */
    fun scale(scale: Float): ParticleEmitter = apply { this.scale = scale }

    /** 每颗粒子出生时的速度（blocks/tick）；默认静止。 */
    fun velocity(velocity: Vec3): ParticleEmitter = apply { this.velocity = velocity }

    /** [velocity] 的分量重载。 */
    fun velocity(x: Number, y: Number, z: Number): ParticleEmitter =
        velocity(Vec3(x.toDouble(), y.toDouble(), z.toDouble()))

    /** 每颗粒子是否发光（免光照）。 */
    fun glowing(glowing: Boolean): ParticleEmitter = apply { this.glowing = glowing }

    /** 发光粒子向外发出的光照等级 (0-15)，仅当 [glowing] 为 true 时生效。 */
    fun lightLevel(level: Int): ParticleEmitter = apply { this.lightLevel = level.coerceIn(0, 15) }

    /** 外观规格（贴图 / UV / 各向异性 / 朝向 / 加色）；给的是拷贝，之后改原对象不影响。 */
    fun visual(visual: ParticleVisual): ParticleEmitter = apply { this.visualSpec = visual.copy() }

    // 外观的链式转发，语义与 ParticleHandle.Builder 一致；规格对象按需创建

    private fun spec(): ParticleVisual = visualSpec ?: ParticleVisual().also { visualSpec = it }

    /** 用整张贴图；名字先经 [ParticleManager.registerTexture] 或 [ParticleStyle] 登记。 */
    fun texture(name: String): ParticleEmitter = apply { spec().texture(name) }

    /** 用内置形状（[ParticleStyle.SQUARE] 等于清掉贴图）。 */
    fun style(style: ParticleStyle): ParticleEmitter = apply { spec().style(style) }

    /** 取贴图的子矩形 UV（贴图像素，顺序 u0, v0, u1, v1）。 */
    fun uv(u0: Float, v0: Float, u1: Float, v1: Float): ParticleEmitter = apply { spec().uv(u0, v0, u1, v1) }

    /** 各向异性尺寸（编辑器单位）：长轴 [w]、短轴 [h]。 */
    fun aniso(w: Float, h: Float): ParticleEmitter = apply { spec().aniso(w, h) }

    /** 各向异性尺寸（世界格整宽/整高）。 */
    fun anisoWorld(w: Float, h: Float): ParticleEmitter = apply { spec().anisoWorld(w, h) }

    /** 广告牌开关：false = 朝向固定（世界 +Z 起算），此时 [spin] / [alignTo] 才生效。 */
    fun billboard(enabled: Boolean): ParticleEmitter = apply { spec().billboard(enabled) }

    /** 绕四边形法线（局部 Z）转 [radians] 弧度。 */
    @JvmOverloads
    fun spin(radians: Double, spinLocal: Boolean = true): ParticleEmitter = apply { spec().spin(radians, spinLocal) }

    /** 三轴自转（弧度）。 */
    @JvmOverloads
    fun spin(radiansX: Double, radiansY: Double, radiansZ: Double, spinLocal: Boolean = true): ParticleEmitter =
        apply { spec().spin(radiansX, radiansY, radiansZ, spinLocal) }

    /** 把长轴对齐到 `to - from` 方向（丝沿线段躺好）。 */
    fun alignTo(from: Vec3, to: Vec3): ParticleEmitter = apply { spec().alignTo(from, to) }

    /** 加法混合开关。 */
    fun additive(enabled: Boolean): ParticleEmitter = apply { spec().additive(enabled) }

    /** 整条寿命曲线的乘数，同 spawn 的 [ParticleHandle.Builder.curve]。 */
    fun curve(channel: CurveChannel, keys: List<CurveKey>): ParticleEmitter =
        apply { lifeCurve = (lifeCurve ?: ParticleLifeCurve.EMPTY).plus(ParticleCurve(channel, keys)) }

    /** 直接给一条曲线。 */
    fun curve(curve: ParticleCurve): ParticleEmitter =
        apply { lifeCurve = (lifeCurve ?: ParticleLifeCurve.EMPTY).plus(curve) }

    /** 透明度曲线（乘数）。 */
    fun alphaCurve(vararg keys: CurveKey): ParticleEmitter = curve(ParticleCurve.alpha(*keys))

    /** 尺寸曲线（乘数；>1 变大、<1 缩小）。 */
    fun sizeCurve(vararg keys: CurveKey): ParticleEmitter = curve(ParticleCurve.scale(*keys))

    /** RGB 三条颜色曲线（乘数，逐通道给关键帧）。 */
    fun colorCurve(red: List<CurveKey>, green: List<CurveKey>, blue: List<CurveKey>): ParticleEmitter = apply {
        curve(ParticleCurve(CurveChannel.RED, red))
        curve(ParticleCurve(CurveChannel.GREEN, green))
        curve(ParticleCurve(CurveChannel.BLUE, blue))
    }

    /** 直接给整套寿命曲线。 */
    fun lifeCurve(curve: ParticleLifeCurve): ParticleEmitter = apply { lifeCurve = curve }

    /** 寿命最后 [ticks] tick 内 alpha 淡到 0。 */
    fun fadeOut(ticks: Int, easing: EasingType = EasingType.EASE_IN): ParticleEmitter = apply {
        require(ticks > 0) { "fadeOut 的时长必须为正" }
        fadeOutTicks = ticks
        fadeOutEasing = easing
    }

    /** 寿命最后 [ticks] tick 内尺寸缩到 [factor] 倍。 */
    fun shrinkTo(factor: Float, ticks: Int, easing: EasingType = EasingType.EASE_IN): ParticleEmitter = apply {
        require(ticks > 0) { "shrinkTo 的时长必须为正" }
        require(factor >= 0f) { "shrinkTo 的目标倍率不能为负" }
        shrinkTarget = factor
        shrinkTicks = ticks
        shrinkEasing = easing
    }

    /** 单个发射器同时存活的粒子上限；默认 4096。 */
    fun maxAlive(count: Int): ParticleEmitter = apply { this.maxAlive = count.coerceAtLeast(1) }

    /**
     * 逐颗的位置抖动：每颗粒子沿垂直运动方向的圆盘随机偏移，半径不超过 [blocks] 格；0 = 不抖（默认）。
     *
     * 偏移由「发射器 id + 第几颗」的哈希算出，同一声明在任何客户端上第 N 颗的偏移都相同。
     */
    fun jitter(blocks: Double): ParticleEmitter = apply {
        require(blocks >= 0.0) { "jitter 不能为负（格）" }
        this.jitterBlocks = blocks
    }

    /**
     * 沿运动方向的偏移（格）：所有粒子整体前移（正）/后移（负）。
     */
    fun offsetAlong(blocks: Double): ParticleEmitter = apply { this.offsetAlongBlocks = blocks }

    /** 下发声明并返回句柄；后续改动通过句柄下发，构建器上的改动不会发包。 */
    fun spawn(): EmitterHandle = manager.startEmitter(this, anchor, toParams())

    // 供管理器读取

    internal fun modeOf(): EmitMode = mode

    internal fun spacingOf(): Double = spacingBlocks

    internal fun intervalMsOf(): Int = intervalMillis

    internal fun lifetimeOf(): Int = lifetimeTicks

    internal fun colorOf(): Color = color

    internal fun scaleOf(): Float = scale

    internal fun visualOf(): ParticleVisual? = visualSpec

    internal fun velocityOf(): Vec3 = velocity

    internal fun glowingOf(): Boolean = glowing

    internal fun lightLevelOf(): Int = lightLevel

    internal fun maxAliveOf(): Int = maxAlive

    internal fun cadence(): EmitterUpdatePayload.EmitterCadence =
        EmitterUpdatePayload.EmitterCadence(mode, spacingBlocks, intervalMillis)

    /** 当前全部静态参数的快照（声明与运行期整份更新都用它）。 */
    internal fun toParams(): EmitterParams = EmitterParams(
        mode, spacingBlocks, intervalMillis, lifetimeTicks,
        color.r, color.g, color.b, color.a,
        scale, visualSpec, resolvedLifeCurve(), velocity,
        glowing, lightLevel, maxAlive, jitterBlocks, offsetAlongBlocks,
    )

    /** 解析出最终曲线：把 [fadeOut] / [shrinkTo] 的糖按当前寿命换算成关键帧。 */
    internal fun resolvedLifeCurve(): ParticleLifeCurve? {
        if (fadeOutTicks <= 0 && shrinkTicks <= 0) return lifeCurve
        var out = lifeCurve ?: ParticleLifeCurve.EMPTY
        if (fadeOutTicks > 0) {
            out = out.plus(LifeCurveSugar.fadeOut(lifetimeTicks, fadeOutTicks, fadeOutEasing))
        }
        if (shrinkTicks > 0) {
            out = out.plus(LifeCurveSugar.shrinkTo(lifetimeTicks, shrinkTarget, shrinkTicks, shrinkEasing))
        }
        return out
    }
}
