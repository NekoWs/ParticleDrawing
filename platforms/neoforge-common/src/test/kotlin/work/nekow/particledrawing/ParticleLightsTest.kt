package work.nekow.particledrawing

import work.nekow.particledrawing.api.LightSample
import work.nekow.particledrawing.api.ParticleLights
import work.nekow.particledrawing.lighting.DynamicLightManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 动态光源只读快照：第三方按自己的预算拿去接光影包，不需要粒子引擎对象。
 *
 * 这里钉住的是快照的**数据契约**：坐标、强度、半径原样透出，容器可以复用。
 */
class ParticleLightsTest {

    @Test
    fun `光源快照透出坐标强度与半径`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val sources = arrayOf(
            DynamicLightManager.LightSource(a, 1.5, 64.0, -3.25, 12.0),
            DynamicLightManager.LightSource(b, -8.0, 30.5, 2.0, 4.0),
        )
        val out = ArrayList<LightSample>()
        DynamicLightManager.toSamples(sources, 16.0, out)

        assertEquals(2, out.size)
        assertEquals(a, out[0].id)
        assertEquals(1.5, out[0].x, 0.0)
        assertEquals(64.0, out[0].y, 0.0)
        assertEquals(-3.25, out[0].z, 0.0)
        assertEquals(12.0, out[0].intensity, 0.0)
        assertEquals(16.0, out[0].range, 0.0, "照射半径按帧统一给出")
        assertEquals(b, out[1].id)
        assertEquals(4.0, out[1].intensity, 0.0)
    }

    @Test
    fun `没有光源时快照为空且不保留上次内容`() {
        DynamicLightManager.clear()
        assertTrue(ParticleLights.snapshot().isEmpty())

        val out = ArrayList<LightSample>()
        out += LightSample(UUID.randomUUID(), 0.0, 0.0, 0.0, 9.0, 8.0)
        assertEquals(0, ParticleLights.snapshotInto(out))
        assertTrue(out.isEmpty(), "取快照要先清空调用方的容器")
    }

    @Test
    fun `快照项可按值比较字符串输出`() {
        val id = UUID.randomUUID()
        val sample = LightSample(id, 1.0, 2.0, 3.0, 15.0, 12.0)
        assertTrue(sample.toString().contains("intensity=15.0"))
        assertTrue(sample.toString().contains("range=12.0"))
    }
}
