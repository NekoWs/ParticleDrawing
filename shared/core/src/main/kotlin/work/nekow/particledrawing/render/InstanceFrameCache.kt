package work.nekow.particledrawing.render

/** 模拟刻内复用同一批成员。只有成员、贴图或采样状态变化时才要重新打包。 */
internal class InstanceFrameCache<T> {
    private val previous = arrayListOf<T>()
    private var tick = Long.MIN_VALUE
    private var textureVersion = 0

    fun needsPacking(tick: Long, textureVersion: Int, visible: List<T>, changed: Boolean): Boolean {
        if (!changed && this.tick == tick && this.textureVersion == textureVersion &&
            previous.size == visible.size && visible.indices.all { previous[it] === visible[it] }
        ) return false
        this.tick = tick
        this.textureVersion = textureVersion
        previous.clear()
        previous.addAll(visible)
        return true
    }

    fun clear() {
        previous.clear()
        tick = Long.MIN_VALUE
    }
}
