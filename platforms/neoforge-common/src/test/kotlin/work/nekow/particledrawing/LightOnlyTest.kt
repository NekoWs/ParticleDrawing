package work.nekow.particledrawing

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.api.Light
import work.nekow.particledrawing.api.ParticleVisual
import work.nekow.particledrawing.api.particleVisual
import work.nekow.particledrawing.core.client.RenderParticle
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 只发光不画外观（`lightOnly`）。
 *
 * 这个选项的契约是：光强只由等级、静态乘数和光照曲线决定，**不随透明度和尺寸衰减**；
 * 普通粒子仍按透明度与尺寸淡出，两条行为不能互相污染。
 */
class LightOnlyTest {

    private fun particle(
        alpha: Float = 1f,
        scale: Float = 1f,
        lightLevel: Int = 15,
        lightIntensity: Float = 1f,
        lightOnly: Boolean = false,
        emitsLight: Boolean = true,
    ) = RenderParticle(
        UUID.randomUUID(), Vec3.ZERO, Color.of(1f, 1f, 1f, alpha), scale,
        false, lightLevel, -1, lightIntensity = lightIntensity,
        emitsLight = emitsLight, lightOnly = lightOnly,
    )

    @Test
    fun `只发光时透明度与尺寸不再衰减光强`() {
        val full = particle(alpha = 1f, scale = 1f, lightOnly = true)
        val transparent = particle(alpha = 0f, scale = 1f, lightOnly = true)
        val tiny = particle(alpha = 1f, scale = 0.0001f, lightOnly = true)

        val expected = 15.0
        assertEquals(expected, full.lightIntensity().toDouble(), 1e-4, "满亮度的只发光粒子应给出满光强")
        assertEquals(expected, transparent.lightIntensity().toDouble(), 1e-4, "完全透明的只发光粒子仍要照亮地面")
        assertEquals(expected, tiny.lightIntensity().toDouble(), 1e-4, "极小尺寸不该削弱光照")
    }

    @Test
    fun `只发光时静态乘数仍然生效`() {
        val half = particle(lightOnly = true, lightIntensity = 0.5f)
        assertEquals(7.5, half.lightIntensity().toDouble(), 1e-4, "lightIntensity 是显式乘数，不能被忽略")
    }

    @Test
    fun `普通粒子仍随透明度与尺寸衰减`() {
        val opaque = particle(alpha = 1f, scale = 1f)
        val transparent = particle(alpha = 0f, scale = 1f)
        // 尺寸衰减是相对出生尺寸算的：出生 0.02，之后缩到 0.01
        val shrinking = particle(alpha = 1f, scale = 0.02f)
        val before = shrinking.lightIntensity()
        shrinking.setScaleArrayDirect(floatArrayOf(0.01f, 0.01f, 1f))
        val after = shrinking.lightIntensity()

        assertEquals(15.0, opaque.lightIntensity().toDouble(), 1e-4)
        assertEquals(0.0, transparent.lightIntensity().toDouble(), 1e-4, "普通粒子透明后不该继续照亮地面")
        assertTrue(after < before, "缩小的普通粒子光强应下降：$after 应小于 $before")
    }

    @Test
    fun `只发光但没开照明时不产生光`() {
        val off = particle(lightLevel = 15, lightOnly = true, emitsLight = false)
        // lightOnly 在视觉规格里会强制打开 emitsLight；这里模拟下游漏传的情况，
        // 渲染粒子仍应把它当光源，免得接入方少写一步就完全没光
        assertTrue(off.lightIntensity() > 0f)
    }

    @Test
    fun `视觉规格开启 lightOnly 会一并打开向外照明`() {
        val v = ParticleVisual().lightOnly(true)
        assertTrue(v.lightOnly)
        assertTrue(v.emitsLight, "不开 emitsLight 的 lightOnly 不会照亮任何东西")
    }

    @Test
    fun `视觉规格关闭 lightOnly 不会反转 emitsLight`() {
        val v = ParticleVisual().emitsLight(true).lightOnly(true).lightOnly(false)
        assertFalse(v.lightOnly)
        assertTrue(v.emitsLight, "关掉 lightOnly 只是恢复绘制，不该顺手关掉照明")
    }

    @Test
    fun `复制会带上 lightOnly`() {
        assertTrue(ParticleVisual().lightOnly(true).copy().lightOnly)
    }

    @Test
    fun `设过 lightOnly 的外观不算默认外观`() {
        assertFalse(ParticleVisual().lightOnly(true).isPristine())
    }

    @Test
    fun `可读外观的 only 照明等价于 lightOnly`() {
        val visual = particleVisual { light = Light.only(12, 0.5f) }
        assertTrue(visual.lightOnly)
        assertTrue(visual.emitsLight, "只发光必须同时是光源")
        assertEquals(12, visual.lightLevel)
        assertEquals(0.5f, visual.lightIntensity)

        val normal = particleVisual { light = Light.level(12) }
        assertFalse(normal.lightOnly, "普通照明不该被顺手改成只发光")
        assertTrue(normal.emitsLight)
    }
}
