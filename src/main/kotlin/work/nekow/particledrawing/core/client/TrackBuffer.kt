package work.nekow.particledrawing.core.client

import net.minecraft.world.phys.Vec3

/**
 * 直设位置（track）的逐 tick 插值缓冲。
 *
 * 权威位置按到达顺序排队，每个客户端 tick 只消费一个：桥接粒子的插值端点
 * （xo = [previous]、x = [current]）因此始终是一对相邻的权威位置，原版按 partialTick 扫一次。
 *
 * 该 tick 没有新位置时 [advance] 返回 false，调用方把端点原地保持（xo = x = [current]）；
 * 照旧再扫一遍同一段会在 tick 边界先退回起点，高速目标上能看到前后瞬移。
 * 缓冲满了丢最旧的一条，把落后量钉在上限内。
 *
 * 队列由网络线程 [offer]、客户端主线程 [advance] 与 [clear] 操作，两者都加锁；
 * [current] 与 [previous] 只在 [advance] 里写、只在客户端主线程读。
 */
class TrackBuffer(private val maxQueued: Int = 4) {

    private val queue = ArrayDeque<Vec3>()

    /** 最近一次消费的权威位置，渲染端点 x。 */
    var current: Vec3? = null
        private set

    /** 上一条已消费的权威位置，渲染端点 xo；为 null 表示还没消费过，插值起点用粒子当前位置。 */
    var previous: Vec3? = null
        private set

    /** 待消费的权威位置条数。 */
    @Synchronized
    fun queued(): Int = queue.size

    /** 收下一条权威位置；超过上限丢最旧的一条。 */
    @Synchronized
    fun offer(pos: Vec3) {
        if (queue.size >= maxQueued) queue.removeFirst()
        queue.addLast(pos)
    }

    /** 消费一条权威位置；返回 false 表示本 tick 没有新位置。 */
    @Synchronized
    fun advance(): Boolean {
        val next = queue.removeFirstOrNull() ?: return false
        previous = current
        current = next
        return true
    }

    @Synchronized
    fun clear() {
        queue.clear()
        previous = null
        current = null
    }
}
