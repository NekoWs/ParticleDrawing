package work.nekow.particledrawing

import work.nekow.particledrawing.animation.AudioAsset
import work.nekow.particledrawing.animation.script.ScriptWavePcm
import work.nekow.particledrawing.math.ResourceId
import work.nekow.particledrawing.math.Vec3
import kotlin.test.*

class CoreHostContractTest {
    @Test fun `没有原生解码器时 OGG 明确拒绝而不是生成静音`() {
        val asset = AudioAsset("missing", "missing", 0, byteArrayOf(1), 0, 1000, 0,
            0.0, 0.0, 0.0, emptyList(), shortArrayOf(), shortArrayOf(), shortArrayOf(), byteArrayOf(), byteArrayOf(), byteArrayOf())
        val error = assertFailsWith<IllegalStateException> { ScriptWavePcm.ready(asset) }
        assertTrue(error.message.orEmpty().contains("OGG"))
    }

    @Test fun `资源标识保留命名空间且拒绝非法路径字符`() {
        assertEquals(ResourceId("minecraft", "particle/end_rod"), ResourceId.parse("particle/end_rod"))
        assertEquals("demo:images/ring.png", ResourceId.parse("demo:images/ring.png").toString())
        assertNull(ResourceId.tryParse("UPPER:bad"))
        assertNull(ResourceId.tryParse("demo:"))
    }

    @Test fun `核心向量可独立计算旋转需要的正交基与归一化`() {
        assertEquals(Vec3(0.0, 0.0, 1.0), Vec3(1.0, 0.0, 0.0).cross(Vec3(0.0, 1.0, 0.0)))
        assertEquals(Vec3.ZERO, Vec3(1e-8, 0.0, 0.0).normalize())
        assertEquals(Vec3(0.6, 0.8, 0.0), Vec3(3.0, 4.0, 0.0).normalize())
    }
}
