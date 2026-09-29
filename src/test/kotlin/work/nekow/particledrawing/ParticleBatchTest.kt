package work.nekow.particledrawing

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.BatchCore
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 程序化粒子集的成员簿记（[BatchCore]）。
 *
 * 用假成员测：簿记只依赖「id / 权威状态 / 一次包直设位置 / 销毁」四个访问器，
 * 而真实 `ParticleHandle` 要服务端关卡才构造得出来。这里钉的是几条使用方最在意的语义：
 * 过期成员自动出列、批量直设位置只发一包且顺序与登记顺序一致、条件回收与补齐的策略。
 */
class ParticleBatchTest {

    private class Fake(val id: UUID = UUID.randomUUID()) {
        var pos: Vec3? = Vec3.ZERO
        var vel: Vec3 = Vec3.ZERO
        var destroyed = false
    }

    private class Harness {
        val sent = ArrayList<Pair<List<UUID>, List<Vec3>>>()
        val core = BatchCore<Fake>(
            idOf = { it.id },
            stateOf = { f -> f.pos?.let { it to f.vel } },
            sendPositions = { ids, positions -> sent.add(ids to positions); ids.size },
            destroy = { it.destroyed = true },
        )
        val alive get() = sent.last()
    }

    @Test
    fun `批量直设位置只发一包，顺序与登记顺序一致`() {
        val h = Harness()
        val a = Fake(); val b = Fake(); val c = Fake()
        h.core.add(a); h.core.add(b); h.core.add(c)

        val n = h.core.trackAll(listOf(Vec3(1.0, 0.0, 0.0), Vec3(2.0, 0.0, 0.0), Vec3(3.0, 0.0, 0.0)))

        assertEquals(3, n, "一次应下发三颗粒子")
        assertEquals(1, h.sent.size, "三个位置必须合并在一个包里")
        assertEquals(listOf(a.id, b.id, c.id), h.sent[0].first, "id 顺序 = 登记顺序")
        assertEquals(listOf(Vec3(1.0, 0.0, 0.0), Vec3(2.0, 0.0, 0.0), Vec3(3.0, 0.0, 0.0)), h.sent[0].second)
    }

    @Test
    fun `位置列表短于成员时按短的一方截断`() {
        val h = Harness()
        val a = Fake(); val b = Fake()
        h.core.add(a); h.core.add(b)
        assertEquals(1, h.core.trackAll(listOf(Vec3(9.0, 0.0, 0.0))))
        assertEquals(listOf(a.id), h.sent[0].first)
    }

    @Test
    fun `逐成员算位置也合成一包`() {
        val h = Harness()
        val a = Fake(); val b = Fake()
        h.core.add(a); h.core.add(b)
        val n = h.core.trackEach { index, _ -> Vec3(index.toDouble(), 0.0, 0.0) }
        assertEquals(2, n)
        assertEquals(1, h.sent.size, "trackEach 也必须只发一包")
        assertEquals(listOf(Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0)), h.sent[0].second)
    }

    @Test
    fun `PD 侧过期或销毁的成员自动出列`() {
        val h = Harness()
        val a = Fake(); val b = Fake()
        h.core.add(a); h.core.add(b)
        assertEquals(2, h.core.size())

        b.pos = null // 模拟 PD 侧寿命到点 / 已被销毁
        assertEquals(1, h.core.evictDead())
        assertEquals(1, h.core.size())
        assertEquals(listOf(a), h.core.members())

        h.core.trackAll(listOf(Vec3(1.0, 1.0, 1.0)))
        assertEquals(listOf(a.id), h.sent.last().first, "出列的成员不该再出现在位置包里")
    }

    @Test
    fun `条件回收按权威位置与速度判定，只销毁命中的成员`() {
        val h = Harness()
        val far = Fake().also { it.pos = Vec3(5.0, 0.0, 0.0) }
        val near = Fake().also { it.pos = Vec3(0.05, 0.0, 0.0); it.vel = Vec3(-0.2, 0.0, 0.0) }
        h.core.add(far); h.core.add(near)

        val removed = h.core.removeIf { _, pos, vel -> pos.x < 0.1 && vel.x < 0.0 }

        assertEquals(1, removed)
        assertTrue(near.destroyed, "命中的成员应被销毁")
        assertTrue(!far.destroyed, "没命中的成员不能被误销毁")
        assertEquals(listOf(far), h.core.members())
    }

    @Test
    fun `遍历只给存活成员，且位置速度来自权威值`() {
        val h = Harness()
        val a = Fake().also { it.pos = Vec3(1.0, 0.0, 0.0); it.vel = Vec3(0.0, 0.0, 0.5) }
        val dead = Fake().also { it.pos = null }
        val c = Fake().also { it.pos = Vec3(3.0, 0.0, 0.0) }
        h.core.add(a); h.core.add(dead); h.core.add(c)

        val seen = ArrayList<String>()
        h.core.forEach { index, _, pos, vel -> seen.add("$index:${pos.x}:${vel.z}") }

        assertEquals(listOf("0:1.0:0.5", "1:3.0:0.0"), seen)
    }

    @Test
    fun `补齐到目标数量，生成被上限拒绝时立刻停下`() {
        val h = Harness()
        var calls = 0
        val added = h.core.ensureSize(5) { index ->
            calls++
            if (index == 3) null else Fake() // 第 4 个被维度上限拒绝
        }
        assertEquals(3, added, "上限拒绝时应停在已补到的数量，不空转")
        assertEquals(3, h.core.size())
        assertEquals(4, calls, "多试了一次拿到 null 就该停")
    }

    @Test
    fun `补齐会先摘掉过期成员再算数量`() {
        val h = Harness()
        h.core.add(Fake().also { it.pos = null })
        val added = h.core.ensureSize(2) { Fake() }
        assertEquals(2, added, "过期成员不算在数量里")
        assertEquals(2, h.core.size())
    }

    @Test
    fun `清空会销毁仍存活的成员并清掉成员表`() {
        val h = Harness()
        val a = Fake()
        val dead = Fake().also { it.pos = null }
        h.core.add(a); h.core.add(dead)

        h.core.clear()

        assertTrue(a.destroyed, "存活成员应被销毁")
        assertTrue(!dead.destroyed, "已不存在的成员不该再发销毁包")
        assertEquals(0, h.core.rawSize())
    }

    @Test
    fun `单个成员直设位置只发它自己`() {
        val h = Harness()
        val a = Fake(); val b = Fake()
        h.core.add(a); h.core.add(b)
        h.core.track(b, Vec3(7.0, 0.0, 0.0))
        assertEquals(listOf(b.id), h.sent[0].first)
        assertEquals(listOf(Vec3(7.0, 0.0, 0.0)), h.sent[0].second)
    }

    @Test
    fun `已失效成员做单点直设位置时直接出列`() {
        val h = Harness()
        val dead = Fake().also { it.pos = null }
        h.core.add(dead)
        h.core.track(dead, Vec3(1.0, 0.0, 0.0))
        assertEquals(0, h.sent.size, "失效成员不该发包")
        assertEquals(0, h.core.rawSize())
    }
}
