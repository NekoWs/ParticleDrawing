package work.nekow.particledrawing.core.server

import java.util.UUID
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import work.nekow.particledrawing.i18n.translation

// 服务端动画调度器：按 game tick 驱动延迟与循环任务。
// 所有方法只能在服务端主线程调用；队列无并发保护依赖这一前提。
object AnimationScheduler {

    private val LOGGER: Logger = LogManager.getLogger("ParticleDrawing")

    private var serverTick = 0L

    // 任务带维度标识：关卡卸载时只清这个维度的任务，别的维度刚排的编排不受影响
    private class Task(
        val dimensionId: UUID, val dueTick: Long, val order: Long, val owner: UUID?, val action: () -> Unit,
    )

    private var nextOrder = 0L
    private val queue = java.util.PriorityQueue(compareBy<Task> { it.dueTick }.thenBy { it.order })

    /** 当前服务端累计 tick（调度器启动以来）。 */
    @JvmStatic
    fun currentTick(): Long = serverTick

    /** 安排一个 [dimensionId] 维度内的延迟任务；delayTicks<=0 表示下一 tick 执行。 */
    @JvmStatic
    fun schedule(dimensionId: UUID, delayTicks: Int, action: () -> Unit) {
        enqueue(dimensionId, delayTicks, null, action)
    }

    internal fun scheduleOwned(dimensionId: UUID, delayTicks: Int, owner: UUID, action: () -> Unit) {
        enqueue(dimensionId, delayTicks, owner, action)
    }

    private fun enqueue(dimensionId: UUID, delayTicks: Int, owner: UUID?, action: () -> Unit) {
        queue.add(Task(dimensionId, serverTick + delayTicks.coerceAtLeast(1), nextOrder++, owner, action))
    }

    internal fun cancelOwned(owner: UUID) {
        queue.removeIf { it.owner == owner }
    }

    internal fun cancelOwned(owners: Set<UUID>) {
        queue.removeIf { it.owner in owners }
    }

    /** 服务端每 tick 推进：到期任务按先后出队执行。 */
    @JvmStatic
    fun tick() {
        serverTick++
        while (true) {
            val top = queue.peek() ?: break
            if (top.dueTick > serverTick) break
            queue.poll()
            try {
                top.action()
            } catch (e: Exception) {
                LOGGER.error(translation("log.scheduler.task_failed").resolve(), e)
            }
        }
    }

    /** 清掉 [dimensionId] 维度的待执行任务（关卡卸载时调用，别的维度不受影响）。 */
    @JvmStatic
    fun clearDimension(dimensionId: UUID) {
        queue.removeIf { it.dimensionId == dimensionId }
    }

    /** 清空全部待执行任务（服务器关闭时调用）。 */
    @JvmStatic
    fun clearAll() {
        queue.clear()
    }
}
