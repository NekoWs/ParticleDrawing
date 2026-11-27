package work.nekow.particledrawing

import work.nekow.particledrawing.api.Draw
import work.nekow.particledrawing.api.ParticleStyle
import work.nekow.particledrawing.command.ParticleCommandOptionParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ParticleCommandOptionsTest {

    @Test
    fun `完整参数会落到绘图与外观规格`() {
        val options = ParticleCommandOptionParser.parse(
            "gradient=#ff0000:#0000ff scale=0.5 style=soft_dot size_world=2,0.25 " +
                "spin=10,20,30 spin_space=world additive glowing=false light=12 intensity=1.5 " +
                "alpha_curve=0:0:ease_out,10:1 axis=xy taper hollow thickness=0.2 " +
                "lifetime=40 velocity=1,2,3 force=0,-0.1,0 force_ticks=5",
        )

        assertEquals(0.5f, options.scale)
        assertEquals(Draw.Axis.XY, options.axis)
        assertTrue(options.taper)
        assertTrue(options.hollow)
        assertEquals(0.2f, options.thickness)
        assertEquals(40, options.lifetime)
        assertEquals(1.0, options.velocity?.x)
        assertEquals(-0.1, options.force?.y ?: 0.0, 1e-6)
        assertEquals(5, options.forceTicks)
        assertEquals(ParticleStyle.SOFT_DOT.textureName, options.visual.texture)
        assertTrue(options.visual.worldUnits)
        assertFalse(options.visual.spinLocal)
        assertTrue(options.visual.additive)
        assertFalse(options.visual.glowing)
        assertEquals(12, options.visual.lightLevel)
        assertNotNull(options.lifeCurve)
        assertEquals(1f, options.color.colorAt(0.0).r)
        assertEquals(1f, options.color.colorAt(1.0).b)
    }

    @Test
    fun `light_only 是开关参数并自动打开向外照明`() {
        val on = ParticleCommandOptionParser.parse("light=15 light_only")
        assertTrue(on.visual.lightOnly)
        assertTrue(on.visual.emitsLight, "只发光必须同时是光源，否则这条命令没有任何效果")

        val off = ParticleCommandOptionParser.parse("light_only=false")
        assertFalse(off.visual.lightOnly)
        assertFalse(off.visual.emitsLight)
    }

    @Test
    fun `glow 只免光照，照明必须显式写 light`() {
        val glow = ParticleCommandOptionParser.parse("glowing=true")
        assertTrue(glow.visual.glowing)
        assertFalse(glow.visual.emitsLight, "glow 不再隐式投光，避免总要补 light 0")
        assertEquals(0, glow.visual.lightLevel)

        val light = ParticleCommandOptionParser.parse("light=12")
        assertTrue(light.visual.emitsLight)
        assertEquals(12, light.visual.lightLevel)

        val dark = ParticleCommandOptionParser.parse("glowing=true light=0")
        assertFalse(dark.visual.emitsLight)
    }

    @Test
    fun `引号允许贴图名和值包含空格`() {
        val options = ParticleCommandOptionParser.parse("texture=\"magic ring/main\" color=#abcdef")
        assertEquals("magic ring/main", options.visual.texture)
        assertEquals(setOf("texture", "color"), options.provided)
    }

    @Test
    fun `冲突依赖和未知参数都会明确报错`() {
        assertFailsWith<IllegalArgumentException> {
            ParticleCommandOptionParser.parse("color=#ffffff rainbow")
        }
        assertFailsWith<IllegalArgumentException> {
            ParticleCommandOptionParser.parse("force_ticks=10")
        }
        assertFailsWith<IllegalArgumentException> {
            ParticleCommandOptionParser.parse("spin_space=world")
        }
        assertFailsWith<IllegalArgumentException> {
            ParticleCommandOptionParser.parse("light=16")
        }
        assertFailsWith<IllegalArgumentException> {
            ParticleCommandOptionParser.parse("stagger=-1")
        }
        assertFailsWith<IllegalArgumentException> {
            ParticleCommandOptionParser.parse("unknown=1")
        }
    }

    @Test
    fun `重复参数和未闭合引号会报错`() {
        assertFailsWith<IllegalArgumentException> {
            ParticleCommandOptionParser.parse("scale=1 scale=2")
        }
        assertFailsWith<IllegalArgumentException> {
            ParticleCommandOptionParser.parse("texture=\"unfinished")
        }
    }
    @Test
    fun `拉丝选项保留运动前的广告牌朝向`() {
        val visual = ParticleCommandOptionParser.parse("align_velocity=true stretch=2.5").visual
        assertTrue(visual.alignVelocity)
        assertEquals(2.5, visual.stretch, 1e-6)
        assertTrue(visual.billboard)
    }

    @Test
    fun `只给 stretch 也会打开对齐`() {
        val visual = ParticleCommandOptionParser.parse("stretch=1.2").visual
        assertTrue(visual.alignVelocity)
        assertEquals(1.2, visual.stretch, 1e-6)
    }
    @Test
    fun `软粒子半径被写入外观`() {
        val visual = ParticleCommandOptionParser.parse("soft=0.6").visual
        assertEquals(0.6, visual.softFade, 1e-6)
    }

    @Test
    fun `拖尾长度使用世界刻且检查上下限`() {
        assertEquals(24, ParticleCommandOptionParser.parse("trail=24").visual.trailTicks)
        assertEquals(0, ParticleCommandOptionParser.parse("trail=0").visual.trailTicks)
        assertFailsWith<IllegalArgumentException> { ParticleCommandOptionParser.parse("trail=33") }
    }
}
