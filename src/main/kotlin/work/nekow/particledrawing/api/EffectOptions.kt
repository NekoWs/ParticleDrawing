package work.nekow.particledrawing.api

/**
 * 特效播放选项（Java 流式友好：`new EffectOptions().scale(2f).speed(1.5)`）。
 */
class EffectOptions {
    private var scaleValue = 1f
    private var loopValue: Boolean? = null
    private var speedValue = 1.0
    private var startTickValue = 0.0
    private var authorityValue = Authority.CLIENT_LOCAL

    /** 整体缩放（编辑器 1 单位 = 1，缩放作用于位置与粒子尺寸基准；billboard 尺寸见渲染层换算）。 */
    fun scale(value: Float): EffectOptions = apply { scaleValue = value }

    /** 是否循环；null = 沿用动画自身 loop 字段。 */
    fun loop(value: Boolean): EffectOptions = apply { loopValue = value }

    /** 播放倍速（默认 1.0）。 */
    fun speed(value: Double): EffectOptions = apply { speedValue = value }

    /** 起始时间轴 tick（默认 0）。 */
    fun startTick(value: Double): EffectOptions = apply { startTickValue = value }

    /** 播放控制权：默认客户端本地；标记服务端权威时控制命令会广播。 */
    fun authority(value: Authority): EffectOptions = apply { authorityValue = value }

    fun scale(): Float = scaleValue
    fun loop(): Boolean? = loopValue
    fun speed(): Double = speedValue
    fun startTick(): Double = startTickValue
    fun authority(): Authority = authorityValue
}

/** 播放控制权。 */
enum class Authority {
    /** 客户端本地：各客户端各自推进时钟；seek/暂停/变速只在本地生效（纯视觉）。 */
    CLIENT_LOCAL,

    /** 服务端权威：控制命令经服务端广播，所有玩家进度严格一致。 */
    SERVER,
}