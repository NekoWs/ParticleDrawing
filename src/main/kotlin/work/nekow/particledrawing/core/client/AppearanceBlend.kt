package work.nekow.particledrawing.core.client

// 桥接粒子的外观插值端点：颜色/透明度与宽高各记「上一 tick → 本 tick」两端，渲染帧按 partialTick 取中间值。

/**
 * 外观插值的两端点账本（颜色 / 透明度 / 宽 / 高）。
 *
 * 两条纪律：
 *
 * 1. **出生状态只是占位**：生成包给的透明度与尺寸是「还没被任何动画接管」的样子，
 *    出生后**第一次**真正同步（表达式首帧、曲线首帧、即时改色改尺寸…）必须把两端**原子**写成新值。
 *    否则渲染帧会在「出生状态」与「首帧状态」之间插值——出生透明 + 首帧零尺寸的组会先闪出半尺寸再缩回，
 *    首帧尺寸大于出生尺寸时则会从小往大「倒缩」一次。
 * 2. **此后按相邻 tick 插值**：同一个引擎 tick 内多次同步只捕获一次上一帧端点，
 *    所以组动画与变量缓动那种 20Hz 求值的外观在高刷下仍是连续的（不是台阶）。
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

    /** 颜色与尺寸各自是否已经历过「出生后第一次真正同步」（两条通道分别落地，见类注释）。 */
    private var colorSettled = false
    private var scaleSettled = false

    /** 出生/重建：两端都落在给定状态，并保持「下一次同步要原子落地」。 */
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

    /** 把当前值当作下一帧的「上一帧端点」（每 tick 一次）。 */
    private fun capturePrev() {
        val tick = tickSource()
        if (capturedTick == tick) return
        capturedTick = tick
        prevR = curR; prevG = curG; prevB = curB; prevA = curA
        prevW = curW; prevH = curH
    }

    private fun lerp(from: Float, to: Float, f: Float): Float = from + (to - from) * f
}
