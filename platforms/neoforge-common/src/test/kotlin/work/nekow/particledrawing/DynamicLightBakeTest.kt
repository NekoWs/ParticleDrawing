package work.nekow.particledrawing

import work.nekow.particledrawing.lighting.LightBakePolicy
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 地形烘焙脏判：小位移要在「已请求重建」的基准上累计，停住时补一次精确重建。
 *
 * 这是移动光源慢速跨 section 时地形不刷新的核心：如果基准每帧被覆盖，
 * 0.1 格/t 的位移永远够不到 0.25 格阈值，光场索引更新了而地形网格没更新。
 */
class DynamicLightBakeTest {

    private fun rebake(
        base: Double,
        last: Double,
        current: Double,
        baseLightQ: Int = 240,
        lightQ: Int = 240,
    ): Boolean = LightBakePolicy.shouldRebake(
        base, 0.0, 0.0, baseLightQ,
        last, 0.0, 0.0,
        current, 0.0, 0.0, lightQ,
    )

    @Test
    fun `小于阈值的慢移不重建，但会在基准上累计`() {
        assertFalse(rebake(base = 0.0, last = 0.0, current = 0.1), "0.1 格还没到 0.25")
        assertFalse(rebake(base = 0.0, last = 0.1, current = 0.2), "基准仍是最初的位置")
        assertFalse(rebake(base = 0.0, last = 0.2, current = 0.25), "恰好 0.25 格还没超过阈值")
        assertTrue(rebake(base = 0.0, last = 0.25, current = 0.25), "停住时补一次精确重建")
    }

    @Test
    fun `累计超过阈值或跨过阈值就重建`() {
        assertTrue(rebake(base = 0.0, last = 0.2, current = 0.3), "0.3 格超过阈值")
        assertTrue(rebake(base = 0.0, last = 0.0, current = 0.26), "一次跳 0.26 格超过阈值")
        assertTrue(rebake(base = 0.0, last = 0.9, current = 1.0), "相对基准已经走很远")
    }

    @Test
    fun `亮度变了即使没动也要重建`() {
        assertTrue(rebake(base = 2.0, last = 2.0, current = 2.0, baseLightQ = 240, lightQ = 224))
        assertFalse(rebake(base = 2.0, last = 2.0, current = 2.0), "位置和亮度都没变")
    }

    @Test
    fun `负坐标与正负方向同样按距离累计`() {
        assertFalse(rebake(base = -80.0, last = -79.9, current = -79.8))
        assertFalse(rebake(base = -80.0, last = -79.8, current = -79.75))
        assertTrue(rebake(base = -80.0, last = -79.75, current = -79.75), "停住补精确重建")
        assertTrue(rebake(base = 80.0, last = 79.8, current = 79.7), "反向移动超过阈值")
    }
}
