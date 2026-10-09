package work.nekow.particledrawing.core.client

// 桥接粒子的光照缓存策略：纯逻辑（便于单测），只管「这次查询能不能复用上次的结果」。

/**
 * 光照缓存的有效性判定。
 *
 * 缓存的是「方块光 + 动态光」合并后的光图坐标，所以只要光场或所在方块变了就必须重查：
 * - **动态光版本**变化（光源新增/移动/销毁/关掉）——立即失效，下一渲染帧就恢复正确亮度；
 * - **方块坐标**变化——位置变了，光照按方块算，自然要重查；
 * - **世界光照变化**（插/拆火把、昼夜、区块重载）没有可观察的版本号，
 *   用**分摊到每颗粒子身上的超时**兜住：每颗按自身 id 抖开到期时刻，避免同帧几万次查询。
 */
internal object LightCachePolicy {

    /** 世界光照的兜底重采样周期（纳秒）：1 秒内一定跟上一次方块光变化。 */
    const val REFRESH_NANOS = 1_000_000_000L

    /** 发光状态变了必须立即重查（发光与非发光走不同光照路径）。 */
    fun shouldQuery(
        hasCache: Boolean,
        blockChanged: Boolean,
        lightVersionChanged: Boolean,
        dueNanos: Long,
        nowNanos: Long,
    ): Boolean = !hasCache || blockChanged || lightVersionChanged || nowNanos >= dueNanos

    /**
     * 把重采样时刻按粒子身份抖开，落在 `[REFRESH_NANOS, 2 × REFRESH_NANOS)` 内：
     * 同批生成的粒子不会在同一帧一起重查，稳定态下的查询量是均摊的。
     */
    fun nextDueNanos(nowNanos: Long, idHash: Int): Long =
        nowNanos + REFRESH_NANOS + (jitterNanos(idHash) % REFRESH_NANOS)

    /** [0, REFRESH_NANOS) 内的抖动量。 */
    fun jitterNanos(idHash: Int): Long {
        val positive = idHash.toLong() and 0xFFFFFFFFL
        return positive * REFRESH_NANOS / 0x100000000L
    }
}
