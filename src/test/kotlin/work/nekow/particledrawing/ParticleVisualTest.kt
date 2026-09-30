package work.nekow.particledrawing

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.ParticleStyle
import work.nekow.particledrawing.api.ParticleVisual
import work.nekow.particledrawing.animation.UvData
import work.nekow.particledrawing.util.VisualMath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 逐粒子外观规格：贴图/UV 解析、各向异性（含世界格口径）、朝向、复制语义。
 */
class ParticleVisualTest {

    @Test
    fun `默认外观是「什么都没设」`() {
        assertTrue(ParticleVisual().isPristine())
        assertFalse(ParticleVisual().glowing(true).isPristine())
        assertFalse(ParticleVisual().billboard(false).isPristine())
        assertFalse(ParticleVisual().additive(true).isPristine())
        assertFalse(ParticleVisual().texture("whatever").isPristine())
        assertFalse(ParticleVisual().aniso(1f, 1f).isPristine())
    }

    @Test
    fun `整图 UV：取景框就是贴图尺寸，尺寸系数按最长边算`() {
        val v = ParticleVisual().texture("t:full")
        val uv = v.toUvData(32, 16)!!
        assertEquals(0, uv.uvStart[0])
        assertEquals(0, uv.uvStart[1])
        assertEquals(32, uv.uvSize[0])
        assertEquals(16, uv.uvSize[1])
        assertEquals(UvData.Mode.STATIC, uv.mode)
        assertEquals(2f, ParticleVisual.texScale(uv), 1e-6f, "32px → 系数 2")
    }

    @Test
    fun `子矩形 UV：取景框是子矩形本身，尺寸系数按子矩形算`() {
        // 64×64 图集里取 16×16 的一格：应该按 16px 算系数（= 1），不是按整图 64px
        val v = ParticleVisual().texture("t:atlas").uv(16f, 32f, 32f, 48f)
        val uv = v.toUvData(64, 64)!!
        assertEquals(16, uv.uvStart[0])
        assertEquals(32, uv.uvStart[1])
        assertEquals(16, uv.uvSize[0])
        assertEquals(16, uv.uvSize[1])
        assertEquals(1f, ParticleVisual.texScale(uv), 1e-6f)
    }

    @Test
    fun `贴图没到货也给出取景框（按请求的矩形算尺寸）`() {
        val v = ParticleVisual().texture("t:missing").uv(0f, 0f, 24f, 24f)
        val uv = v.toUvData(0, 0)!!
        assertEquals("t:missing", uv.texture)
        assertEquals(24, uv.uvSize[0])
        assertEquals(24, uv.uvSize[1])
    }

    @Test
    fun `无贴图没有 UV`() {
        assertNull(ParticleVisual().toUvData(16, 16))
        assertNull(ParticleVisual().style(ParticleStyle.SQUARE).toUvData(16, 16))
    }

    @Test
    fun `内置形状名就是贴图名`() {
        val uv = ParticleVisual().style(ParticleStyle.SOFT_DOT).toUvData(16, 16)!!
        assertEquals("particledrawing:builtin/soft_dot", uv.texture)
        assertEquals(16, uv.uvSize[0])
        assertEquals(1f, ParticleVisual.texScale(uv), 1e-6f, "内置贴图 16px → 系数 1")
    }

    @Test
    fun `编辑器单位各向异性原样下发；世界格口径按系数换算`() {
        val editor = ParticleVisual().aniso(2f, 0.5f).resolvedAniso(1f, 1f)!!
        assertEquals(2f, editor[0], 1e-6f)
        assertEquals(0.5f, editor[1], 1e-6f)

        val world = ParticleVisual().anisoWorld(3f, 0.1f).resolvedAniso(1f, 1f)!!
        // 渲染半宽 = 编辑器尺寸 × 0.1 × 系数 → 应等于请求整宽的一半
        assertEquals(1.5f, world[0] * VisualMath.EDITOR_TO_MC_SCALE, 1e-5f)
        assertEquals(0.05f, world[1] * VisualMath.EDITOR_TO_MC_SCALE, 1e-5f)
    }

    @Test
    fun `世界格口径在贴图尺寸系数不为 1 时仍然精确`() {
        val v = ParticleVisual().texture("t:big").anisoWorld(3f, 0.1f)
        val uv = v.toUvData(32, 32)!!          // 系数 2
        val texScale = ParticleVisual.texScale(uv)
        val arr = v.resolvedAniso(1f, texScale)!!
        assertEquals(1.5f, arr[0] * VisualMath.EDITOR_TO_MC_SCALE * texScale, 1e-5f)
        assertEquals(0.05f, arr[1] * VisualMath.EDITOR_TO_MC_SCALE * texScale, 1e-5f)
    }

    @Test
    fun `未给各向异性时不产生缩放数组`() {
        assertNull(ParticleVisual().resolvedAniso(1f, 1f))
        // 只给一轴：另一轴沿用标量 scale
        val half = ParticleVisual().aniso(4f, 0f).resolvedAniso(0.5f, 1f)!!
        assertEquals(4f, half[0], 1e-6f)
        assertEquals(0.5f, half[1], 1e-6f)
    }

    @Test
    fun `弧度入口转成渲染层认的度，并对齐长轴`() {
        val v = ParticleVisual().spin(Math.PI / 2)
        assertEquals(90.0, v.spinZDeg, 1e-9)
        assertEquals(0.0, v.spinXDeg, 1e-9)
        assertTrue(v.spinLocal)

        val aligned = ParticleVisual().alignTo(Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0))
        assertFalse(aligned.billboard, "对齐长轴必须关掉广告牌，否则自转不生效")
        assertEquals(0.0, aligned.spinXDeg, 1e-9)
        assertEquals(0.0, aligned.spinYDeg, 1e-9)
        assertEquals(0.0, aligned.spinZDeg, 1e-9)
    }

    @Test
    fun `copy 独立：微调副本不影响原规格`() {
        val base = ParticleVisual().style(ParticleStyle.LINE).anisoWorld(2f, 0.2f).uv(1f, 2f, 3f, 4f)
        val copy = base.copy()
        copy.scaleW = 9f
        copy.uvRect!![0] = 7f
        copy.additive(true)
        assertEquals(2f, base.scaleW, 1e-6f)
        assertEquals(1f, base.uvRect!![0], 1e-6f)
        assertFalse(base.additive)
    }

    @Test
    fun `链式设置覆盖旧值`() {
        val v = ParticleVisual().style(ParticleStyle.SOFT_DOT).uv(0f, 0f, 8f, 8f)
        assertEquals(ParticleStyle.SOFT_DOT.textureName, v.texture)
        assertEquals(8f, v.uvRect!![2], 1e-6f)
        // 换成别的贴图会清掉子矩形（否则会去新图上取旧矩形）
        v.texture("t:other")
        assertNull(v.uvRect)
        // 再设成「无贴图」形状
        v.style(ParticleStyle.SQUARE)
        assertNull(v.texture)
    }
}
