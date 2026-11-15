package work.nekow.particledrawing

import com.google.gson.JsonParser
import work.nekow.particledrawing.animation.ClientAnimationPlayer
import work.nekow.particledrawing.animation.FunctionObject
import work.nekow.particledrawing.animation.ParticleAnimation
import work.nekow.particledrawing.animation.PdrawcReader
import work.nekow.particledrawing.math.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScriptMorphPreviewTest {

    private fun fixture(name: String) =
        checkNotNull(javaClass.getResourceAsStream("/editor-fixture/$name")) { "找不到夹具 $name" }

    private val samples = fixture("morph-preview.json").bufferedReader().use { JsonParser.parseReader(it).asJsonArray }
    private val animation = fixture("morph-preview.pdrawc").use { it.readBytes() }.let {
        assertTrue(PdrawcReader.verify(it))
        PdrawcReader.parse(it)
    }

    private fun player() = ClientAnimationPlayer(animation, Vec3.ZERO, startGameTick = 0L, currentGameTick = 0L)

    private fun compare(player: ClientAnimationPlayer, sampleIndex: Int, include: (String) -> Boolean = { true }) {
        val frame = samples[sampleIndex].asJsonObject
        val time = frame["timeMs"].asDouble
        val expected = frame["positions"].asJsonObject.entrySet().filter { include(it.key) }.associate { it.key to it.value }
        val actual = player.currentStates().filter { it.visible && include(it.id) }.associateBy { it.id }
        assertEquals(expected.keys, actual.keys, "粒子集合 @ ${time}ms")
        for ((id, point) in expected) {
            val pos = actual.getValue(id).pos
            val coords = point.asJsonArray
            assertEquals(coords[0].asDouble, pos.x, 1e-8, "$id.x @ ${time}ms")
            assertEquals(coords[1].asDouble, pos.y, 1e-8, "$id.y @ ${time}ms")
            assertEquals(coords[2].asDouble, pos.z, 1e-8, "$id.z @ ${time}ms")
        }
    }

    @Test
    fun `签名动画的播放坐标逐帧复现编辑器预览`() {
        val player = player()
        assertTrue(player.isFrameSyncDerived("fx1:p0"), "只有 setup 的形变也应按渲染帧同步")
        for (i in 0 until samples.size()) {
            if (i > 0) player.advanceFrame(samples[i].asJsonObject["timeMs"].asDouble)
            compare(player, i)
        }
        player.advanceFrame(75.0)
        compare(player, 3)
    }

    @Test
    fun `稀疏采样的形变位置一致且回卷恢复初始形变`() {
        val player = player()
        val last = samples.size() - 1
        player.advanceFrame(samples[last].asJsonObject["timeMs"].asDouble)
        // fx3 含 process 中间时刻发出的重定向，这里只比较无需依赖中间 process 调用的对象。
        compare(player, last) { !it.startsWith("fx3:") }
        player.tickExternal(950)
        player.tickExternal(1000)
        assertTrue(player.consumeJustLooped())
        player.advanceFrame(0.0)
        compare(player, 0)
        player.advanceFrame(50.0)
        compare(player, 2)
    }

    @Test
    fun `无固定时长的 setup 形变不走静态快路径`() {
        val fx = FunctionObject("fx", "fx", doubleArrayOf(0.0, 0.0, 0.0), animation.functions[1].source, 0, emptyMap(), 0)
        val indefinite = ParticleAnimation(false, emptyList(), emptyList(), emptyMap(), listOf(fx))
        val player = ClientAnimationPlayer(indefinite, Vec3.ZERO, 0L, 0L)
        assertFalse(player.isStatic())
        val before = player.currentStates().associate { it.id to it.pos }
        player.advanceFrame(75.0)
        assertTrue(player.currentStates().any { before[it.id] != it.pos })
    }
}
