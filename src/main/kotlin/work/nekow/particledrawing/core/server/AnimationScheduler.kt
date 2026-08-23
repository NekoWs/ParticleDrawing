package work.nekow.particledrawing.core.server

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * 服务端动画调度器：按 game tick 执行延迟与循环任务，
 * 是编排式动画 API（[work.nekow.particledrawing.api.ParticleGroup] 的 delay /
 * fadeIn / spin / pulse 等）的驱动核心。
 *
 * 所有方法只能在服务端主线程调用（tick 事件 / 命令执行均满足）；
 * 队列无并发保护依赖这一前提。
 */
object AnimationScheduler {

    private val LOGGER: Logger = LogManager.getLogger("ParticleDrawing")

    private var serverTick = 0L

    private class Task(val dueTick: Long, val action: () -> Unit)

    private val queue = java.util.PriorityQueue<Task>(java.util.Comparator.comparingLong { it.dueTick })

    /** 当前服务端累计 tick（调度器启动以来）。 */
    @JvmStatic
    fun currentTick(): Long = serverTick

    /**
     * 安排一个延迟任务。
     * @param delayTicks 从现在起多少 tick 后执行，小于等于 0 表示下一 tick
     * @param action 到期执行的动作
     */
    @JvmStatic
    fun schedule(delayTicks: Int, action: () -> Unit) {
        queue.add(Task(serverTick + delayTicks.coerceAtLeast(1), action))
    }

    /** 服务端每 tick 推进：到期任务按先后出队执行（任务内可再入队）。 */
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
                LOGGER.error("AnimationScheduler task failed", e)
            }
        }
    }

    /** 清空全部待执行任务（维度卸载 / 服务器关闭时调用）。 */
    @JvmStatic
    fun clear() {
        queue.clear()
    }
}
