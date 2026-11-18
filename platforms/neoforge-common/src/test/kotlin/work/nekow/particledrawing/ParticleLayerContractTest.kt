package work.nekow.particledrawing

import net.minecraft.resources.Identifier
import work.nekow.particledrawing.core.client.particleLayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 加色 / 半透明 / 不透明三种粒子层的对外契约。
 *
 * 26.2 与 26.3 的管线实现各自用了一代不同的渲染 API（26.3 有 OIT），文件因此分开；
 * 但三个层对引擎暴露的行为必须一致，这个用例放在共享测试里钉住那部分行为。
 * 1.21.1 没有 `particleLayer`（走另一代渲染桥），所以那一端在 shared_exclusions 里排除本文件。
 */
class ParticleLayerContractTest {

    private val texture = Identifier.fromNamespaceAndPath("particledrawing", "test_particle")

    @Test
    fun `加色层按半透明粒子参与排序`() {
        assertTrue(particleLayer(texture, translucent = false, additive = true).translucent)
    }

    @Test
    fun `不透明层不加半透明标记`() {
        assertFalse(particleLayer(texture, translucent = false, additive = false).translucent)
    }

    @Test
    fun `半透明层保持标记`() {
        assertTrue(particleLayer(texture, translucent = true, additive = false).translucent)
    }

    @Test
    fun `三种层都使用传入的贴图`() {
        for (translucent in listOf(false, true)) {
            for (additive in listOf(false, true)) {
                assertEquals(texture, particleLayer(texture, translucent, additive).textureAtlasLocation)
            }
        }
    }
}
