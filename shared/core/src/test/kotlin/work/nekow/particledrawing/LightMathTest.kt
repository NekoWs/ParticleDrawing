package work.nekow.particledrawing

import work.nekow.particledrawing.light.LightEmission
import work.nekow.particledrawing.light.LightFalloff
import work.nekow.particledrawing.light.LightMath
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 光源数学的护栏：衰减曲线形状、锥体软硬边、朝向、颜色合成。
 *
 * 这些全部是纯函数，断言的是「物理上该成立的性质」，不依赖渲染。
 */
class LightMathTest {

    private fun point(
        luminance: Double = 10.0,
        range: Double = 8.0,
        falloff: LightFalloff = LightFalloff.LINEAR,
        r: Double = 1.0, g: Double = 1.0, b: Double = 1.0,
    ) = LightEmission(0.0, 0.0, 0.0, luminance, range, falloff = falloff, red = r, green = g, blue = b)

    private fun spot(
        innerDeg: Double,
        outerDeg: Double,
        luminance: Double = 10.0,
        range: Double = 8.0,
        edge: Double = 1.0,
        dirX: Double = 0.0, dirY: Double = -1.0, dirZ: Double = 0.0,
    ) = LightEmission(
        x = 0.0, y = 0.0, z = 0.0,
        luminance = luminance, range = range,
        dirX = dirX, dirY = dirY, dirZ = dirZ,
        innerCos = LightMath.cosOfDegrees(innerDeg),
        outerCos = LightMath.cosOfDegrees(outerDeg),
        edge = edge,
    )

    // ── 衰减曲线 ──────────────────────────────────────────────────────────────

    @Test
    fun `所有衰减曲线在中心是满亮、在半径处归零`() {
        for (curve in LightFalloff.entries) {
            assertEquals(1.0, LightMath.attenuation(0.0, 8.0, curve), 1e-9, "$curve 中心")
            assertEquals(0.0, LightMath.attenuation(8.0, 8.0, curve), 1e-9, "$curve 边界")
            assertEquals(0.0, LightMath.attenuation(12.0, 8.0, curve), 1e-9, "$curve 超界")
        }
    }

    @Test
    fun `衰减曲线单调不增`() {
        for (curve in LightFalloff.entries) {
            var last = 1.0
            for (i in 1..64) {
                val d = 8.0 * i / 64.0
                val a = LightMath.attenuation(d, 8.0, curve)
                assertTrue(a <= last + 1e-12, "$curve 在 d=$d 处反弹：$a > $last")
                last = a
            }
        }
    }

    @Test
    fun `不同衰减曲线在中段确实有区别`() {
        val mid = 4.0
        val values = LightFalloff.entries.map { LightMath.attenuation(mid, 8.0, it) }
        assertEquals(values.size, values.toSet().size, "各曲线在半径中点应当各不相同：$values")
    }

    @Test
    fun `半径为零时不发光`() {
        assertEquals(0.0, LightMath.attenuation(0.0, 0.0, LightFalloff.LINEAR))
    }

    // ── 锥体 ──────────────────────────────────────────────────────────────────

    @Test
    fun `轴线方向是满亮`() {
        val s = spot(innerDeg = 10.0, outerDeg = 25.0)
        assertEquals(1.0, LightMath.coneFactor(s, 0.0, -4.0, 0.0), 1e-9)
    }

    @Test
    fun `外锥之外完全没有光`() {
        val s = spot(innerDeg = 10.0, outerDeg = 25.0)
        // 与轴向夹角 60°，远超外锥 25°
        val d = 4.0
        val a = Math.toRadians(60.0)
        val x = d * Math.sin(a)
        val y = -d * Math.cos(a)
        assertEquals(0.0, LightMath.coneFactor(s, x, y, 0.0), 1e-9)
    }

    @Test
    fun `内外锥之间的过渡是单调的`() {
        val s = spot(innerDeg = 10.0, outerDeg = 40.0)
        var last = 1.0
        for (i in 0..40) {
            val deg = 10.0 + i * 0.75
            val a = Math.toRadians(deg)
            val x = 5.0 * Math.sin(a)
            val y = -5.0 * Math.cos(a)
            val f = LightMath.coneFactor(s, x, y, 0.0)
            assertTrue(f <= last + 1e-9, "夹角 $deg° 处锥体权重反弹")
            assertTrue(f in 0.0..1.0)
            last = f
        }
    }

    @Test
    fun `内锥角内保持满亮`() {
        val s = spot(innerDeg = 20.0, outerDeg = 35.0)
        for (deg in listOf(0.0, 5.0, 12.0, 19.0)) {
            val a = Math.toRadians(deg)
            val x = 6.0 * Math.sin(a)
            val y = -6.0 * Math.cos(a)
            assertEquals(1.0, LightMath.coneFactor(s, x, y, 0.0), 1e-9, "夹角 $deg° 应在内锥内")
        }
    }

    @Test
    fun `背面完全不受光`() {
        val s = spot(innerDeg = 10.0, outerDeg = 30.0)
        assertEquals(0.0, LightMath.coneFactor(s, 0.0, 4.0, 0.0), 1e-9, "光源上方（背面）不该有光")
    }

