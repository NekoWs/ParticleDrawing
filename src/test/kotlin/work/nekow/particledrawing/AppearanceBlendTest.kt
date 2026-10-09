package work.nekow.particledrawing

import work.nekow.particledrawing.core.client.AppearanceBlend
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 外观插值端点的落地语义（第 77 轮）：
 * - **出生状态只是占位**：出生后第一次真正同步把两端原子写成新值——出生透明 + 首帧零尺寸的组，
 *   任何子帧都不该有面积；首帧尺寸大于出生尺寸时也不该从出生状态「倒缩」过去；
 * - **此后按相邻 tick 插值**：同一 tick 内多次同步只捕获一次上一帧端点；
 * - **显式 snap** 任何时刻强制两端同值（轴心瞬移、断流恢复、即时指令）。
 */
class AppearanceBlendTest {

    /** 可手推的引擎 tick，模拟一 tick 一次同步 + 中间的渲染帧采样。 */
    private class Harness {
        var tick = 1L
        val blend = AppearanceBlend { tick }
        fun nextTick() { tick++ }
    }

    /** 渲染帧采样：partialTick 0 / 0.25 / 0.5 / 0.75 / 1。 */
    private val subFrames = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)

    private fun widthsAt(blend: AppearanceBlend): List<Float> = subFrames.map { blend.widthAt(it) }

    private fun alphasAt(blend: AppearanceBlend): List<Float> = subFrames.map { blend.alphaAt(it) }

    @Test
    fun `出生透明加首帧零尺寸：所有子帧都没有面积`() {
        val h = Harness()
        // 出生包：透明、完整基础尺寸（调用方不需要用 scale=0 出生，表达式 sc 是乘在 baseScale 上的）
        h.blend.initialize(1f, 1f, 1f, 0f, 5f, 5f)
        // 首个表达式帧：a=1、sc=0（宽高落到 0）
        h.blend.setColor(1f, 1f, 1f, 1f)
        h.blend.setScale(0f, 0f)

        assertEquals(listOf(0f, 0f, 0f, 0f, 0f), widthsAt(h.blend), "首帧零尺寸：任何子帧都不能有面积")
        assertEquals(listOf(1f, 1f, 1f, 1f, 1f), alphasAt(h.blend), "颜色是原子落地的，不该从出生透明度插值过来")
    }

    @Test
    fun `首帧尺寸大于零时两端都是首帧状态，不会从出生状态倒缩`() {
        val h = Harness()
        h.blend.initialize(1f, 1f, 1f, 0f, 5f, 5f)   // 出生：尺寸 5
        h.blend.setColor(1f, 1f, 1f, 1f)
        h.blend.setScale(2f, 2f)                     // 首帧：尺寸 2（比出生小）

        assertEquals(listOf(2f, 2f, 2f, 2f, 2f), widthsAt(h.blend), "两端都是首帧状态，不从 5 缩到 2")
    }

    @Test
    fun `出生后第一次同步之后，按相邻 tick 插值`() {
        val h = Harness()
        h.blend.initialize(1f, 1f, 1f, 1f, 1f, 1f)
        h.blend.setScale(0f, 0f)                     // 第一次真正同步：原子落地
        assertEquals(listOf(0f, 0f, 0f, 0f, 0f), widthsAt(h.blend))

        h.nextTick()
        h.blend.setScale(4f, 4f)                     // 之后的一次同步：两端是 0 → 4
        assertEquals(listOf(0f, 1f, 2f, 3f, 4f), widthsAt(h.blend), "渲染帧在相邻两 tick 之间插值")
    }

    @Test
    fun `同一 tick 内多次同步只捕获一次上一帧端点`() {
        val h = Harness()
        h.blend.initialize(1f, 1f, 1f, 1f, 0f, 0f)
        h.blend.setScale(0f, 0f)

        h.nextTick()
        h.blend.setScale(4f, 4f)
        h.blend.setScale(6f, 6f)                     // 同 tick 第二次（如曲线逐帧刷新）：不覆盖上一帧端点
        assertEquals(listOf(0f, 1.5f, 3f, 4.5f, 6f), widthsAt(h.blend), "起点仍是上一 tick 的 0")
    }

    @Test
    fun `显式 snap 任何时刻都两端同值`() {
        val h = Harness()
        h.blend.initialize(1f, 1f, 1f, 1f, 0f, 0f)
        h.blend.setScale(0f, 0f)
        h.nextTick()
        h.blend.setScale(8f, 8f)
        h.blend.setScale(3f, 3f, snap = true)        // 轴心瞬移/即时指令：直接落到 3
        assertEquals(listOf(3f, 3f, 3f, 3f, 3f), widthsAt(h.blend))
    }

    @Test
    fun `颜色与透明度各通道都按同一规则插值`() {
        val h = Harness()
        h.blend.initialize(0f, 0f, 0f, 0f, 1f, 1f)
        h.blend.setColor(1f, 1f, 1f, 1f)             // 第一次同步：原子落地
        assertEquals(listOf(1f, 1f, 1f, 1f, 1f), alphasAt(h.blend))

        h.nextTick()
        h.blend.setColor(0f, 0.5f, 0.25f, 0f)
        assertEquals(0.5f, h.blend.redAt(0.5f), 1e-6f)
        assertEquals(0.75f, h.blend.greenAt(0.5f), 1e-6f)
        assertEquals(0.625f, h.blend.blueAt(0.5f), 1e-6f)
        assertEquals(0.5f, h.blend.alphaAt(0.5f), 1e-6f)
    }

    @Test
    fun `初始化可以重建两端（重发粒子时不会插值出残影）`() {
        val h = Harness()
        h.blend.initialize(1f, 1f, 1f, 1f, 6f, 6f)
        h.blend.setScale(2f, 2f)
        h.blend.initialize(1f, 1f, 1f, 1f, 1f, 1f)   // 同一个 id 重新 spawn
        assertEquals(listOf(1f, 1f, 1f, 1f, 1f), widthsAt(h.blend))
    }
}
