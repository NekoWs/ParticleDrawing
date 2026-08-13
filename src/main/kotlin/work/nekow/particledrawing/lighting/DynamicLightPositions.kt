package work.nekow.particledrawing.lighting

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import work.nekow.particledrawing.util.ParticleUtils
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 动态光源位置管理器。
 * 按维度线程安全地追踪所有被动态光源占据的方块位置。
 */
@Suppress("unused")
object DynamicLightPositions {

    private val POSITIONS = ConcurrentHashMap<UUID, MutableSet<BlockPos>>()

    private fun forLevel(level: ServerLevel): MutableSet<BlockPos> =
        POSITIONS.computeIfAbsent(ParticleUtils.dimensionUUID(level)) { ConcurrentHashMap.newKeySet() }

    /** 注册光源位置 */
    fun add(level: ServerLevel, pos: BlockPos) { forLevel(level).add(pos.immutable()) }

    /** 移除光源位置 */
    fun remove(level: ServerLevel, pos: BlockPos) { forLevel(level).remove(pos) }

    /**
     * 清除指定维度的所有动态光源并恢复为空气。
     * @param level 服务端世界实例
     */
    fun clearAll(level: ServerLevel) {
        val positions = POSITIONS.remove(ParticleUtils.dimensionUUID(level)) ?: return
        for (pos in positions) {
            try {
                if (level.hasChunk(pos.x shr 4, pos.z shr 4) && level.getBlockState(pos).`is`(Blocks.LIGHT)) {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL)
                }
            } catch (ignored: Exception) {
            }
        }
    }
}
