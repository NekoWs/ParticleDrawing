package work.nekow.particledrawing.api

import work.nekow.particledrawing.core.easing.EasingType

/**
 * 寿命曲线通道。
 *
 * 各通道的取值一律是**乘数**（缺省 1.0），逐帧乘在粒子自身的颜色/缩放之上：
 * 想「随时间压暗」就给 0 → 1 的值，想「越飘越大」就给大于 1 的值。
 * 多条曲线落在同一通道时相乘（见 [ParticleLifeCurve]）。
 */
enum class CurveChannel {
    /** 透明度乘数。 */
    ALPHA,

    /** 尺寸乘数（各向同性；各向异性粒子的两个轴同乘）。 */
    SCALE,

    RED, GREEN, BLUE,
}

/**
 * 曲线关键帧。
 *
 * @param tTicks 从**生成那一刻**算起的 tick 数（1 tick = 50ms）；不必从 0 开始
 * @param value 该时刻的通道值（乘数）
 * @param easing 到**下一关键帧**的缓动（与变量关键帧、摄像机关键帧同一约定：段用后一帧的缓动）
 */
class CurveKey(
    @JvmField val tTicks: Float,
    @JvmField val value: Float,
    @JvmField val easing: EasingType = EasingType.LINEAR,
) {
    companion object {
        /** 线性关键帧。 */
        @JvmStatic
        fun at(tTicks: Number, value: Number): CurveKey =
            CurveKey(tTicks.toFloat(), value.toFloat(), EasingType.LINEAR)

        /** 带缓动的关键帧（缓动作用于「本帧 → 下一帧」这一段）。 */
        @JvmStatic
        fun at(tTicks: Number, value: Number, easing: EasingType): CurveKey =
            CurveKey(tTicks.toFloat(), value.toFloat(), easing)

        /** 阶跃关键帧：值保持到下一关键帧才跳变。 */
        @JvmStatic
        fun step(tTicks: Number, value: Number): CurveKey =
            CurveKey(tTicks.toFloat(), value.toFloat(), EasingType.NONE)
    }
}

/**
 * 一条寿命曲线：某个通道随时间（生成后 tick 数）的乘数。
 *
 * 与 [ParticleGroup] 的组级动画不同，它作用于**单颗粒子在其寿命内**的外观，
 * 生成时随 spawn 包下发一次即可，之后不需要任何逐 tick 带宽。
 * 关键帧按 [CurveKey.tTicks] 升序存放（构造时排序）；取值时刻落在首尾之外时取端点值。
 */
class ParticleCurve(
    @JvmField val channel: CurveChannel,
    keys: List<CurveKey>,
) {

    /** 关键帧，按时刻升序。 */
    @JvmField
    val keys: List<CurveKey> = keys.sortedBy { it.tTicks }

    init {
        require(this.keys.isNotEmpty()) { "寿命曲线至少需要一个关键帧" }
        require(this.keys.size <= MAX_KEYS) { "寿命曲线关键帧过多（${this.keys.size} > $MAX_KEYS）" }
        require(this.keys.first().tTicks >= 0f) { "关键帧时刻不能为负（生成前不存在）" }
    }

    /** [ticks]（生成后 tick 数）处的通道值；段内按后一关键帧的缓动插值。 */
    fun valueAt(ticks: Float): Float {
        val k = keys
        val first = k[0]
        if (ticks <= first.tTicks) return first.value
        val last = k[k.size - 1]
        if (ticks >= last.tTicks) return last.value
        for (i in 0 until k.size - 1) {
            val a = k[i]
            val b = k[i + 1]
            if (ticks >= a.tTicks && ticks <= b.tTicks) {
                val dur = b.tTicks - a.tTicks
                if (dur <= 0f) return b.value
                val f = (ticks - a.tTicks) / dur
                return a.value + (b.value - a.value) * b.easing.evaluate(f)
            }
        }
        return last.value
    }

    /** 复制一份。 */
    fun copy(): ParticleCurve = ParticleCurve(channel, keys.toList())

    companion object {
        /** 单条曲线的关键帧上限（协议与内存的边界，超出直接报错而不是截断）。 */
        const val MAX_KEYS = 64

        @JvmStatic
        fun of(channel: CurveChannel, vararg keys: CurveKey): ParticleCurve =
            ParticleCurve(channel, keys.toList())

        @JvmStatic
        fun alpha(vararg keys: CurveKey): ParticleCurve = ParticleCurve(CurveChannel.ALPHA, keys.toList())

        @JvmStatic
        fun scale(vararg keys: CurveKey): ParticleCurve = ParticleCurve(CurveChannel.SCALE, keys.toList())

        @JvmStatic
        fun red(vararg keys: CurveKey): ParticleCurve = ParticleCurve(CurveChannel.RED, keys.toList())

        @JvmStatic
        fun green(vararg keys: CurveKey): ParticleCurve = ParticleCurve(CurveChannel.GREEN, keys.toList())

        @JvmStatic
        fun blue(vararg keys: CurveKey): ParticleCurve = ParticleCurve(CurveChannel.BLUE, keys.toList())
    }
}

/**
 * 一颗粒子的整套寿命曲线（各通道的乘数，可叠加）。
 *
 * 用法：`manager.create().lifetime(20).fadeOut(10).shrinkTo(0.2f, 10).spawn()`，
 * 或直接给通道关键帧 `.curve(ParticleCurve.alpha(CurveKey.at(0, 1f), CurveKey.at(20, 0f, EasingType.EASE_IN)))`。
 *
 * 不可变；用 [plus] 叠加新曲线（不修改原对象）。
 */
class ParticleLifeCurve(curves: List<ParticleCurve>) {

    /** 全部曲线（同通道可有多条，取值时相乘）。 */
    @JvmField
    val curves: List<ParticleCurve> = curves.toList()

    init {
        require(this.curves.size <= MAX_CURVES) { "寿命曲线条数过多（${this.curves.size} > $MAX_CURVES）" }
    }

    fun isEmpty(): Boolean = curves.isEmpty()

    /** 是否含某个通道（逐帧取值的快路径判断）。 */
    fun has(channel: CurveChannel): Boolean {
        for (c in curves) if (c.channel == channel) return true
        return false
    }

    /** [ticks] 处某个通道的总乘数；该通道没有曲线时返回 1（即不改变原外观）。 */
    fun valueAt(channel: CurveChannel, ticks: Float): Float {
        var v = 1f
        for (c in curves) {
            if (c.channel == channel) v *= c.valueAt(ticks)
        }
        return v
    }

    /** 叠加一条曲线，返回新对象（原对象不变）。 */
    fun plus(curve: ParticleCurve): ParticleLifeCurve = ParticleLifeCurve(curves + curve)

    /** 叠加一整套曲线，返回新对象。 */
    fun plus(other: ParticleLifeCurve): ParticleLifeCurve = ParticleLifeCurve(curves + other.curves)

    companion object {
        /** 整套曲线的条数上限（协议边界）。 */
        const val MAX_CURVES = 8

        @JvmField
        val EMPTY = ParticleLifeCurve(emptyList())

        @JvmStatic
        fun of(vararg curves: ParticleCurve): ParticleLifeCurve = ParticleLifeCurve(curves.toList())
    }
}
