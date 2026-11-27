package work.nekow.particledrawing.animation.morph

import work.nekow.particledrawing.i18n.LocalizedArgumentException
import work.nekow.particledrawing.i18n.translation

/**
 * 可以跨网络传输的形变配置：只含原始类型与枚举序号，避免把函数式缓动塞进包里。
 *
 * 槽位公式单独用 [MorphSlotFormulas] 表达；两边合起来就能还原出完整的
 * [MorphOptions] 与槽位来源。
 */
data class MorphSpec(
    val ticks: Int = 40,
    val staggerTicks: Int = 0,
    val maxSpeed: Double = 0.0,
    val maxAccel: Double = 0.0,
    val path: Int = 0,
    val easing: String = "linear",
    val wobble: Int = 0,
    val wobbleStrength: Double = 0.0,
    val wobblePeaks: Double = 1.0,
    val convergeEps: Double = 0.05,
    val loop: Int = 0,
    val allocation: Int = 0,
    val keepVelocity: Boolean = false,
    val writeAttributes: Boolean = false,
    /** true = 槽位每 tick 用公式重算（动态目标），false = 开始时算一次。 */
    val dynamicSlots: Boolean = false,
    /** [MorphSpace] 序号；缺省世界坐标。 */
    val space: Int = 0,
) {
    fun toOptions(): MorphOptions = MorphOptions(
        ticks = ticks,
        staggerTicks = staggerTicks,
        maxSpeed = maxSpeed,
        maxAccel = maxAccel,
        path = MorphPath.entries.getOrNull(path) ?: MorphPath.STRAIGHT,
        easing = MorphEasing.byName(easing) ?: MorphEasing.LINEAR,
        wobble = MorphWobble.entries.getOrNull(wobble) ?: MorphWobble.NONE,
        wobbleStrength = wobbleStrength,
        wobblePeaks = wobblePeaks,
        convergeEps = convergeEps,
        loop = loop,
        allocation = MorphAllocation.entries.getOrNull(allocation) ?: MorphAllocation.NEAREST,
        keepVelocity = keepVelocity,
        writeAttributes = writeAttributes,
        space = MorphSpace.entries.getOrNull(space) ?: MorphSpace.WORLD,
    )

    companion object {
        fun of(options: MorphOptions, dynamicSlots: Boolean): MorphSpec = MorphSpec(
            ticks = options.ticks,
            staggerTicks = options.staggerTicks,
            maxSpeed = options.maxSpeed,
            maxAccel = options.maxAccel,
            path = options.path.ordinal,
            easing = options.easing.nameOf(),
            wobble = options.wobble.ordinal,
            wobbleStrength = options.wobbleStrength,
            wobblePeaks = options.wobblePeaks,
            convergeEps = options.convergeEps,
            loop = options.loop,
            allocation = options.allocation.ordinal,
            keepVelocity = options.keepVelocity,
            writeAttributes = options.writeAttributes,
            dynamicSlots = dynamicSlots,
            space = options.space.ordinal,
        )
    }
}

/** 预设缓动到网络名称的映射。 */
private fun MorphEasing.nameOf(): String = when (this) {
    MorphEasing.LINEAR -> "linear"
    MorphEasing.IN_QUAD -> "in_quad"
    MorphEasing.OUT_QUAD -> "out_quad"
    MorphEasing.IN_OUT_QUAD -> "in_out_quad"
    MorphEasing.OUT_CUBIC -> "out_cubic"
    MorphEasing.IN_OUT_CUBIC -> "in_out_cubic"
    MorphEasing.OUT_BACK -> "out_back"
    else -> throw LocalizedArgumentException(translation("error.morph.easing_preset"))
}
