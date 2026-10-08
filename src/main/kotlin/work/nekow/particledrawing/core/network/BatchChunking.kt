package work.nekow.particledrawing.core.network

/**
 * 批量载荷的拆包：条数超过单包上限时按**原顺序**切成若干段，每段不超过 [max]。
 *
 * 服务端发送端一律先过这里再发：公开 API（`ParticleBatch.setVelocityAll` 等）对成员数没有上限，
 * 而载荷的 `MAX_BATCH` 是协议硬约束——直接把 519 条塞进一个包，客户端解码会按上限拒绝并断开连接。
 * 拆包只影响「几个包」，不影响顺序、对应关系与最终状态。
 */
internal object BatchChunking {

    /** 把 [items] 按 [max] 切段；空列表得到空结果（调用方不必再判一次）。 */
    fun <T> chunks(items: List<T>, max: Int): List<List<T>> {
        require(max > 0) { "单包上限必须为正（$max）" }
        if (items.isEmpty()) return emptyList()
        if (items.size <= max) return listOf(items)
        val out = ArrayList<List<T>>((items.size + max - 1) / max)
        var i = 0
        while (i < items.size) {
            val end = minOf(i + max, items.size)
            out.add(ArrayList(items.subList(i, end)))
            i = end
        }
        return out
    }
}
