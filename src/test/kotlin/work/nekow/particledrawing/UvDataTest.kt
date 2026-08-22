package work.nekow.particledrawing

import work.nekow.particledrawing.animation.UvData
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * UV 帧数推算逻辑的纯 JVM 回归测试（与编辑器 autoFramesFor/effMaxFrame 语义对齐）。
 */
class UvDataTest {

    private fun uv(
        uvStart: IntArray = intArrayOf(0, 0),
        uvStep: IntArray = intArrayOf(0, 0),
        maxFrame: Int = 1,
        fps: Float = 1f,
        mode: UvData.Mode = UvData.Mode.ANIMATED,
    ) = UvData(
        texture = "t", mode = mode,
        texSize = intArrayOf(16, 16),
        uvStart = uvStart, uvSize = intArrayOf(16, 16),
        uvStep = uvStep, fps = fps, maxFrame = maxFrame, loop = true,
    )

    @Test
    fun autoFramesSingleDirection() {
        // 16x16，step x=8 → 2 格；step y=0 → 1 格；共 2 帧
        assertEquals(2, uv(uvStep = intArrayOf(8, 0)).autoFrames(16, 16))
        // 16x16，step x=16 → 起点 0 只能放 1 格
        assertEquals(1, uv(uvStep = intArrayOf(16, 0)).autoFrames(16, 16))
    }

    @Test
    fun autoFramesTwoDirection() {
        // 16x16，step (8,8) → 2x2 = 4 帧
        assertEquals(4, uv(uvStep = intArrayOf(8, 8)).autoFrames(16, 16))
    }

    @Test
    fun autoFramesWithOffsetStart() {
        // 16x16，start (4,0) step x=8 → floor((15-4)/8)+1 = 2 格
        assertEquals(2, uv(uvStart = intArrayOf(4, 0), uvStep = intArrayOf(8, 0)).autoFrames(16, 16))
    }

    @Test
    fun effectiveMaxFrameAuto() {
        // maxFrame<=1 视为「自动」：min(自动, 上限) 用自动值
        val auto = 4
        assertEquals(4, uv(uvStep = intArrayOf(8, 8)).effectiveMaxFrame(auto))
    }

    @Test
    fun effectiveMaxFrameCap() {
        // maxFrame>1 作为上限：min(2, 4) = 2
        val u = uv(uvStep = intArrayOf(8, 8), maxFrame = 2)
        assertEquals(2, u.effectiveMaxFrame(4))
    }

    @Test
    fun effectiveMaxFrameCapOverAuto() {
        // 上限超过自动帧数时取自动
        val u = uv(uvStep = intArrayOf(8, 8), maxFrame = 99)
        assertEquals(4, u.effectiveMaxFrame(4))
    }

    @Test
    fun effectiveMaxFrameFloorAtLeastOne() {
        // 即使自动=0 兜底 ≥1
        val u = uv(maxFrame = 1)
        assertEquals(1, u.effectiveMaxFrame(0))
    }
}
