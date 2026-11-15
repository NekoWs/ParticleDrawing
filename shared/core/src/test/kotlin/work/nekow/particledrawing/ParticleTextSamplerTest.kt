package work.nekow.particledrawing

import work.nekow.particledrawing.api.ParticleTextSampler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ParticleTextSamplerTest {
    @Test
    fun `同一字体和文本的坐标可重复生成并适配目标高度`() {
        val text = "Morph"
        val points = ParticleTextSampler.sample(text, 602, 2.0)
        assertEquals(602, points.toSet().size)
        assertEquals(points, ParticleTextSampler.sample(text, 602, 2.0))
        assertTrue(points.all { it.z == 0.0 && it.y in -1.0..1.0 })
        assertTrue(points.maxOf { it.y } - points.minOf { it.y } > 1.8)

        val halfHeight = ParticleTextSampler.sample(text, 602, 1.0)
        assertEquals(points.map { it.scale(0.5) }, halfHeight)
    }

    @Test
    fun `换行分开笔画而微小字形可以提供足量采样点`() {
        val lines = ParticleTextSampler.sample("H\nH", 80, 2.0)
        assertEquals(80, lines.size)
        assertTrue(lines.all { kotlin.math.abs(it.y) > 0.02 })
        val dot = ParticleTextSampler.sample(".", 8192, 1.0)
        assertEquals(8192, dot.toSet().size)
    }

    @Test
    fun `空白或缺字时明确报错`() {
        assertFailsWith<IllegalArgumentException> { ParticleTextSampler.sample(" \n ", 12, 1.0) }
        val missing = String(Character.toChars(0x10FFFF))
        val error = assertFailsWith<IllegalArgumentException> { ParticleTextSampler.sample(missing, 12, 1.0) }
        assertTrue(error.message.orEmpty().contains("U+10FFFF"))
    }
}
