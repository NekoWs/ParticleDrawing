package work.nekow.particledrawing.core.motion

import net.minecraft.client.Minecraft
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

/**
 * 客户端帧级运动系统：注册、启动运动算法并每帧计算粒子的位置/颜色/缩放。
 */
@Suppress("unused")
object MotionSystem {

    private val algorithms: MutableMap<String, MotionAlgorithm.Factory> = ConcurrentHashMap()

    /** 目标点提供者（默认为本地玩家位置），可替换以泛化算法用途。 */
    @Volatile
    @JvmStatic
    var targetProvider: () -> Vec3? = { Minecraft.getInstance().player?.position() }
    init {
        register(RotateAlgorithm.ID, ::RotateAlgorithm)
        register(ColorGradientAlgorithm.ID, ::ColorGradientAlgorithm)
        register(FollowPlayerAlgorithm.ID, ::FollowPlayerAlgorithm)
        register(ScaleByDistanceAlgorithm.ID, ::ScaleByDistanceAlgorithm)
        register(SwirlAlgorithm.ID, ::SwirlAlgorithm)
        register(VortexAlgorithm.ID, ::VortexAlgorithm)
    }

    @JvmStatic
    fun register(id: String, factory: MotionAlgorithm.Factory) { algorithms[id] = factory }

    private class MotionInstance(val algorithm: MotionAlgorithm, val startTimeNanos: Long = System.nanoTime())

    private class GroupMotion(
        var pivot: Vec3,
        val basePositions: Map<UUID, Vec3>,
        val motions: MutableList<MotionInstance> = mutableListOf()
    )

    private val activeGroups: MutableMap<UUID, GroupMotion> = ConcurrentHashMap()

    @JvmStatic
    fun start(groupId: UUID, algoId: String, params: DoubleArray, pivot: Vec3,
              basePositions: Map<UUID, Vec3>) {
        val factory = algorithms[algoId] ?: return
        val group = activeGroups.getOrPut(groupId) { GroupMotion(pivot, basePositions) }
        group.motions.removeAll { it.algorithm.id == algoId }
        group.motions.add(MotionInstance(factory(params)))
    }

    @JvmStatic
    fun stop(groupId: UUID) { activeGroups.remove(groupId) }

    @JvmStatic
    fun clear() { activeGroups.clear() }

    @JvmStatic
    fun activeGroupIds(): Set<UUID> = activeGroups.keys

    @JvmStatic
    fun tick(
        groupMembers: Map<UUID, Set<UUID>>,
        renderParticles: Map<UUID, RenderParticle>,
        bridges: Map<UUID, BridgeParticle>
    ) {
        val target = targetProvider()
        val now = System.nanoTime()

        for ((groupId, group) in activeGroups) {
            val members = groupMembers[groupId] ?: continue
            val states = group.motions.map { it.algorithm to (now - it.startTimeNanos) / 1_000_000_000.0 }

            // 第一遍：更新 pivot（每个算法一次）
            for ((algorithm, s) in states) {
                group.pivot = algorithm.updatePivot(group.pivot, s, target)
            }

            // 第二遍：逐粒子应用所有算法
            for (memberId in members) {
                val base = group.basePositions[memberId] ?: continue
                val rp = renderParticles[memberId] ?: continue
                val bp = bridges[memberId] ?: continue
                var curPos = base
                var curColor: Color? = null
                var curScale: Float? = null

                for ((algorithm, s) in states) {
                    val r = algorithm.compute(curPos, group.pivot, s, target)
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
