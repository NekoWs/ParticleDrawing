package work.nekow.particledrawing.lighting

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import java.util.concurrent.ConcurrentHashMap

@Suppress("unused")
object DynamicLightPositions {

    private val POSITIONS = ConcurrentHashMap.newKeySet<BlockPos>()

    fun add(pos: BlockPos) { POSITIONS.add(pos.immutable()) }
    fun remove(pos: BlockPos) { POSITIONS.remove(pos) }
    fun contains(pos: BlockPos): Boolean = POSITIONS.contains(pos)
    fun all(): Set<BlockPos> = POSITIONS.toSet()

    @Suppress("deprecation")
    fun clearAll(level: ServerLevel) {
        for (pos in POSITIONS) {
            try {
                if (level.hasChunkAt(pos) && level.getBlockState(pos).`is`(Blocks.LIGHT)) {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL)
                }
            } catch (ignored: Exception) {
            }
        }
        POSITIONS.clear()
    }
}
