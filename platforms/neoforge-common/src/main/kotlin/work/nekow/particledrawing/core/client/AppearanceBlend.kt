package work.nekow.particledrawing.core.client

// 桥接粒子的外观插值端点：颜色/透明度与宽高各记「上一 tick → 本 tick」两端，渲染帧按 partialTick 取中间值。

/**
 * 外观插值的两端点账本（颜色 / 透明度 / 宽 / 高）。
 *
 * 出生状态只是占位：出生后第一次同步（表达式首帧、曲线首帧、即时改色改尺寸）要把两端原子写成新值，
 * 否则渲染帧会在出生状态与首帧状态之间插值。此后按相邻 tick 取端点，同一引擎 tick 内多次同步只捕获
 * 一次上一帧端点。
 *
 * 显式 [snap]（轴心瞬移、断流恢复、即时指令）任何时刻都强制两端同值。
 */
internal class AppearanceBlend(
    /** 引擎 tick 序号来源（默认取引擎的；测试注入递增计数器）。 */
    private val tickSource: () -> Long = { ClientParticleEngine.tickSequence() },
) {

    private var prevR = 1f
    private var prevG = 1f
    private var prevB = 1f
    private var prevA = 1f
    private var prevW = 0f
    private var prevH = 0f

    private var curR = 1f
    private var curG = 1f
    private var curB = 1f
    private var curA = 1f
    private var curW = 0f
    private var curH = 0f

    /** 上一次捕获上一帧端点时的引擎 tick（同一 tick 内只捕获一次）。 */
    private var capturedTick = Long.MIN_VALUE

    /** 颜色 / 尺寸两条通道是否已经历过出生后第一次同步，各自独立。 */
    private var colorSettled = false
    private var scaleSettled = false

    /** 出生 / 重建：两端都落在给定状态，下一次同步仍原子落地。 */
    fun initialize(r: Float, g: Float, b: Float, a: Float, w: Float, h: Float) {
        prevR = r; prevG = g; prevB = b; prevA = a; prevW = w; prevH = h
        curR = r; curG = g; curB = b; curA = a; curW = w; curH = h
        capturedTick = Long.MIN_VALUE
        colorSettled = false
        scaleSettled = false
    }

    fun setColor(r: Float, g: Float, b: Float, a: Float, snap: Boolean = false) {
        if (snap || !colorSettled) {
            prevR = r; prevG = g; prevB = b; prevA = a
            colorSettled = true
        } else {
            capturePrev()
        }
        curR = r; curG = g; curB = b; curA = a
    }

    fun setScale(w: Float, h: Float, snap: Boolean = false) {
        if (snap || !scaleSettled) {
            prevW = w; prevH = h
            scaleSettled = true
        } else {
            capturePrev()
        }
        curW = w; curH = h
    }

    fun redAt(f: Float): Float = lerp(prevR, curR, f)

    fun greenAt(f: Float): Float = lerp(prevG, curG, f)

    fun blueAt(f: Float): Float = lerp(prevB, curB, f)

    fun alphaAt(f: Float): Float = lerp(prevA, curA, f)

    fun widthAt(f: Float): Float = lerp(prevW, curW, f)

    fun heightAt(f: Float): Float = lerp(prevH, curH, f)

    /** 把当前值当作下一帧的上一帧端点（每 tick 至多一次）。 */
    private fun capturePrev() {
        val tick = tickSource()
        if (capturedTick == tick) return
        capturedTick = tick
        prevR = curR; prevG = curG; prevB = curB; prevA = curA
        prevW = curW; prevH = curH
    }

    private fun lerp(from: Float, to: Float, f: Float): Float = from + (to - from) * f
}
