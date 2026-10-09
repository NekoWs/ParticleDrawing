package work.nekow.particledrawing.core.client

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 粒子的「接管」标记：**位置接管**与**外观接管**是两件事，不能共用一个标记。
 *
 * - **位置接管**（`track`/`trackAll`、实体锚点、动画与程序的直写）：引擎的分批缓动轮转与速度积分让位；
 * - **外观接管**（动画与程序的直写颜色/缩放）：寿命曲线的逐帧刷新让位。
 *
 * 分开的理由是 `track`：它只接管位置，外观仍该由寿命曲线逐渲染帧驱动。
 * 两者曾经共用 `directIds`，于是被 track 接管的粒子在 `frameSyncCurves` 里被一起跳过，
 * 尺寸曲线冻结在出生那一帧的乘数上（0→1 出生的方片永远长不起来）。
 */
internal class ParticleTakeover {

    /** 位置由直写接管的粒子。 */
    private val positional: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    /** 颜色/缩放由直写接管的粒子。 */
    private val appearance: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    /** 只接管位置（track / 实体锚点）。 */
    fun markPosition(id: UUID) {
        positional.add(id)
    }

    /** 接管位置与外观（动画/程序的完整直写）。 */
    fun markAppearance(id: UUID) {
        positional.add(id)
        appearance.add(id)
    }

    /** 交还接管权（缓动/速度/力重新接手，或粒子被销毁）。 */
    fun clear(id: UUID) {
        positional.remove(id)
        appearance.remove(id)
    }

    fun hasPosition(id: UUID): Boolean = id in positional

    fun hasAppearance(id: UUID): Boolean = id in appearance

    fun clearAll() {
        positional.clear()
        appearance.clear()
    }
}
