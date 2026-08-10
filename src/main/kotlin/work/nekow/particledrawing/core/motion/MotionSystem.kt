package work.nekow.particledrawing.core.motion

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.core.client.BridgeParticle
import work.nekow.particledrawing.core.client.RenderParticle
import work.nekow.particledrawing.core.motion.algorithms.ColorByYAlgorithm
import work.nekow.particledrawing.core.motion.algorithms.RotateAlgorithm
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 客户端运动系统。基于实际耗时驱动，不受 /tick 影响。
 *
 * 用法:
 *   MotionSystem.start(groupId, "rotate", params, pivot, basePositions) // 服务端触发
 *   MotionSystem.tick(groups, particles, bridges)                       // 每帧调用
 */
object MotionSystem {

    private val algorithms: MutableMap<String, MotionAlgorithm.Factory> = ConcurrentHashMap()

    init {
        register(RotateAlgorithm.Factory)
        register(ColorByYAlgorithm.Factory)
    }

    fun register(factory: MotionAlgorithm.Factory) { algorithms[factory.id] = factory }

    private data class GroupMotion(
        val pivot: Vec3,
        val basePositions: Map<UUID, Vec3>,
        val motions: MutableList<MotionAlgorithm> = mutableListOf(),
        val startTimeNanos: Long = System.nanoTime()
    )

    private val activeGroups: MutableMap<UUID, GroupMotion> = ConcurrentHashMap()

    fun start(groupId: UUID, algoId: String, params: DoubleArray, pivot: Vec3,
              basePositions: Map<UUID, Vec3>) {
        val factory = algorithms[algoId] ?: return
        val group = activeGroups.getOrPut(groupId) { GroupMotion(pivot, basePositions) }
        group.motions.removeAll { it.id == algoId }
        group.motions.add(factory.create(params))
    }

    fun stop(groupId: UUID) { activeGroups.remove(groupId) }
    fun clear() { activeGroups.clear() }
    fun activeGroupIds(): Set<UUID> = activeGroups.keys

    fun tick(
        groupMembers: Map<UUID, Set<UUID>>,
        renderParticles: Map<UUID, RenderParticle>,
        bridges: Map<UUID, BridgeParticle>
    ) {
        for ((groupId, group) in activeGroups) {
            val elapsedSeconds = (System.nanoTime() - group.startTimeNanos) / 1_000_000_000.0
            val members = groupMembers[groupId] ?: continue

            for (memberId in members) {
                val base = group.basePositions[memberId] ?: continue
                val rp = renderParticles[memberId] ?: continue
                val bp = bridges[memberId] ?: continue

                var curPos = base
                var curColor: Color? = null

                for (motion in group.motions) {
                    val (newPos, newColor) = motion.compute(curPos, group.pivot, elapsedSeconds)
                    if (newPos != null) curPos = newPos
                    if (newColor != null) curColor = newColor
                }

                rp.setPositionDirect(curPos)
                if (curColor != null) rp.setColorDirect(curColor)

                bp.syncPosition(curPos.x, curPos.y, curPos.z, snap = false)
                bp.syncColor(rp.r(), rp.g(), rp.b(), rp.a())
                bp.syncScale(rp.scale())
            }
        }
    }
}
