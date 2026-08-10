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

@Suppress("unused")
object DynamicLightEngine {

    private var tickCounter = 0
    private val placedLights = HashMap<BlockPos, BlockState>()
    private val originalBlocks = HashMap<BlockPos, BlockState>()

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

            val light = Math.round(lum * 15).toInt().coerceIn(8, 15)
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

    @Suppress("deprecation")
    private fun canPlace(level: ServerLevel, pos: BlockPos): Boolean {
        if (!level.hasChunkAt(pos)) return false
        val current = level.getBlockState(pos)
        if (current.isAir) return true
        if (current.`is`(Blocks.LIGHT)) return true
        return current.canBeReplaced() && current.fluidState.isEmpty
    }

    private fun placeLight(level: ServerLevel, pos: BlockPos, lightLevel: Int) {
        val current = level.getBlockState(pos)
        val lightState = Blocks.LIGHT.defaultBlockState()
            .setValue(BlockStateProperties.LEVEL, lightLevel)

        if (!current.`is`(Blocks.LIGHT)) {
            originalBlocks[pos] = current
        }
        DynamicLightPositions.add(pos)
        placedLights[pos] = lightState
        level.setBlock(pos, lightState, Block.UPDATE_ALL)
    }

    private fun restoreBlock(level: ServerLevel, pos: BlockPos) {
        DynamicLightPositions.remove(pos)
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
