package work.nekow.particledrawing.core.network

import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.translation

/**
 * 批量载荷的拆包：条数超过单包上限时按原顺序切成若干段，每段不超过 [max]。
 *
 * 公开 API 对成员数没有上限，而载荷的 MAX_BATCH 是协议硬约束，超限的包会被客户端拒绝并断开连接。
 */
internal object BatchChunking {

    /** 把 [items] 按 [max] 切成若干段；空列表得到空结果。 */
    fun <T> chunks(items: List<T>, max: Int): List<List<T>> {
        requireLocalized(max > 0) { translation("error.packet.batch_size", max) }
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
