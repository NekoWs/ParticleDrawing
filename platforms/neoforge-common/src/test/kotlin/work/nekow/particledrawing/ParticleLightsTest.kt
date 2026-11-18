package work.nekow.particledrawing

import work.nekow.particledrawing.api.LightSample
import work.nekow.particledrawing.api.ParticleLights
import work.nekow.particledrawing.lighting.DynamicLightManager
import java.util.UUID
import work.nekow.particledrawing.light.LightShape
import work.nekow.particledrawing.platform.PlatformCompat
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
    fun `白光保留原材质颜色`() {
        val material = 0x92a9cdef.toInt()
        val weight = 9.45
        val white = weight * 255
        assertEquals(material, DynamicLightManager.tintTerrainColor(
            material, 3, 7.5, weight, white, white, white,
        ))
    }

    @Test
    fun `合并亮度保留小数精度和原有天空光`() {
        val packed = 0x00af0040
        assertEquals(0x00af0078, PlatformCompat.lightWithMinimumBlock(packed, 7.5))
        assertEquals(0x00af0044, PlatformCompat.lightWithMinimumBlock(packed, 4.25))
        assertEquals(packed, PlatformCompat.lightWithMinimumBlock(packed, 3.5))
        assertEquals(0x00af00f0, PlatformCompat.lightWithMinimumBlock(packed, 15.0))
    }

    @Test
    fun `光源快照透出坐标强度与半径`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val spot = LightShape.spot(12.0, 30.0)
        val sources = arrayOf(
            DynamicLightManager.LightSource(a, 1.5, 64.0, -3.25, 12.0, range = 12.0, shape = spot, rgb = 0xff8800),
            DynamicLightManager.LightSource(b, -8.0, 30.5, 2.0, 4.0),
        )
        val out = ArrayList<LightSample>()
        DynamicLightManager.toSamples(sources, out)

        assertEquals(2, out.size)
        assertEquals(a, out[0].id)
        assertEquals(1.5, out[0].x, 0.0)
        assertEquals(64.0, out[0].y, 0.0)
        assertEquals(-3.25, out[0].z, 0.0)
        assertEquals(12.0, out[0].intensity, 0.0)
        assertEquals(12.0, out[0].range)
        assertEquals(spot, out[0].shape)
        assertEquals(0xff8800, out[0].rgb)
        assertEquals(16.0, out[1].range)
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
    @Test
    fun `纯红光源保留红通道并压低绿蓝`() {
        val material = 0xffffffff.toInt()
        val block = 0
        val brightest = 12.0
        val weight = 12.0
        // 光源只有红色分量：红通道应保持原值，绿蓝按新增光占比被压暗
        val tinted = DynamicLightManager.tintTerrainColor(
            material, block, brightest, weight, weight * 255, 0.0, 0.0,
        )
        assertEquals(255, (tinted shr 16) and 255, "红通道不该被红光照暗")
        assertTrue(((tinted shr 8) and 255) < 255, "绿通道应当被压暗")
        assertTrue((tinted and 255) < 255, "蓝通道应当被压暗")
        assertEquals(255, (tinted ushr 24) and 255, "透明度必须保留")
    }

    @Test
    fun `彩色光源与原材质相乘而不是整体覆盖`() {
        // 半亮材质在橙光下：红分量高、蓝分量低，结果应体现材质自身的差异
        val dark = 0xff404040.toInt()
        val bright = 0xffc0c0c0.toInt()
        val weight = 10.0
        fun tint(color: Int) = DynamicLightManager.tintTerrainColor(
            color, 0, 10.0, weight, weight * 255, weight * 128, 0.0,
        )
        val tintedDark = tint(dark)
        val tintedBright = tint(bright)
        assertTrue((tintedBright shr 16) and 255 > (tintedDark shr 16) and 255, "亮材质的红分量仍应更高")
        assertTrue((tintedDark and 255) <= (dark and 255), "蓝通道不该被抬高")
    }

    @Test
    fun `新增光越多颜色偏移越大`() {
        val material = 0xffffffff.toInt()
        val weight = 10.0
        fun green(brightest: Double) =
            (DynamicLightManager.tintTerrainColor(material, 0, brightest, weight, weight * 255, 0.0, 0.0) shr 8) and 255
        val weak = green(2.0)
        val strong = green(9.0)
        assertTrue(strong <= weak, "光越强，非光源通道应被压得越低：weak=$weak strong=$strong")
    }

    @Test
    fun `没有新增光时不改顶点颜色`() {
        val material = 0xff123456.toInt()
        // brightest 不超过原方块光：tint 为 0，颜色原样返回
        assertEquals(material, DynamicLightManager.tintTerrainColor(material, 9, 9.0, 9.0, 9.0 * 255, 0.0, 0.0))
    }
}
