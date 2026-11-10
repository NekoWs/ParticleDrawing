package work.nekow.particledrawing

import work.nekow.particledrawing.animation.PdrawcReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 加法混合效果在播放端的读回契约：函数对象 flags bit7 → additive=true，
 * 即 ADDITIVE_PARTICLE 管线读的那一位。
 *
 * 夹具是签名容器（验签由同类的另一个用例覆盖），里面恰好一个函数对象。
 */
class AdditiveFlagContractTest {

    @Test
    fun `editor-exported additive effect reads back additive flag`() {
        val bytes = javaClass.getResourceAsStream("/editor-fixture/effect-additive.pdrawc")
            ?.readBytes() ?: error("找不到夹具 editor-fixture/effect-additive.pdrawc")
        val anim = PdrawcReader.parse(bytes)
        assertEquals(1, anim.functions.size, "夹具应恰好一个函数对象，additive 挂在它的 flags 上")
        assertTrue(anim.functions[0].additive, "flags bit7 应映射为 additive=true")
    }

    @Test
    fun `fixture is signed and passes verification`() {
        val bytes = javaClass.getResourceAsStream("/editor-fixture/effect-additive.pdrawc")
            ?.readBytes() ?: error("找不到夹具")
        assertTrue(PdrawcReader.verify(bytes), "夹具签名必须有效（编辑器导出即签名）")
    }
}