package work.nekow.particledrawing.api

import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.core.server.ServerParticleEngine
import work.nekow.particledrawing.util.ParticleUtils
import java.util.*

/**
 * 创建和管理粒子的入口点。
 */
@Suppress("unused")
class ParticleManager private constructor(val level: ServerLevel) {

    val dimensionId: UUID = ParticleUtils.dimensionUUID(level)

    init {
        ServerParticleEngine.getOrCreate(dimensionId)
    }

    /**
     * 创建一个新的粒子构建器。
     */
    fun create() = ParticleHandle.Builder(this)

    /**
     * 创建一个新的空白粒子组。
     * @param pivot 组的基准点
     * @return 新创建的粒子组
     */
    fun createGroup(pivot: Vec3): ParticleGroup {
        val groupId = UUID.randomUUID()
        val engine = getEngine()
        engine.createGroup(groupId, pivot)
        return ParticleGroup(groupId, pivot, this)
    }

    /** [createGroup] 的分量重载。 */
    fun createGroup(x: Number, y: Number, z: Number): ParticleGroup {
        return createGroup(Vec3(x.toDouble(), y.toDouble(), z.toDouble()))
    }

    /**
     * 获取一个已存在的粒子组。
     * @param groupId 粒子组 UUID
     * @return 粒子组，不存在则返回 null
     */
    fun getGroup(groupId: UUID): ParticleGroup? {
        val engine = getEngine()
        val groupData = engine.getGroup(groupId) ?: return null
        return ParticleGroup(groupId, groupData.pivot(), this)
    }

    fun getEngine() = ServerParticleEngine.getOrCreate(dimensionId)

    /**
     * 批量直设位置（无缓动）：一个包覆盖多颗粒子，语义与 [ParticleHandle.track] 相同
     * （客户端每个 tick 消费一条，按 partialTick 在相邻两条之间插值）。
     * [ids] 与 [positions] 按顺序一一对应，长度不同按短的一方截断，不存在的粒子跳过。
     *
     * @param ids 粒子 ID 列表
     * @param positions 与 [ids] 一一对应的目标位置
     * @return 服务端实际生效的粒子数
     */
    fun trackAll(ids: List<UUID>, positions: List<Vec3>): Int {
        if (ids.isEmpty() || positions.isEmpty()) return 0
        return getEngine().trackParticles(ids, positions, getPlayers())
    }

    /**
     * 批量设置速度（blocks/tick）：一个包覆盖多颗粒子，语义与 [ParticleHandle.setVelocity] 相同
     * （速度驱动接管位置、解除实体锚点，之后两端按同一规则逐 tick 积分，位置不由调用方下发）。
     * [ids] 与 [velocities] 按顺序一一对应，长度不同按短的一方截断，不存在的粒子跳过。
     *
     * @param ids 粒子 ID 列表
     * @param velocities 与 [ids] 一一对应的速度
     * @return 服务端实际生效的粒子数
     */
    fun setVelocityAll(ids: List<UUID>, velocities: List<Vec3>): Int {
        if (ids.isEmpty() || velocities.isEmpty()) return 0
        return getEngine().setVelocities(ids, velocities, getPlayers())
    }

    /**
     * 批量施力：一个包覆盖多颗粒子，每颗粒子各自的加速度、共用一个 [ticks]。
     * 语义与 [ParticleHandle.applyForce] 相同（力驱动接管位置、解除实体锚点、两端逐 tick 积分）。
     *
     * @param ids 粒子 ID 列表
     * @param accelerations 与 [ids] 一一对应的加速度（blocks/tick²）
     * @param ticks 施力 tick 数：>0 = 这么多 tick；<0 = 无限；0 = 清除
     * @return 服务端实际生效的粒子数
     */
    fun applyForceAll(ids: List<UUID>, accelerations: List<Vec3>, ticks: Int = 1): Int {
        if (ids.isEmpty() || accelerations.isEmpty()) return 0
        return getEngine().applyForces(ids, accelerations, ticks, getPlayers())
    }

    internal fun getPlayers(): Collection<ServerPlayer> = level.players()

    companion object {
        @JvmStatic
        fun of(level: ServerLevel) = ParticleManager(level)

        @JvmStatic
        fun of(level: Level): ParticleManager {
            if (level !is ServerLevel) {
                throw IllegalArgumentException("ParticleManager requires a ServerLevel")
            }
            return ParticleManager(level)
        }
    }
}
