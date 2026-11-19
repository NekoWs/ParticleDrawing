package work.nekow.particledrawing.core.client

/** 按刻取样的有界队列。溢出时把最旧的帧交给调用方。 */
internal class TickFrameQueue<T : Any>(private val capacity: Int, private val reserve: Int = 0) {
    private val pending = ArrayDeque<T>()
    private var started = false
    private var buffering = true

    init { require(reserve in 0 until capacity) }

    val size: Int get() = pending.size

    fun offer(frame: T): T? {
        val skipped = if (pending.size == capacity) pending.removeFirst() else null
        pending.addLast(frame)
        return skipped
    }

    /** 首帧立即播放。开始或断流后积攒备用帧，直到下一次耗尽。 */
    fun advance(drain: Boolean = false): T? {
        if (pending.isEmpty()) {
            buffering = true
            return null
        }
        if (started && buffering && !drain && pending.size <= reserve) return null
        buffering = !started && pending.size <= reserve && !drain
        started = true
        return pending.removeFirst()
    }

    fun clear() {
        pending.clear()
        started = false
        buffering = true
    }
}
