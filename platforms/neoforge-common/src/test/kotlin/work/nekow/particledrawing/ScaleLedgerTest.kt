package work.nekow.particledrawing

import work.nekow.particledrawing.core.client.ScaleLedger
import kotlin.test.Test
import kotlin.test.assertEquals

class ScaleLedgerTest {
    @Test
    fun `相对缩放从执行起点相乘，可先缩到 0 再长回 1`() {
        val ledger = ScaleLedger()
        // 第一条：从 1 缩到 0.01（瞬时）
        var target = ledger.begin(1f, 0.01f, absolute = false)
        assertEquals(0.01f, target, 1e-6f)
        var current = ledger.valueAt(target, 1f)
        assertEquals(0.01f, current, 1e-6f)

        // 第二条：从 0.01 乘 100 → 终点是 1
        val ledger2 = ScaleLedger()
        target = ledger2.begin(current, 100f, absolute = false)
        assertEquals(1f, target, 1e-6f)
        assertEquals(0.505f, ledger2.valueAt(target, 0.5f), 1e-6f, "中点线性（缓动由调用方给）")
        assertEquals(1f, ledger2.valueAt(target, 1f), 1e-6f)
    }

    @Test
    fun `绝对缩放以当前倍率为起点：从 1_5 倍退场不会先跳回 1`() {
        val ledger = ScaleLedger()
        val target = ledger.begin(1.5f, 0f, absolute = true)
        assertEquals(1.5f, ledger.valueAt(target, 0f), 1e-6f, "起点是当时的 1.5 倍")
        assertEquals(0.75f, ledger.valueAt(target, 0.5f), 1e-6f)
        assertEquals(0f, ledger.valueAt(target, 1f), 1e-6f, "0 = 完全收起")
    }

    @Test
    fun `执行起点只捕获一次：同一条指令的每帧调用不会漂移`() {
        val ledger = ScaleLedger()
        val target = ledger.begin(1f, 3f, absolute = false)
        assertEquals(3f, target, 1e-6f)
        // 中间帧把「当前倍率」传成已推进的值，起点仍必须是第一帧捕获的 1
        assertEquals(3f, ledger.begin(2.4f, 3f, absolute = false), 1e-6f)
        assertEquals(2f, ledger.valueAt(target, 0.5f), 1e-6f)
    }
}
