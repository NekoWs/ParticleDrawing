package work.nekow.particledrawing.core.motion

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.core.client.BridgeParticle
import work.nekow.particledrawing.core.client.RenderParticle
import work.nekow.particledrawing.core.motion.algorithms.ColorGradientAlgorithm
import work.nekow.particledrawing.core.motion.algorithms.FollowPlayerAlgorithm
import work.nekow.particledrawing.core.motion.algorithms.RotateAlgorithm
import work.nekow.particledrawing.core.motion.algorithms.ScaleByDistanceAlgorithm
import work.nekow.particledrawing.core.motion.algorithms.SwirlAlgorithm
import work.nekow.particledrawing.core.motion.algorithms.VortexAlgorithm
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Suppress("unused")
object MotionSystem {

    private val algorithms: MutableMap<String, MotionAlgorithm.Factory> = ConcurrentHashMap()

    init {
        register(RotateAlgorithm.ID, ::RotateAlgorithm)
        register(ColorGradientAlgorithm.ID, ::ColorGradientAlgorithm)
        register(FollowPlayerAlgorithm.ID, ::FollowPlayerAlgorithm)
        register(ScaleByDistanceAlgorithm.ID, ::ScaleByDistanceAlgorithm)
        register(SwirlAlgorithm.ID, ::SwirlAlgorithm)
        register(VortexAlgorithm.ID, ::VortexAlgorithm)
    }

    fun register(id: String, factory: MotionAlgorithm.Factory) { algorithms[id] = factory }

    private data class GroupMotion(
        var pivot: Vec3,
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
        group.motions.add(factory(params))
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
            val s = (System.nanoTime() - group.startTimeNanos) / 1_000_000_000.0
            val members = groupMembers[groupId] ?: continue

            // 第一遍：收集需要更新 pivot 的算法
            val firstBase = group.basePositions.values.firstOrNull()
            for (motion in group.motions) {
                if (firstBase == null) break
                val r = motion.compute(firstBase, group.pivot, s)
                if (r.newPivot != null) group.pivot = r.newPivot
            }

            // 第二遍：逐粒子应用所有算法
            for (memberId in members) {
                val base = group.basePositions[memberId] ?: continue
                val rp = renderParticles[memberId] ?: continue
                val bp = bridges[memberId] ?: continue
                var curPos = base
                var curColor: Color? = null
                var curScale: Float? = null

                for (motion in group.motions) {
                    val r = motion.compute(curPos, group.pivot, s)
                    if (r.position != null) curPos = r.position
                    if (r.color != null) curColor = r.color
                    if (r.scale != null) curScale = r.scale
                }

                rp.setPositionDirect(curPos)
                if (curColor != null) rp.setColorDirect(curColor)
                if (curScale != null) rp.setScaleDirect(curScale)

                bp.syncPosition(curPos.x, curPos.y, curPos.z, snap = false)
                bp.syncColor(rp.r(), rp.g(), rp.b(), rp.a())
                bp.syncScale(rp.scale())
            }
        }
    }
}
