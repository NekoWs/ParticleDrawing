package work.nekow.particledrawing

import work.nekow.particledrawing.api.VelocityStretch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 速度拉丝的方向数学：长轴是局部 X，旋转按 Rz·Ry·Rx 组合。 */
class VelocityStretchTest {

    private val out = DoubleArray(3)

    @Test
    fun `静止不做对齐`() {
        assertFalse(VelocityStretch.spinDegrees(0.0, 0.0, 0.0, out))
    }

    @Test
    fun `沿正 X 前进时不需要旋转`() {
        assertTrue(VelocityStretch.spinDegrees(0.5, 0.0, 0.0, out))
        assertEquals(0.0, out[0], 1e-9)
        assertEquals(0.0, out[1], 1e-9)
        assertEquals(0.0, out[2], 1e-9)
    }

    @Test
    fun `竖直向上时绕 Z 抬九十度`() {
        assertTrue(VelocityStretch.spinDegrees(0.0, 0.5, 0.0, out))
        assertEquals(90.0, out[2], 1e-9)
        assertEquals(0.0, out[1], 1e-9)
    }

    @Test
    fun `沿正 Z 前进时俯仰负九十度`() {
        assertTrue(VelocityStretch.spinDegrees(0.0, 0.0, 0.5, out))
        assertEquals(-90.0, out[1], 1e-9)
    }

    @Test
    fun `长度系数随速度增长并夹在上限`() {
        assertEquals(1.0, VelocityStretch.lengthFactor(0.0, 0.6), 1e-9)
        assertEquals(1.6, VelocityStretch.lengthFactor(1.0, 0.6), 1e-9)
        assertEquals(VelocityStretch.MAX_LENGTH_FACTOR, VelocityStretch.lengthFactor(1000.0, 0.6), 1e-9)
        assertEquals(1.0, VelocityStretch.lengthFactor(1.0, 0.0), 1e-9)
    }
}