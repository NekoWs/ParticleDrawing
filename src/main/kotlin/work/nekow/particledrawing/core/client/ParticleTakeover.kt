package work.nekow.particledrawing.core.client

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 粒子的接管标记：位置接管与外观接管各记一份，不能共用一个标记。
 *
 * - 位置接管（`track`/`trackAll`、实体锚点、动画与程序的直写）：引擎的分批缓动轮转与速度积分让位；
 * - 外观接管（动画与程序的直写颜色或缩放）：寿命曲线的逐帧刷新让位。
 *
 * `track` 只接管位置，外观仍由寿命曲线逐渲染帧驱动；两者若共用一个标记，被 track 接管的粒子
 * 会在 `frameSyncCurves` 里被一起跳过，尺寸曲线冻结在出生那一帧的乘数上。
 */
internal class ParticleTakeover {

    /** 位置由直写接管的粒子。 */
    private val positional: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    /** 颜色/缩放由直写接管的粒子。 */
    private val appearance: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    /** 只接管位置（track、实体锚点）。 */
    fun markPosition(id: UUID) {
        positional.add(id)
    }

    /** 接管位置与外观（动画与程序的完整直写）。 */
    fun markAppearance(id: UUID) {
        positional.add(id)
        appearance.add(id)
    }

    /** 交还接管权：缓动、速度与力重新接手，或粒子被销毁。 */
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
