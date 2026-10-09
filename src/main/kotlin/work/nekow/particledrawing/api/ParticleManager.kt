package work.nekow.particledrawing.api

import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import net.neoforged.api.distmarker.Dist
import net.neoforged.fml.loading.FMLEnvironment
import work.nekow.particledrawing.core.TextureRegistry
import work.nekow.particledrawing.core.client.ClientTextureSyncManager
import work.nekow.particledrawing.core.network.EmitterParams
import work.nekow.particledrawing.core.server.ServerEmitterManager
import work.nekow.particledrawing.core.server.ServerParticleEngine
import work.nekow.particledrawing.core.server.TextureSyncService
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
     * 声明一个运行时发射器：服务端下发一次声明，客户端按渲染帧沿锚点推进并生成粒子。
     *
     * @param anchor 发射锚点（固定点 / 实体 / 可移动）
     */
    fun emitter(anchor: Anchor): ParticleEmitter = ParticleEmitter(this, anchor)

    /** [emitter] 的固定点重载。 */
    fun emitter(x: Number, y: Number, z: Number): ParticleEmitter =
        emitter(Anchor.Fixed(Vec3(x.toDouble(), y.toDouble(), z.toDouble())))

    /**
     * 批量生成一批粒子：整批用一个包下发，每颗的字段由 [ParticleSpawnSpec] 给。
     * 服务端按玩家可见性裁剪后下发，返回与 [specs] 一一对应的句柄；被维度上限拒绝的为 null。
     *
     * 长度按 [MAX_SPAWN_BATCH] 截断，返回值能看出哪些没生成。
     */
    fun spawnAll(specs: List<ParticleSpawnSpec>): List<ParticleHandle?> {
        if (specs.isEmpty()) return emptyList()
        val effective = if (specs.size > MAX_SPAWN_BATCH) specs.subList(0, MAX_SPAWN_BATCH) else specs
        return getEngine().spawnParticles(effective, getPlayers()).map { data ->
            data?.let { ParticleHandle(it.id, this) }
        }
    }

    // 发射器的服务端登记，由 ParticleEmitter / EmitterHandle 调用

    internal fun startEmitter(emitter: ParticleEmitter, anchor: Anchor, params: EmitterParams): EmitterHandle {
        // 达到上限时服务端拒绝登记并打 ERROR，句柄仍可用，但 isActive() 为 false
        val id = ServerEmitterManager.start(dimensionId, anchor, params, getPlayers()) ?: UUID.randomUUID()
        return EmitterHandle(id, this, emitter).init(anchor)
    }

    internal fun updateEmitterAnchor(id: UUID, anchor: Anchor) {
        ServerEmitterManager.updateAnchor(id, anchor, getPlayers())
    }

    internal fun updateEmitterCadence(id: UUID, mode: EmitMode, spacing: Double, intervalMs: Int) {
        ServerEmitterManager.updateCadence(id, mode, spacing, intervalMs, getPlayers())
    }

    internal fun updateEmitterParams(id: UUID, params: EmitterParams) {
        ServerEmitterManager.updateParams(id, params, getPlayers())
    }

    internal fun stopEmitter(id: UUID) {
        ServerEmitterManager.stop(id, getPlayers())
    }

    internal fun isEmitterActive(id: UUID): Boolean = ServerEmitterManager.isActive(id)

    /**
     * 创建一个新的空白粒子组。
     * @param pivot 组的基准点
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
     * 获取一个已存在的粒子组；不存在返回 null。
     * @param groupId 粒子组 UUID
     */
    fun getGroup(groupId: UUID): ParticleGroup? {
        val engine = getEngine()
        val groupData = engine.getGroup(groupId) ?: return null
        return ParticleGroup(groupId, groupData.pivot(), this)
    }

    fun getEngine() = ServerParticleEngine.getOrCreate(dimensionId)

    /**
     * 批量直设位置（无缓动）：一个包覆盖多颗粒子，语义与 [ParticleHandle.track] 相同，
     * 客户端每个 tick 消费一条，按 partialTick 在相邻两条之间插值。
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
     * 批量设置速度（blocks/tick）：一个包覆盖多颗粒子，语义与 [ParticleHandle.setVelocity] 相同，
     * 速度驱动接管位置、解除实体锚点，之后两端按同一规则逐 tick 积分。
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
     * 语义与 [ParticleHandle.applyForce] 相同，力驱动接管位置、解除实体锚点、两端逐 tick 积分。
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
        /** 单次 [spawnAll] 的条数上限；超出的规格不生成。 */
        const val MAX_SPAWN_BATCH = 256

        @JvmStatic
        fun of(level: ServerLevel) = ParticleManager(level)

        @JvmStatic
        fun of(level: Level): ParticleManager {
            if (level !is ServerLevel) {
                throw IllegalArgumentException("ParticleManager requires a ServerLevel")
            }
            return ParticleManager(level)
        }


        /**
         * 登记一张程序化粒子用的贴图（PNG 字节），可随时调用；幂等，同名重复登记按第一次的生效。
         * 登记后按名字引用（`manager.create().texture("mymod:glow")`），子矩形 UV 用 [ParticleHandle.Builder.uv]。
         *
         * 字节会自动同步到客户端：单机与自带客户端就地解码，专用服务器在玩家进服时补发。
         * 名字未知或没登记过的贴图，客户端渲染成纯白方块。
         *
         * @param name 贴图名，建议带自己的命名空间（≤256 字符）
         * @param pngBytes PNG 字节（≤1 MiB）
         * @return 名字/数据合法且已登记为 true；重复登记同名也是 true
         */
        @JvmStatic
        fun registerTexture(name: String, pngBytes: ByteArray): Boolean {
            val entry = TextureRegistry.register(name, pngBytes) ?: return false
            // 本机就是客户端（单机 / 自带客户端）：就地解码
            if (FMLEnvironment.getDist() == Dist.CLIENT) {
                ClientTextureSyncManager.loadLocal(entry.name, pngBytes)
            }
            // 服务端：推给在线玩家；还没进服的玩家由 TextureSyncService.sendAll 在进服时补
            TextureSyncService.broadcast(entry)
            return true
        }

        /**
         * 注册全部内置形状贴图（[ParticleStyle]）。
         *
         * 内置形状在第一次被引用时就地生成（客户端）或按固定 id 引用（两端约定），
         * 只在需要提前确认贴图存在时调用。
         */
        @JvmStatic
        fun registerBuiltinTextures() {
            if (FMLEnvironment.getDist() != Dist.CLIENT) return
            ClientTextureSyncManager.ensureBuiltins()
        }
    }
}
