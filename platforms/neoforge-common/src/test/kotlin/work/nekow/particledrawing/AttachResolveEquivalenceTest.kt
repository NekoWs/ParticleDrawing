package work.nekow.particledrawing

import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.joml.Vector3f
import work.nekow.particledrawing.util.AttachMath
import kotlin.test.Test
import kotlin.test.assertEquals

class AttachResolveEquivalenceTest {

    @Test
    fun `两个入口都符合四元数朝向，并给出同一坐标`() {
        val yaws = floatArrayOf(0f, 37f, -90f, 180f, 271.5f, 359.9f)
        val pitches = floatArrayOf(0f, 22.5f, -60f, 90f, -90f, 5.2f)
        val offsets = listOf(
            Vec3(0.0, 0.0, 0.0),
            Vec3(1.0, 2.0, 3.0),
            Vec3(-4.5, 0.25, 7.0),
            Vec3(0.0, -1.5, 0.0),
            Vec3(16.0, 0.0, -8.0),
        )
        val origins = listOf(Vec3.ZERO, Vec3(10.0, 64.0, -3.0), Vec3(-128.5, 300.0, 4096.25))
        val out = DoubleArray(3)

        for (origin in origins) {
            for (yaw in yaws) {
                for (pitch in pitches) {
                    for (offset in offsets) {
                        for (local in booleanArrayOf(true, false)) {
                            val expected = quaternionReference(origin, yaw, pitch, offset, local)
                            val resolved = AttachMath.resolve(origin, yaw, pitch, offset, local)
                            AttachMath.resolveInto(
                                origin.x, origin.y, origin.z, yaw, pitch,
                                offset.x, offset.y, offset.z, local, out,
                            )
                            val where = "origin=$origin yaw=$yaw pitch=$pitch offset=$offset local=$local"
                            assertEquals(resolved, Vec3(out[0], out[1], out[2]))
                            assertEquals(expected.x, out[0], 1e-4, "X 不一致：$where")
                            assertEquals(expected.y, out[1], 1e-4, "Y 不一致：$where")
                            assertEquals(expected.z, out[2], 1e-4, "Z 不一致：$where")
                        }
                    }
                }
            }
        }
    }

    private fun quaternionReference(origin: Vec3, yaw: Float, pitch: Float, offset: Vec3, local: Boolean): Vec3 {
        if (!local) return origin.add(offset)
        val rotation = Quaternionf().rotationYXZ(
            -Math.toRadians(yaw.toDouble()).toFloat(), Math.toRadians(pitch.toDouble()).toFloat(), 0f,
        )
        val vector = Vector3f(offset.x.toFloat(), offset.y.toFloat(), offset.z.toFloat()).rotate(rotation)
        return origin.add(vector.x.toDouble(), vector.y.toDouble(), vector.z.toDouble())
    }

    @Test
    fun `未随朝向旋转时就是简单相加`() {
        val out = DoubleArray(3)
        AttachMath.resolveInto(10.0, 64.0, -3.0, 123f, -45f, 1.5, -2.0, 0.25, false, out)
        assertEquals(11.5, out[0], 0.0)
        assertEquals(62.0, out[1], 0.0)
        assertEquals(-2.75, out[2], 0.0)
    }
}
