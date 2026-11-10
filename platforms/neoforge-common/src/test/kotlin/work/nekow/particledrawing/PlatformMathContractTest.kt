package work.nekow.particledrawing

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.math.ResourceId
import work.nekow.particledrawing.platform.toCore
import work.nekow.particledrawing.platform.toMinecraft
import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformMathContractTest {
    @Test fun `向量边界转换与核心运算保持原版坐标结果`() {
        val vectors = listOf(Vec3.ZERO, Vec3(1e-8, 0.0, 0.0), Vec3(1e-4, 0.0, 0.0),
            Vec3(3.0, -4.0, 2.0), Vec3(-10000.25, 0.125, 64.0))
        for (a in vectors) {
            assertEquals(a, a.toCore().toMinecraft())
            assertEquals(a.normalize(), a.toCore().normalize().toMinecraft())
            for (b in vectors) {
                assertEquals(a.add(b), a.toCore().add(b.toCore()).toMinecraft())
                assertEquals(a.subtract(b), a.toCore().subtract(b.toCore()).toMinecraft())
                assertEquals(a.cross(b), a.toCore().cross(b.toCore()).toMinecraft())
                assertEquals(a.dot(b), a.toCore().dot(b.toCore()))
                assertEquals(a.lerp(b, 0.375), a.toCore().lerp(b.toCore(), 0.375).toMinecraft())
            }
        }
    }

    @Test fun `资源标识在平台入口保留命名空间与路径`() {
        val id = ResourceId.parse("demo:textures/particle/ring.png")
        val native = id.toMinecraft()
        assertEquals(id.namespace, native.namespace)
        assertEquals(id.path, native.path)
        assertEquals(id.toString(), native.toString())
    }
}
