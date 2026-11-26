package work.nekow.particledrawing

import work.nekow.particledrawing.animation.ParticleUvRegion
import work.nekow.particledrawing.animation.UvData
import work.nekow.particledrawing.core.client.ParticleSpriteAnimation
import kotlin.test.Test
import kotlin.test.assertEquals

class ParticleUvRegionTest {
    @Test
    fun `帧矩形换行且各边取自同一帧`() {
        val reg = ParticleUvRegion()
        reg.update(uv(UvData.Mode.ANIMATED), 8, 4, 0.75, null, 0.0, -1)
        assertEquals(listOf(0.625f, 0.875f, 0.5f, 1f), listOf(reg.u0, reg.u1, reg.v0, reg.v1))
        reg.update(uv(UvData.Mode.ANIMATED), 8, 4, 3.0, null, 0.0, -1)
        assertEquals(listOf(0.875f, 1f, 0.5f, 1f), listOf(reg.u0, reg.u1, reg.v0, reg.v1))
        reg.update(uv(UvData.Mode.ANIMATED, loop = true), 8, 4, 1.0, null, 0.0, -1)
        assertEquals(listOf(0.125f, 0.375f, 0f, 0.5f), listOf(reg.u0, reg.u1, reg.v0, reg.v1))
    }

    @Test
    fun `纵向图集使用单帧高度，整图模式使用完整贴图`() {
        val reg = ParticleUvRegion()
        val sprite = ParticleSpriteAnimation(frameHeight = 8, frames = 8)
        val fullFrame = UvData("test", UvData.Mode.STATIC, intArrayOf(8, 64), IntArray(2), IntArray(2), IntArray(2), 0f, 0, false)
        reg.update(fullFrame, 8, 64, 0.0, sprite, 40.0, 80)
        assertEquals(listOf(0f, 1f, 0.375f, 0.5f), listOf(reg.u0, reg.u1, reg.v0, reg.v1))
        reg.update(uv(UvData.Mode.FILL), 8, 64, 0.0, sprite, 40.0, 80)
        assertEquals(listOf(0f, 1f, 0f, 1f), listOf(reg.u0, reg.u1, reg.v0, reg.v1))
    }

    @Test
    fun `平铺资源在行末换行并留出半像素边距`() {
        val reg = ParticleUvRegion()
        val sprite = ParticleSpriteAnimation(8, 6, 20.0, frameWidth = 10, columns = 3)
        val fullFrame = UvData("sheet:test:sample", UvData.Mode.STATIC, IntArray(2), IntArray(2),
            IntArray(2), IntArray(2), 0f, 0, false)
        reg.update(fullFrame, 30, 16, 0.0, sprite, 2.0, 6)
        assertEquals(listOf(20.5f / 30, 29.5f / 30, 0.5f / 16, 7.5f / 16),
            listOf(reg.u0, reg.u1, reg.v0, reg.v1))
        reg.update(fullFrame, 30, 16, 0.0, sprite, 3.0, 6)
        assertEquals(listOf(0.5f / 30, 9.5f / 30, 8.5f / 16, 15.5f / 16),
            listOf(reg.u0, reg.u1, reg.v0, reg.v1))
    }

    private fun uv(mode: UvData.Mode, loop: Boolean = false) =
        UvData("test", mode, intArrayOf(8, 4), intArrayOf(1, 0), intArrayOf(2, 2), intArrayOf(2, 2), 8f, 0, loop)
}
