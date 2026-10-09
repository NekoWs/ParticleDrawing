package work.nekow.particledrawing.api

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.core.easing.EasingType
import java.util.UUID

/**
 * 已声明发射器的句柄：运行期改动都通过它下发，方法名与 [ParticleEmitter] 一致。
 *
 * 自己持一份参数快照，与构建器互不影响：`spawn()` 之后再改构建器不会动到句柄。
 *
 * 变更按三档下发：
 * - [updateAnchor]：锚点（投射物每 tick 挪动走这条）；
 * - [spacing] / [interval] / [intervalMs]：发射口径；
 * - [life] / [scale] / [color] / 曲线 / 外观 / [jitter] 等：整份静态参数。
 *
 * 已生成的粒子不受影响，带着生成那一刻的寿命与曲线走完自己的命。
 * 后进服、切维度、走进范围的玩家会自动收到当前声明与参数。
 */
class EmitterHandle internal constructor(
    /** 发射器 id（服务端与客户端一致）。 */
    val id: UUID,
    private val manager: ParticleManager,
    emitter: ParticleEmitter,
) {

    /** 句柄自己的参数快照；构建器之后的改动不外泄到这里。 */
    private val state: ParticleEmitter = emitter.snapshot()

    private var currentAnchor: Anchor = Anchor.Fixed(Vec3.ZERO)

    internal fun init(anchor: Anchor): EmitterHandle {
        currentAnchor = anchor
        return this
    }

    /** 当前锚点。 */
    fun anchor(): Anchor = currentAnchor

    // 锚点

    /**
     * 挪动锚点，投射物每 tick 更新一次即可；变更时才发包。
     * 客户端按相邻两个样本插值推进里程，位置更新频率与渲染帧率无关。
     */
    fun updateAnchor(anchor: Anchor): EmitterHandle {
        currentAnchor = anchor
        manager.updateEmitterAnchor(id, anchor)
        return this
    }

    /** [updateAnchor] 的分量重载，固定点锚点。 */
    fun updateAnchor(x: Number, y: Number, z: Number): EmitterHandle =
        updateAnchor(Anchor.Fixed(Vec3(x.toDouble(), y.toDouble(), z.toDouble())))

    // 发射口径

    /** 改成按里程发射，每 [blocks] 格一颗。 */
    fun spacing(blocks: Double): EmitterHandle {
        state.spacing(blocks)
        pushCadence()
        return this
    }

    /** 改成按时间发射，每 [ticks] tick 一颗。 */
    fun interval(ticks: Int): EmitterHandle {
        state.interval(ticks)
        pushCadence()
        return this
    }

    /** 改成按时间发射，每 [ms] 毫秒一颗。 */
    fun intervalMs(ms: Int): EmitterHandle {
        state.intervalMs(ms)
        pushCadence()
        return this
    }

    // 逐粒子静态参数；改一次发一个包，只影响之后生成的粒子

    /** 每颗粒子的寿命（tick）；必须为正。 */
    fun life(ticks: Int): EmitterHandle = params { it.life(ticks) }

    /** 每颗粒子的缩放。 */
    fun scale(scale: Float): EmitterHandle = params { it.scale(scale) }

    /** 每颗粒子的颜色。 */
    fun color(color: Color): EmitterHandle = params { it.color(color) }

    /** 每颗粒子的颜色（整数分量）。 */
    fun color(r: Int, g: Int, b: Int, a: Int = 255): EmitterHandle = params { it.color(r, g, b, a) }

    /** 每颗粒子出生时的速度（blocks/tick）。 */
    fun velocity(velocity: Vec3): EmitterHandle = params { it.velocity(velocity) }

    /** 免光照开关。 */
    fun glowing(glowing: Boolean): EmitterHandle = params { it.glowing(glowing) }

    /** 发光等级 (0-15)。 */
    fun lightLevel(level: Int): EmitterHandle = params { it.lightLevel(level) }

    /** 同时存活的粒子上限。 */
    fun maxAlive(count: Int): EmitterHandle = params { it.maxAlive(count) }

    /** 逐颗位置抖动半径（格，垂直运动方向）；0 = 不抖。 */
    fun jitter(blocks: Double): EmitterHandle = params { it.jitter(blocks) }

    /** 沿运动方向的偏移（格，正 = 往前）。 */
    fun offsetAlong(blocks: Double): EmitterHandle = params { it.offsetAlong(blocks) }

    // 曲线，与 [ParticleEmitter] 同名同义

    /** 给某个通道的关键帧。 */
    fun curve(channel: CurveChannel, keys: List<CurveKey>): EmitterHandle = params { it.curve(channel, keys) }

    /** 直接给一条曲线，叠加到现有曲线上。 */
    fun curve(curve: ParticleCurve): EmitterHandle = params { it.curve(curve) }

    /** 直接给整套寿命曲线（覆盖之前设的）。 */
    fun lifeCurve(curve: ParticleLifeCurve): EmitterHandle = params { it.lifeCurve(curve) }

    /** 透明度曲线（乘数）。 */
    fun alphaCurve(vararg keys: CurveKey): EmitterHandle = params { it.alphaCurve(*keys) }

    /** 尺寸曲线（乘数）。 */
    fun sizeCurve(vararg keys: CurveKey): EmitterHandle = params { it.sizeCurve(*keys) }

    /** RGB 三条颜色曲线。 */
    fun colorCurve(red: List<CurveKey>, green: List<CurveKey>, blue: List<CurveKey>): EmitterHandle =
        params { it.colorCurve(red, green, blue) }

    /** 寿命最后 [ticks] tick 内淡出到透明（需要有限寿命）。 */
    fun fadeOut(ticks: Int, easing: EasingType = EasingType.EASE_IN): EmitterHandle =
        params { it.fadeOut(ticks, easing) }

    /** 寿命最后 [ticks] tick 内缩到 [factor] 倍（需要有限寿命）。 */
    fun shrinkTo(factor: Float, ticks: Int, easing: EasingType = EasingType.EASE_IN): EmitterHandle =
        params { it.shrinkTo(factor, ticks, easing) }

    // 外观；只影响之后生成的粒子

    /** 直接给一整份外观规格。 */
    fun visual(visual: ParticleVisual): EmitterHandle = params { it.visual(visual) }

    /** 用整张贴图。 */
    fun texture(name: String): EmitterHandle = params { it.texture(name) }

    /** 用内置形状。 */
    fun style(style: ParticleStyle): EmitterHandle = params { it.style(style) }

    /** 子矩形 UV（贴图像素）。 */
    fun uv(u0: Float, v0: Float, u1: Float, v1: Float): EmitterHandle = params { it.uv(u0, v0, u1, v1) }

    /** 加法混合开关。 */
    fun additive(enabled: Boolean): EmitterHandle = params { it.additive(enabled) }

    // 生命周期

    /** 停止发射；已生成的粒子各自走完寿命。 */
    fun stop() {
        manager.stopEmitter(id)
    }

    /** 这个发射器是否还在服务端登记着。 */
    fun isActive(): Boolean = manager.isEmitterActive(id)

    private fun pushCadence() {
        val c = state.cadence()
        manager.updateEmitterCadence(id, c.mode, c.spacing, c.intervalMs)
    }

    /** 改一处静态参数后整份下发；客户端整份替换，不做部分叠加。 */
    private inline fun params(change: (ParticleEmitter) -> Unit): EmitterHandle {
        change(state)
        manager.updateEmitterParams(id, state.toParams())
        return this
    }
}
