package work.nekow.particledrawing

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.Anchor
import work.nekow.particledrawing.api.Orient
import work.nekow.particledrawing.core.client.EffectAnchorResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 锚点解析器：固定/可移动锚点的本地坐标 → 世界坐标映射，以及速度 → 朝向换算。
 */
class EffectAnchorResolverTest {

    private val pos = Vec3(10.0, 20.0, 30.0)
    private val scale = 2f

    @Test
    fun fixedAnchorScalesWithoutRotation() {
        val r = EffectAnchorResolver(Anchor.Fixed(pos), scale)
        val world = r.apply(Vec3(1.0, 2.0, 3.0))
        assertEquals(12.0, world.x, 1e-6)
        assertEquals(24.0, world.y, 1e-6)
        assertEquals(36.0, world.z, 1e-6)
    }

    @Test
    fun movableVelocitySouthKeepsForward() {
        // 速度朝 +Z（南）：look 向量 (0,0,1)，本地 +Z 映射为世界 +Z
        val r = EffectAnchorResolver(Anchor.Movable(pos, Vec3(0.0, 0.0, 1.0)), scale)
        val world = r.apply(Vec3(0.0, 0.0, 1.0))
        assertEquals(10.0, world.x, 1e-6)
        assertEquals(20.0, world.y, 1e-6)
        assertEquals(32.0, world.z, 1e-6)
    }

    @Test
    fun movableVelocityWestRotatesForwardToWest() {
        // 速度朝 -X（西）：yaw=90°，本地 +Z 映射为世界 -X
        val r = EffectAnchorResolver(Anchor.Movable(pos, Vec3(-1.0, 0.0, 0.0)), scale)
        val world = r.apply(Vec3(0.0, 0.0, 1.0))
        assertEquals(8.0, world.x, 1e-6)
        assertEquals(20.0, world.y, 1e-6)
        assertEquals(30.0, world.z, 1e-6)
    }

    @Test
    fun movableWorldOrientationIgnoresVelocity() {
        val r = EffectAnchorResolver(Anchor.Movable(pos, Vec3(-1.0, 0.0, 0.0), Orient.WORLD), scale)
        val world = r.apply(Vec3(0.0, 0.0, 1.0))
        assertEquals(10.0, world.x, 1e-6)
        assertEquals(20.0, world.y, 1e-6)
        assertEquals(32.0, world.z, 1e-6)
    }

    @Test
    fun yawPitchFromVelocity() {
        assertArrayEquals(doubleArrayOf(0.0, 0.0), EffectAnchorResolver.yawPitchDegrees(Vec3(0.0, 0.0, 1.0)))
        assertArrayEquals(doubleArrayOf(90.0, 0.0), EffectAnchorResolver.yawPitchDegrees(Vec3(-1.0, 0.0, 0.0)))
        assertNull(EffectAnchorResolver.yawPitchDegrees(Vec3.ZERO))
    }

    private fun assertArrayEquals(expected: DoubleArray, actual: DoubleArray?) {
        assertEquals(expected.size, actual?.size)
        for (i in expected.indices) assertEquals(expected[i], actual!![i], 1e-9)
    }
}