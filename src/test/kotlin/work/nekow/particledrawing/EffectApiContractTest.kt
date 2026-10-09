package work.nekow.particledrawing

import work.nekow.particledrawing.api.EffectCallbacks
import work.nekow.particledrawing.api.EffectVarStore
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 纯 JVM 契约测试：回调注册/触发/清理语义与变量覆盖记忆。
 */
class EffectApiContractTest {

    @Test
    fun `finished 回调：fire 触发一次并清空该 ID；未注册的 ID 不炸`() {
        val id = UUID.randomUUID()
        var hits = 0
        EffectCallbacks.onFinished(id) { hits++ }
        EffectCallbacks.onFinished(id) { hits++ }
        EffectCallbacks.fireFinished(id)
        assertEquals(2, hits)
        EffectCallbacks.fireFinished(id)   // 第二次 fire 不再触发（回调已清空）
        assertEquals(2, hits)
        EffectCallbacks.fireFinished(UUID.randomUUID())   // 不抛
    }

    @Test
    fun `paramChanged 回调：多次覆盖都触发，携带名字与值；clear 后不再触发`() {
        val id = UUID.randomUUID()
        val events = mutableListOf<Pair<String, String>>()
        EffectCallbacks.onParamChanged(id) { _, name, value -> events.add(name to value) }
        EffectCallbacks.fireParamChanged(id, "speed", "2.0")
        EffectCallbacks.fireParamChanged(id, "speed", "3.5")
        assertEquals(listOf("speed" to "2.0", "speed" to "3.5"), events)
        EffectCallbacks.clear(id)
        EffectCallbacks.fireParamChanged(id, "speed", "4.0")
        assertEquals(2, events.size)
    }

    @Test
    fun `变量记忆：getParam 返回最近一次设置值；未设置或非数字返回 null`() {
        val store = EffectVarStore()
        assertNull(store.getDouble("speed"))
        store.set("speed", "2.5")
        assertEquals(2.5, store.getDouble("speed"))
        store.set("speed", "4.0")
        assertEquals(4.0, store.getDouble("speed"))
        store.set("speed", "不是数字")
        assertNull(store.getDouble("speed"))     // 不是数字就 null
        assertEquals(1, store.size())
        assertTrue(store.get("speed") == "不是数字")   // 字符串读回仍有
    }
}