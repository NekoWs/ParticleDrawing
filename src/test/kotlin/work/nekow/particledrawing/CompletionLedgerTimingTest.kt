package work.nekow.particledrawing

import work.nekow.particledrawing.core.client.completionLedgerEndMs
import work.nekow.particledrawing.core.server.AnimationScheduler
import work.nekow.particledrawing.core.server.ServerProgramCompletion
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 完成账本的两条时效语义：
 * - **走完的缓动终点要留到上报那一次**（到点与上报之间隔着一 tick 桥接余量，
 *   不留这一笔、纯变量组会在该报的那一 tick 发现账本空了）；
 * - **兜底任务带登记版本**（缓动重定向把账本往后挪之后，旧任务不许按老时刻收走这个组）。
 */
class CompletionLedgerTimingTest {

    /** 兜底余量（`ServerProgramCompletion` 里的常量）：排任务时额外加在后面。 */
    private val fallbackMargin = 20

    @BeforeTest
    fun setUp() {
        ServerProgramCompletion.clearAll()
        AnimationScheduler.clear()
    }

    @AfterTest
    fun tearDown() {
        ServerProgramCompletion.clearAll()
        AnimationScheduler.clear()
    }

    @Test
    fun `走完的缓动终点留账到上报，纯变量组不会错过信号`() {
        // 缓动 20 tick 走完 → 账本终点 20；上报要等 +1 tick（桥接余量），那一 tick 缓动已经不在表里了
        val activeEases = emptyList<Long>()
        assertEquals(20L, completionLedgerEndMs(-1L, expressionMode = true, expressionEndMs = -1L,
            varEaseEnds = activeEases, completedVarEndMs = 20L), "缓动摘掉后终点仍在账本上")
        assertEquals(-1L, completionLedgerEndMs(-1L, expressionMode = true, expressionEndMs = -1L,
            varEaseEnds = activeEases), "没有留账就会空（这就是原来错过的原因）")
    }

    @Test
    fun `留账的终点与指令、表达式取最晚，且不超过后来重定向的新终点`() {
        assertEquals(60L, completionLedgerEndMs(30L, expressionMode = false, expressionEndMs = -1L,
            varEaseEnds = listOf(60L), completedVarEndMs = 20L))
        assertEquals(60L, completionLedgerEndMs(-1L, expressionMode = true, expressionEndMs = 35L,
            varEaseEnds = emptyList(), completedVarEndMs = 60L))
    }

    @Test
    fun `兜底任务带版本：重定向后旧兜底不再触发`() {
        val group = UUID.randomUUID()
        var fired = 0
        // 登记：账本还剩 20 tick → 兜底排在 20 + 20
        ServerProgramCompletion.onComplete(group, UUID.randomUUID(), fallbackTicks = 20) { fired++ }
        // 缓动被重定向：账本往后挪到 44 tick → 兜底重排到 44 + 20（旧任务应失效）
        ServerProgramCompletion.rescheduleFallback(group, fallbackTicks = 44)

        repeat(20 + fallbackMargin + 1) { AnimationScheduler.tick() }   // 走过旧兜底时刻
        assertEquals(0, fired, "旧兜底不能在重排后把这次登记吃掉（实机表现为回调提前到旧兜底时刻）")

        repeat(24 + 1) { AnimationScheduler.tick() }                    // 走到新兜底时刻
        assertEquals(1, fired, "最后一次登记照常兜底，且只触发一次")

        repeat(10) { AnimationScheduler.tick() }
        assertEquals(1, fired)
    }

    @Test
    fun `客户端上报先到：兜底不再重复触发`() {
        val group = UUID.randomUUID()
        var fired = 0
        ServerProgramCompletion.onComplete(group, UUID.randomUUID(), fallbackTicks = 5) { fired++ }
        ServerProgramCompletion.complete(group)
        assertEquals(1, fired, "客户端上报即回调")

        repeat(5 + fallbackMargin + 5) { AnimationScheduler.tick() }
        assertEquals(1, fired, "上报之后兜底必须失效")
    }

    @Test
    fun `账本被立即赋值清空时兜底按立即到点排，不丢收尾`() {
        val group = UUID.randomUUID()
        var fired = 0
        ServerProgramCompletion.onComplete(group, UUID.randomUUID(), fallbackTicks = 40) { fired++ }
        // 缓动被 setVariableLive 取消：账本空了（-1）→ 按 0 处理，仍要收尾
        ServerProgramCompletion.rescheduleFallback(group, fallbackTicks = -1)

        repeat(fallbackMargin + 1) { AnimationScheduler.tick() }
        assertEquals(1, fired, "已经登记的组不能因为账本变空就再也不收尾")
    }

    @Test
    fun `没登记过的组重排兜底是空操作`() {
        ServerProgramCompletion.rescheduleFallback(UUID.randomUUID(), fallbackTicks = 3)
        repeat(30) { AnimationScheduler.tick() }
        assertEquals(0, ServerProgramCompletion.size())
    }
}
