package work.nekow.particledrawing

import work.nekow.particledrawing.animation.PdrawcReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 跨仓契约：编辑器导出的加法混合效果（blend=additive，容器 flags bit7），播放端必须读回
 * additive=true——这是 ADDITIVE_PARTICLE 管线的数据来源。
 *
 * 夹具 `editor-fixture/effect-additive.pdrawc` 由编辑器侧一次性生成：
 *   默认发射器层 + blend=additive → exportEffectAsPdrawc（签名在容器内，parse 自验）。
 * 字段级语义在编辑器侧 `test/player-api-contract.test.js` 钉死（bit7 编码），这里只验证
 * 播放端读取端与它是同一份事实。
 */
class AdditiveFlagContractTest {

    @Test
    fun `editor-exported additive effect reads back additive flag`() {
        val bytes = javaClass.getResourceAsStream("/editor-fixture/effect-additive.pdrawc")
            ?.readBytes() ?: error("找不到夹具 editor-fixture/effect-additive.pdrawc（编辑器侧生成）")
        val anim = PdrawcReader.parse(bytes)
        assertEquals(1, anim.functions.size, "夹具应恰好一条函数对象（一个发射器层）")
        assertTrue(anim.functions[0].additive, "flags bit7 应映射为 additive=true")
    }

    @Test
    fun `fixture is signed and passes verification`() {
        val bytes = javaClass.getResourceAsStream("/editor-fixture/effect-additive.pdrawc")
            ?.readBytes() ?: error("找不到夹具")
        assertTrue(PdrawcReader.verify(bytes), "夹具签名必须有效（编辑器导出即签名）")
    }
}