package work.nekow.particledrawing.api

import work.nekow.particledrawing.core.easing.EasingType
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.translation

// 命名寿命曲线门面：关键帧仍是现有 ParticleCurve；easing 归属为“上一帧 → 目标帧”。

/** 单通道曲线构建器。 */
class CurveChannelBuilder internal constructor(private val channel: CurveChannel) {

    private val keys = ArrayList<CurveKey>()
    private var started = false

    /** 在生成时刻的起始值。每个通道必须且只能调用一次。 */
    fun start(value: Number): CurveChannelBuilder {
        requireLocalized(!started) { translation("error.curve.start_duplicate", channel.name) }
        val v = finiteNumber(value.toDouble(), translation("field.curve_value").resolve())
        keys.add(CurveKey.at(0, v))
        started = true
        return this
    }

    /** 到达 [at] 时的目标值；[easing] 作用于上一帧到这一帧。 */
    fun to(value: Number, at: Time, easing: Ease = Ease.LINEAR): CurveChannelBuilder {
        requireLocalized(started) { translation("error.curve.start_missing", channel.name) }
        val v = finiteNumber(value.toDouble(), translation("field.curve_value").resolve())
        val t = at.requireNonNegative(translation("field.curve_time").resolve()).ticks.toFloat()
        requireLocalized(t > lastTime() + 1e-6f) { translation("error.curve.key_order", channel.name, at.ticks) }
        keys.add(CurveKey.at(t, v, easing.toEasingType()))
        return this
    }

    /** 保持当前值到 [until]，再继续下一个 [to]。 */
    fun hold(until: Time): CurveChannelBuilder {
        requireLocalized(started) { translation("error.curve.start_missing", channel.name) }
        val t = until.requireNonNegative(translation("field.hold_time").resolve()).ticks.toFloat()
        requireLocalized(t > lastTime() + 1e-6f) { translation("error.curve.hold_order", channel.name, until.ticks) }
        keys.add(CurveKey.at(t, keys.last().value, EasingType.NONE))
        return this
    }

    internal fun build(): ParticleCurve = ParticleCurve(channel, keys)

    private fun lastTime(): Float = keys.last().tTicks
}

private fun finiteNumber(value: Double, what: String): Double {
    requireLocalized(value.isFinite()) { translation("error.parameter.finite", what) }
    return value
}

/** 整套寿命曲线构建器；多个通道或同通道多条曲线会按原有规则相乘。 */
class ParticleLifeCurveBuilder {

    private val curves = ArrayList<ParticleCurve>()

    fun alpha(block: CurveChannelBuilder.() -> Unit) = add(CurveChannel.ALPHA, block)
    fun scale(block: CurveChannelBuilder.() -> Unit) = add(CurveChannel.SCALE, block)
    fun red(block: CurveChannelBuilder.() -> Unit) = add(CurveChannel.RED, block)
    fun green(block: CurveChannelBuilder.() -> Unit) = add(CurveChannel.GREEN, block)
    fun blue(block: CurveChannelBuilder.() -> Unit) = add(CurveChannel.BLUE, block)
    fun light(block: CurveChannelBuilder.() -> Unit) = add(CurveChannel.LIGHT, block)

    fun channel(channel: CurveChannel, block: CurveChannelBuilder.() -> Unit) = add(channel, block)

    private fun add(channel: CurveChannel, block: CurveChannelBuilder.() -> Unit) {
        curves.add(CurveChannelBuilder(channel).apply(block).build())
    }

    fun build(): ParticleLifeCurve = ParticleLifeCurve(curves.toList())
}

fun lifeCurve(block: ParticleLifeCurveBuilder.() -> Unit): ParticleLifeCurve =
    ParticleLifeCurveBuilder().apply(block).build()

/**
 * 常用尺寸包络：出生从 0 收到 1，保持，最后 [leave] 收到 0。
 *
 * 默认拒绝 `enter + leave > lifetime` 的重叠；确实要压缩时长时请显式拆分或缩短段。
 */
@JvmOverloads
fun sizeEnvelope(
    lifetime: Time,
    enter: Time,
    leave: Time,
    enterEasing: Ease = Ease.OUT,
    leaveEasing: Ease = Ease.IN_OUT,
): ParticleLifeCurve {
    lifetime.requirePositive(translation("field.lifetime").resolve())
    enter.requireNonNegative(translation("field.enter_time").resolve())
    leave.requireNonNegative(translation("field.exit_time").resolve())
    val life = lifetime.ticks
    requireLocalized(enter.ticks + leave.ticks <= life + 1e-6) {
        translation("error.curve.envelope_length", enter.ticks, leave.ticks, life)
    }
    return lifeCurve {
        scale {
            if (enter.ticks <= 1e-6) start(1.0) else {
                start(0.0)
                to(1.0, enter, enterEasing)
            }
            val holdUntil = Time.ticks(life - leave.ticks)
            if (holdUntil.ticks > enter.ticks + 1e-6) hold(holdUntil)
            if (leave.ticks > 1e-6) to(0.0, lifetime, leaveEasing)
        }
    }
}

/** 只在寿命最后 [leave] 内淡出到 0。 */
@JvmOverloads
fun fadeOut(lifetime: Time, leave: Time, easing: Ease = Ease.IN_OUT): ParticleLifeCurve {
    lifetime.requirePositive(translation("field.lifetime").resolve())
    leave.requirePositive(translation("field.exit_time").resolve())
    requireLocalized(leave.ticks <= lifetime.ticks + 1e-6) { translation("error.curve.fade_length", leave.ticks, lifetime.ticks) }
    return lifeCurve {
        alpha {
            start(1.0)
            val holdUntil = Time.ticks(lifetime.ticks - leave.ticks)
            if (holdUntil.ticks > 1e-6) hold(holdUntil)
            to(0.0, lifetime, easing)
        }
    }
}

/** Java 静态入口。 */
object LifeCurves {
    @JvmStatic
    @JvmOverloads
    fun sizeEnvelope(
        lifetime: Time,
        enter: Time,
        leave: Time,
        enterEasing: Ease = Ease.OUT,
        leaveEasing: Ease = Ease.IN_OUT,
    ): ParticleLifeCurve = sizeEnvelope(lifetime, enter, leave, enterEasing, leaveEasing)

    @JvmStatic
    @JvmOverloads
    fun fadeOut(lifetime: Time, leave: Time, easing: Ease = Ease.IN_OUT): ParticleLifeCurve =
        fadeOut(lifetime, leave, easing)

    @JvmStatic
    fun builder(): ParticleLifeCurveBuilder = ParticleLifeCurveBuilder()
}
