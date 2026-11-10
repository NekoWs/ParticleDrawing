package work.nekow.particledrawing.core.server

import net.minecraft.world.phys.Vec3
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 粒子组数据，管理组成员列表和轴心。
 *
 * @param id 组唯一标识符
 * @param pivot 组轴心坐标
 */
@Suppress("unused")
class ParticleGroupData(
    val id: UUID,
    private var pivot: Vec3
) {

    private val memberIds: MutableList<UUID> = CopyOnWriteArrayList()

    fun memberIds(): List<UUID> = Collections.unmodifiableList(memberIds)
    fun size(): Int = memberIds.size
    fun pivot(): Vec3 = pivot

    fun setPivot(pivot: Vec3) {
        this.pivot = pivot
    }

    fun addMember(particleId: UUID) {
        if (!memberIds.contains(particleId)) {
            memberIds.add(particleId)
        }
    }

    fun removeMember(particleId: UUID) {
        memberIds.remove(particleId)
    }

    fun addMembers(ids: Collection<UUID>) {
        for (id in ids) {
            addMember(id)
        }
    }

    fun isEmpty(): Boolean = memberIds.isEmpty()

    companion object {
        fun create(id: UUID, pivot: Vec3): ParticleGroupData {
            return ParticleGroupData(id, pivot)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ParticleGroupData) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "ParticleGroupData{$id members=${memberIds.size}}"
}
