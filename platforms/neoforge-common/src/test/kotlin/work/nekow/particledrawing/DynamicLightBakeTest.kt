package work.nekow.particledrawing

import work.nekow.particledrawing.lighting.LightBakePolicy
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 地形烘焙脏判：小位移要在「已请求重建」的基准上累计，停住时补一次精确重建。
 *
 * 这是移动光源跨 section 时地形跟不跟得上的核心：如果基准每帧被覆盖，
 * 比阈值还小的位移永远累计不到，光场索引更新了而地形网格没更新，光斑就会跳。
 */
class DynamicLightBakeTest {

    private fun rebake(
        base: Double,
        last: Double,
        current: Double,
        baseLightQ: Int = 240,
        lightQ: Int = 240,
        shadowed: Boolean = false,
    ): Boolean = LightBakePolicy.shouldRebake(
        base, 0.0, 0.0, baseLightQ,
        last, 0.0, 0.0,
        current, 0.0, 0.0, lightQ,
        shadowed,
    )

    @Test
    fun `小于阈值的慢移不重建，但会在基准上累计`() {
        assertFalse(rebake(base = 0.0, last = 0.0, current = 0.03), "0.03 格还没到 0.06")
        assertFalse(rebake(base = 0.0, last = 0.03, current = 0.04), "基准仍是最初的位置")
        assertFalse(rebake(base = 0.0, last = 0.04, current = 0.06), "恰好 0.06 格还没超过阈值")
        assertTrue(rebake(base = 0.0, last = 0.06, current = 0.06), "停住时补一次精确重建")
    }

    @Test
    fun `累计超过阈值或跨过阈值就重建`() {
        assertTrue(rebake(base = 0.0, last = 0.05, current = 0.08), "0.08 格超过阈值")
        assertTrue(rebake(base = 0.0, last = 0.0, current = 0.07), "一次跳 0.07 格超过阈值")
        assertTrue(rebake(base = 0.0, last = 0.9, current = 1.0), "相对基准已经走很远")
    }

    @Test
    fun `移动阴影更早请求重建，普通光源维持原阈值`() {
        assertFalse(rebake(base = 0.0, last = 0.0, current = 0.02, shadowed = true), "0.02 格还没到阴影阈值")
        assertTrue(rebake(base = 0.0, last = 0.02, current = 0.04, shadowed = true), "0.04 格已超过阴影阈值")
        assertFalse(rebake(base = 0.0, last = 0.02, current = 0.04), "同样的位移普通光源还不重建")
    }

    @Test
    fun `亮度变了即使没动也要重建`() {
        assertTrue(rebake(base = 2.0, last = 2.0, current = 2.0, baseLightQ = 240, lightQ = 224))
        assertFalse(rebake(base = 2.0, last = 2.0, current = 2.0), "位置和亮度都没变")
    }

    @Test
    fun `负坐标与正负方向同样按距离累计`() {
        assertFalse(rebake(base = -80.0, last = -79.97, current = -79.96))
        assertFalse(rebake(base = -80.0, last = -79.96, current = -79.95))
        assertTrue(rebake(base = -80.0, last = -79.95, current = -79.95), "停住补精确重建")
        assertTrue(rebake(base = 80.0, last = 79.95, current = 79.9), "反向移动超过阈值")
    }
}
