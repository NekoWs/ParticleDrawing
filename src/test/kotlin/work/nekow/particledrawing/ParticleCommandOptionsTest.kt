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
}
