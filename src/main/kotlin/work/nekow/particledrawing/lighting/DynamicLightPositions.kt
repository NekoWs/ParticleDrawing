package work.nekow.particledrawing.lighting

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import java.util.concurrent.ConcurrentHashMap

/**
 * 动态光源位置管理器。
 * 线程安全地追踪所有被动态光源占据的方块位置。
 */
@Suppress("unused")
object DynamicLightPositions {

    private val POSITIONS = ConcurrentHashMap.newKeySet<BlockPos>()

    /** 注册光源位置 */
    fun add(pos: BlockPos) { POSITIONS.add(pos.immutable()) }
    /** 移除光源位置 */
    fun remove(pos: BlockPos) { POSITIONS.remove(pos) }
    /** 检查位置是否被动态光源占据 */
    fun contains(pos: BlockPos): Boolean = POSITIONS.contains(pos)
    /** 获取所有被占据的位置集合 */
    fun all(): Set<BlockPos> = POSITIONS.toSet()

    /**
     * 清除所有动态光源并恢复为空气。
     * @param level 服务端世界实例
     */
    fun clearAll(level: ServerLevel) {
        for (pos in POSITIONS) {
            try {
                if (level.hasChunk(pos.x shr 4, pos.z shr 4) && level.getBlockState(pos).`is`(Blocks.LIGHT)) {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL)
                }
            } catch (ignored: Exception) {
            }
        }
        POSITIONS.clear()
    }
}
