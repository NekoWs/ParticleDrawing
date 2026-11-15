package work.nekow.particledrawing.core.client

import net.minecraft.world.phys.Vec3

/**
 * 可移动锚点的相邻两个服务器样本。
 *
 * 服务端每 tick 报一条位置，渲染按 `lerp(prev, cur, partialTick)` 求值，与 `track` 粒子
 * 逐 tick 消费一条样本的相位一致。
 *
 * - 瞬移：一条样本跳得比 [TELEPORT_LIMIT] 还远，直接落到新位置；
 * - 断流：超过 [STALE_NANOS] 没有新样本，停在最后位置而不外推；
 * - 恢复：断流后的第一条样本按跳变处理，中间路径无从重建。
 */
internal class MovableAnchorSamples {

    private var prev: Vec3? = null
    private var cur: Vec3? = null
    private var lastSampleNanos: Long = 0L

    fun onSample(pos: Vec3, nowNanos: Long) {
        val c = cur
        val stale = nowNanos - lastSampleNanos > STALE_NANOS
        prev = when {
            c == null -> pos
            stale -> pos
            pos.distanceTo(c) > TELEPORT_LIMIT -> pos
            else -> c
        }
        cur = pos
        lastSampleNanos = nowNanos
    }

    /** 本帧的锚点位置；[partialTick] 为渲染帧在相邻两个 tick 之间的进度。 */
    fun resolve(partialTick: Float, nowNanos: Long): Vec3? {
        val c = cur ?: return null
        val p = prev ?: return c
        if (nowNanos - lastSampleNanos > STALE_NANOS) return c
        return Vec3(
            p.x + (c.x - p.x) * partialTick,
            p.y + (c.y - p.y) * partialTick,
            p.z + (c.z - p.z) * partialTick,
        )
    }

    companion object {
        /** 一条样本跳这么远（格）就按瞬移处理。 */
        const val TELEPORT_LIMIT = 8.0

        /** 多久（纳秒）没有新样本算断流，取 3 tick。 */
        const val STALE_NANOS = 150_000_000L
    }
}
