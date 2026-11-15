package work.nekow.particledrawing

import work.nekow.particledrawing.animation.AudioAsset
import work.nekow.particledrawing.animation.script.ScriptWavePcm
import work.nekow.particledrawing.math.ResourceId
import work.nekow.particledrawing.math.Vec3
import java.lang.reflect.InvocationTargetException
import java.net.URL
import java.net.URLClassLoader
import java.util.Collections
import java.util.Enumeration
import kotlin.test.*

class CoreHostContractTest {
    @Test fun `没有原生解码器时 OGG 明确拒绝而不是生成静音`() {
        val asset = AudioAsset("missing", "missing", 0, byteArrayOf(1), 0, 1000, 0,
            0.0, 0.0, 0.0, emptyList(), shortArrayOf(), shortArrayOf(), shortArrayOf(), byteArrayOf(), byteArrayOf(), byteArrayOf())
        val pcmClass = ScriptWavePcm::class.java
        val service = "META-INF/services/${ScriptWavePcm.OggReader::class.java.name}"
        val location = pcmClass.protectionDomain.codeSource.location
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        val error = object : URLClassLoader(arrayOf(location), pcmClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name != pcmClass.name && !name.startsWith(pcmClass.name + '$')) return super.loadClass(name, resolve)
                val type = findLoadedClass(name) ?: findClass(name)
                if (resolve) resolveClass(type)
                return type
            }

            override fun getResources(name: String): Enumeration<URL> =
                if (name == service) Collections.emptyEnumeration() else super.getResources(name)
        }.use { isolated ->
            try {
                thread.contextClassLoader = isolated
                val runtimeType = isolated.loadClass(pcmClass.name)
                val runtime = runtimeType.getField("INSTANCE").get(null)
                val thrown = assertFailsWith<InvocationTargetException> {
                    runtimeType.getMethod("ready", AudioAsset::class.java).invoke(runtime, asset)
                }
                assertIs<IllegalStateException>(thrown.targetException)
            } finally {
                thread.contextClassLoader = previous
            }
        }
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