    @Test
    fun `没有朝向时是点光`() {
        val p = point()
        assertTrue(!p.isSpot)
        assertEquals(1.0, LightMath.coneFactor(p, 3.0, 4.0, 0.0), 1e-9)
    }

    @Test
    fun `边缘锐度越大过渡越陡`() {
        val soft = spot(innerDeg = 10.0, outerDeg = 40.0, edge = 1.0)
        val hard = spot(innerDeg = 10.0, outerDeg = 40.0, edge = 6.0)
        val a = Math.toRadians(25.0)
        val x = 5.0 * Math.sin(a)
        val y = -5.0 * Math.cos(a)
        assertTrue(
            LightMath.coneFactor(hard, x, y, 0.0) < LightMath.coneFactor(soft, x, y, 0.0),
            "同样的夹角，锐度高的一侧应当更暗（过渡更陡）",
        )
    }

    // ── 合成 ──────────────────────────────────────────────────────────────────

    @Test
    fun `射灯贡献等于亮度乘衰减乘锥体`() {
        val s = spot(innerDeg = 0.0, outerDeg = 30.0, luminance = 12.0, range = 10.0)
        val x = 0.0
        val y = -5.0
        val z = 0.0
        val expected = 12.0 * LightMath.attenuation(5.0, 10.0, LightFalloff.LINEAR) * 1.0
        assertEquals(expected, LightMath.contribution(s, x, y, z), 1e-9)
    }

    @Test
    fun `超出范围直接为零`() {
        val p = point(range = 5.0)
        assertEquals(0.0, LightMath.contribution(p, 0.0, 6.0, 0.0))
    }

    @Test
    fun `多灯合成取最亮而不是相加`() {
        val a = point(luminance = 8.0, range = 10.0)
        val b = point(luminance = 6.0, range = 10.0)
        val rgb = DoubleArray(3)
        val v = LightMath.combine(listOf(a, b), 1.0, 0.0, 0.0, rgb)
        assertTrue(v <= 8.0 + 1e-9, "合成不应超过最亮那盏：$v")
        assertEquals(8.0 * LightMath.attenuation(1.0, 10.0, LightFalloff.LINEAR), v, 1e-9)
    }

    @Test
    fun `颜色按贡献加权平均`() {
        val red = LightEmission(0.0, 0.0, 0.0, 10.0, 10.0, red = 1.0, green = 0.0, blue = 0.0)
        val blue = LightEmission(2.0, 0.0, 0.0, 10.0, 10.0, red = 0.0, green = 0.0, blue = 1.0)
        val rgb = DoubleArray(3)
        LightMath.combine(listOf(red, blue), 1.0, 0.0, 0.0, rgb)
        // 中点两侧衰减相同，应当是 50/50
        assertEquals(0.5, rgb[0], 1e-9)
        assertEquals(0.0, rgb[1], 1e-9)
        assertEquals(0.5, rgb[2], 1e-9)
    }

    @Test
    fun `没有光时颜色是黑的`() {
        val rgb = DoubleArray(3) { 1.0 }
        val v = LightMath.combine(emptyList(), 0.0, 0.0, 0.0, rgb)
        assertEquals(0.0, v)
        assertTrue(rgb.all { it == 0.0 })
    }

    // ── 朝向与光斑 ────────────────────────────────────────────────────────────

    @Test
    fun `偏航俯仰得到单位向量且方向符合直觉`() {
        val down = LightMath.directionOf(0.0, -90.0)
        assertEquals(-1.0, down.y, 1e-9)
        val flat = LightMath.directionOf(0.0, 0.0)
        assertEquals(1.0, flat.z, 1e-9)
        val turned = LightMath.directionOf(90.0, 0.0)
        assertEquals(-1.0, turned.x, 1e-9)
        for ((yaw, pitch) in listOf(0.0 to 0.0, 45.0 to 30.0, 180.0 to -60.0)) {
            val v = LightMath.directionOf(yaw, pitch)
            assertEquals(1.0, Math.sqrt(v.x * v.x + v.y * v.y + v.z * v.z), 1e-9)
        }
    }

    @Test
    fun `光斑半径随距离线性增长`() {
        val s = spot(innerDeg = 15.0, outerDeg = 30.0)
        val near = LightMath.spotFootprintRadius(s, 5.0)
        val far = LightMath.spotFootprintRadius(s, 10.0)
        assertTrue(near > 0.0)
        assertEquals(2.0, far / near, 1e-9)
        // 30° 外锥在地面上的半径约等于 距离 × tan30°
        assertEquals(5.0 * Math.tan(Math.toRadians(30.0)), near, 1e-6)
    }

    @Test
    fun `点光没有光斑`() {
        assertEquals(0.0, LightMath.spotFootprintRadius(point(), 10.0))
    }

    @Test
    fun `归一化亮度被夹在零到一`() {
        assertEquals(1.0, LightMath.normalized(15.0))
        assertEquals(0.0, LightMath.normalized(-3.0))
        assertTrue(abs(LightMath.normalized(7.5) - 0.5) < 1e-9)
    }
}
