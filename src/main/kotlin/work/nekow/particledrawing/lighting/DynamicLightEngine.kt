package work.nekow.particledrawing.lighting

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import work.nekow.particledrawing.config.ParticleDrawingConfig
import work.nekow.particledrawing.core.client.RenderParticle
import kotlin.math.roundToInt

/**
 * 动态光源引擎：根据发光粒子放置/移除光源方块。
 */
@Suppress("unused")
object DynamicLightEngine {

    private var tickCounter = 0
    private val placedLights = HashMap<BlockPos, BlockState>()
    private val originalBlocks = HashMap<BlockPos, BlockState>()

    /**
     * 每 tick 根据发光粒子更新光源方块。
     * @param glowingParticles 当前活跃的发光粒子列表
     */
    fun tick(glowingParticles: List<RenderParticle>) {
        tickCounter++

        val mc = Minecraft.getInstance()
        if (mc.level == null || mc.player == null) return

        val server = mc.singleplayerServer ?: return

        val level = server.getLevel(mc.level!!.dimension()) ?: return

        val maxDist = ParticleDrawingConfig.CLIENT.dynamicLightMaxDistance.get()
        val maxLights = ParticleDrawingConfig.CLIENT.maxDynamicLights.get()
        val player = mc.player!!

        val sorted = glowingParticles.sortedBy { player.distanceToSqr(it.x(), it.y(), it.z()) }

        val desiredLevels = HashMap<BlockPos, Int>()

        for (p in sorted) {
            if (desiredLevels.size >= maxLights) break
            if (!p.isAlive() || p.a() < 0.01f) continue

            val distSq = player.distanceToSqr(p.x(), p.y(), p.z())
            if (distSq > maxDist * maxDist) continue

            val lum = maxOf(p.r(), maxOf(p.g(), p.b())) * p.a()
            if (lum < 0.05f) continue

            val light = (lum * 15).roundToInt().coerceIn(8, 15)
            val pos = BlockPos.containing(p.x(), p.y(), p.z())

            if (canPlace(level, pos)) {
                desiredLevels.merge(pos, light) { a, b -> maxOf(a, b) }
            }
        }

        val it = placedLights.keys.iterator()
        while (it.hasNext()) {
            val pos = it.next()
            if (!desiredLevels.containsKey(pos)) {
                restoreBlock(level, pos)
                it.remove()
            }
        }

        for ((pos, newLevel) in desiredLevels) {
            val existing = placedLights[pos]
            val existingLevel = existing?.getValue(BlockStateProperties.LEVEL) ?: -1

            if (existingLevel != newLevel) {
                placeLight(level, pos, newLevel)
            }
        }
    }

    /**
     * 清除所有已放置的动态光源，恢复原始方块。
     */
    fun clearAll() {
        val mc = Minecraft.getInstance()
        if (mc.level == null) return

        val server = mc.singleplayerServer ?: return

        val level = server.getLevel(mc.level!!.dimension()) ?: return

        for (pos in HashSet(placedLights.keys)) {
            restoreBlock(level, pos)
        }
        placedLights.clear()
    }

    /**
     * 检查指定位置是否可以放置光源方块。
     * @param level 服务端世界实例
     * @param pos 目标方块位置
     * @return 如果可以放置则返回 true
     */
    private fun canPlace(level: ServerLevel, pos: BlockPos): Boolean {
        if (!level.hasChunk(pos.x shr 4, pos.z shr 4)) return false
        val current = level.getBlockState(pos)
        if (current.isAir) return true
        if (current.`is`(Blocks.LIGHT)) return true
        return current.canBeReplaced() && current.fluidState.isEmpty
    }

    /**
     * 在指定位置放置指定亮度的光源方块。
     * 保存原始方块以便后续恢复。
     * @param level 服务端世界实例
     * @param pos 目标方块位置
     * @param lightLevel 光源亮度等级 (0-15)
     */
    private fun placeLight(level: ServerLevel, pos: BlockPos, lightLevel: Int) {
        val current = level.getBlockState(pos)
        val lightState = Blocks.LIGHT.defaultBlockState()
            .setValue(BlockStateProperties.LEVEL, lightLevel)

        if (!current.`is`(Blocks.LIGHT)) {
            originalBlocks[pos] = current
        }
        DynamicLightPositions.add(level, pos)
        placedLights[pos] = lightState
        level.setBlock(pos, lightState, Block.UPDATE_ALL)
    }

    /**
     * 恢复指定位置的原始方块。
     * @param level 服务端世界实例
     * @param pos 目标方块位置
     */
    private fun restoreBlock(level: ServerLevel, pos: BlockPos) {
        DynamicLightPositions.remove(level, pos)
        val original = originalBlocks.remove(pos)
        if (original != null) {
            level.setBlock(pos, original, Block.UPDATE_ALL)
        } else {
            val current = level.getBlockState(pos)
            if (current.`is`(Blocks.LIGHT)) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL)
            }
        }
    }
}
