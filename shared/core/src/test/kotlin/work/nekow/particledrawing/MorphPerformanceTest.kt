package work.nekow.particledrawing

import work.nekow.particledrawing.animation.morph.MorphAllocation
import work.nekow.particledrawing.animation.morph.MorphAllocator
import work.nekow.particledrawing.animation.morph.MorphOptions
import work.nekow.particledrawing.animation.morph.MorphParticle
import work.nekow.particledrawing.animation.morph.MorphRuntime
import work.nekow.particledrawing.animation.morph.MorphSlot
import work.nekow.particledrawing.math.Vec3
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 形变规模的性能护栏：最近邻分配与逐 tick 推进在万级粒子上必须是近线性，
 * 不能再退回 O(n²)（旧实现对 10k 会卡住数秒）。
 */
class MorphPerformanceTest {

    private fun particle(index: Int, position: Vec3): MorphParticle =
        MorphParticle(UUID(0x1234_5678_9ABC_DEF0L, index.toLong() + 1), position, Vec3.ZERO, index)

    private fun gridSlots(count: Int, spacing: Double = 0.2): List<MorphSlot> {
        val side = kotlin.math.ceil(kotlin.math.sqrt(count.toDouble())).toInt()
        return List(count) { index ->
            val x = (index % side) * spacing
            val z = (index / side) * spacing
            MorphSlot(Vec3(x, 0.0, z))
        }
    }

    @Test
    fun `一万粒子的最近邻分配在预算内完成且一一对应`() {
        val count = 10_000
        val slots = gridSlots(count)
        // 起点整体偏移并打乱顺序，模拟两个形状之间的形变。
        val particles = (0 until count).map { index ->
            val base = slots[(index * 7919) % count].position
            particle(index, Vec3(base.x + 0.37, base.y + 0.5, base.z - 0.21))
        }
        val started = System.nanoTime()
        val allocation = MorphAllocator.allocate(particles, slots, MorphAllocation.NEAREST)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
        assertEquals(count, allocation.slotOfParticle.size)
        assertEquals(count, allocation.slotOfParticle.values.toSet().size)
        assertTrue(allocation.unmatchedSlots.isEmpty())
        System.out.println("[PD-PERF] 10k nearest allocation: ${elapsedMs}ms")
        assertTrue(elapsedMs < 2_000.0, "10k nearest allocation took ${elapsedMs}ms")
    }

    @Test
    fun `大组最近邻仍然就近配对`() {
        val slots = gridSlots(1_024)
        val shuffled = slots.indices.sortedBy { (it * 2654435761L) and 0xFFFF }
        val slotList = shuffled.map { slots[it] }
        val particles = slots.mapIndexed { index, slot ->
            particle(index, Vec3(slot.position.x + 0.01, 0.0, slot.position.z - 0.02))
        }
        val allocation = MorphAllocator.allocate(particles, slotList, MorphAllocation.NEAREST)
        for ((id, slotIndex) in allocation.slotOfParticle) {
            val index = id.leastSignificantBits - 1
            val from = particles[index.toInt()].position
            val to = slotList[slotIndex].position
            val distance = from.distanceTo(to)
            assertTrue(distance < 0.5, "particle $index assigned ${distance}m away")
        }
    }

    @Test
    fun `千级与万级粒子形变准备都在预算内`() {
        for (count in listOf(1_024, 10_000)) {
            val slots = gridSlots(count)
            val particles = slots.mapIndexed { index, slot ->
                particle(index, Vec3(slot.position.x + 1.0, slot.position.y, slot.position.z))
            }
            val started = System.nanoTime()
            val runtime = MorphRuntime(particles, slots, MorphOptions(ticks = 10))
            val setupMs = (System.nanoTime() - started) / 1_000_000.0
            val warmStarted = System.nanoTime()
            MorphRuntime(particles, slots, MorphOptions(ticks = 10))
            val warmMs = (System.nanoTime() - warmStarted) / 1_000_000.0
            val activeStarted = System.nanoTime()
            repeat(10) { runtime.tick(1.0) }
            val activeMs = (System.nanoTime() - activeStarted) / 1_000_000.0
            assertTrue(runtime.isDone())
            val idleStarted = System.nanoTime()
            repeat(1_000) { runtime.tick(1.0) }
            val idleMs = (System.nanoTime() - idleStarted) / 1_000_000.0
            System.out.println(
                "[PD-PERF] $count: setup ${setupMs}ms (warm ${warmMs}ms), " +
                    "10 active ticks ${activeMs}ms, 1000 idle ticks ${idleMs}ms",
            )
            assertTrue(setupMs < if (count <= 1_024) 500.0 else 3_000.0, "$count setup took ${setupMs}ms")
            assertTrue(activeMs < if (count <= 1_024) 200.0 else 2_000.0, "$count active ticks took ${activeMs}ms")
            assertTrue(idleMs < 200.0, "$count idle ticks took ${idleMs}ms")
            assertTrue(!runtime.tick(1.0), "completed non-keep-velocity morph should stop ticking")
        }
    }
}
