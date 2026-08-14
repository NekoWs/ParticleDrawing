package work.nekow.particledrawing.api

import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.core.server.ServerParticleEngine
import work.nekow.particledrawing.util.ParticleUtils
import java.util.UUID

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

    internal fun getPlayers(): Collection<ServerPlayer> = level.players()

    companion object {
        fun of(level: ServerLevel) = ParticleManager(level)

        fun of(level: Level): ParticleManager {
            if (level !is ServerLevel) {
                throw IllegalArgumentException("ParticleManager requires a ServerLevel")
            }
            return ParticleManager(level)
        }
    }
}
