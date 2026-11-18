package work.nekow.particledrawing.core.client

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 「哪些粒子在发光」的索引。
 *
 * 动态光照每帧只遍历这一批，不再扫描全部粒子；判定规则也只在这里写一次，
 * 免得出生、改光照等级、换光源形状几处各写一份条件后慢慢漂移。
 */
internal class ParticleLightIndex {

    private val ids: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    val isEmpty: Boolean get() = ids.isEmpty()

    val size: Int get() = ids.size

    operator fun contains(id: UUID): Boolean = ids.contains(id)

    /** 供遍历用；遍历期间集合可能被网络线程改动，调用方按快照语义使用。 */
    fun forEach(action: (UUID) -> Unit) {
        for (id in ids) action(id)
    }

    fun add(id: UUID) {
        ids.add(id)
    }

    fun remove(id: UUID) {
        ids.remove(id)
    }

    fun clear() {
        ids.clear()
    }

    /** 状态变化后维持索引；只有判定结果翻转才动集合。 */
    fun refresh(id: UUID, wasLight: Boolean, nowLight: Boolean) {
        if (wasLight == nowLight) return
        if (nowLight) ids.add(id) else ids.remove(id)
    }
}

/** 会发光的判定只有这一处：全亮或显式发光，且光照等级 > 0。 */
internal fun lightsUp(glowing: Boolean, emitsLight: Boolean, lightLevel: Int): Boolean =
    (glowing || emitsLight) && lightLevel > 0

internal fun lightsUp(particle: RenderParticle): Boolean =
    lightsUp(particle.glowing(), particle.emitsLight(), particle.lightLevel())
