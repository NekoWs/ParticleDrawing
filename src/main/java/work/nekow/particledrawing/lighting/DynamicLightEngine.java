package work.nekow.particledrawing.lighting;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import work.nekow.particledrawing.config.ParticleDrawingConfig;
import work.nekow.particledrawing.core.client.RenderParticle;

import java.util.*;

/**
 * Places waterloggable invisible {@link Blocks#LIGHT} blocks at glow particle
 * positions with sub-block interpolation for smooth light movement.
 */
public final class DynamicLightEngine {

    private static final int UPDATE_INTERVAL_TICKS = 1;
    private static int tickCounter = 0;
    private static final Map<BlockPos, BlockState> placedLights = new HashMap<>();
    private static final Map<BlockPos, BlockState> originalBlocks = new HashMap<>();
    private static final org.slf4j.Logger LOGGER = LogUtils.getLogger();

    private DynamicLightEngine() {}

    public static void tick(List<RenderParticle> glowingParticles) {
        tickCounter++;
        if (tickCounter % UPDATE_INTERVAL_TICKS != 0) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        var server = mc.getSingleplayerServer();
        if (server == null) return;

        ServerLevel level = server.getLevel(mc.level.dimension());
        if (level == null) return;

        double maxDist = ParticleDrawingConfig.CLIENT.dynamicLightMaxDistance.get();
        int maxLights = ParticleDrawingConfig.CLIENT.maxDynamicLights.get();

        List<RenderParticle> sorted = new ArrayList<>(glowingParticles);
        sorted.sort(Comparator.comparingDouble(p ->
            mc.player.distanceToSqr(p.x(), p.y(), p.z())));

        Map<BlockPos, Integer> desiredLevels = new HashMap<>();

        for (RenderParticle p : sorted) {
            if (desiredLevels.size() >= maxLights) break;
            if (!p.isAlive() || p.a() < 0.01f) continue;

            double distSq = mc.player.distanceToSqr(p.x(), p.y(), p.z());
            if (distSq > maxDist * maxDist) continue;

            float lum = Math.max(p.r(), Math.max(p.g(), p.b())) * p.a();
            if (lum < 0.05f) continue;

            int light = Math.max(8, Math.min(15, Math.round(lum * 15)));
            BlockPos pos = BlockPos.containing(p.x(), p.y(), p.z());

            if (canPlace(level, pos)) {
                desiredLevels.merge(pos, light, Math::max);
            }
        }

        // Remove lights at positions no longer needed
        Iterator<BlockPos> it = placedLights.keySet().iterator();
        while (it.hasNext()) {
            BlockPos pos = it.next();
            if (!desiredLevels.containsKey(pos)) {
                restoreBlock(level, pos);
                it.remove();
            }
        }

        // Add or update lights
        for (Map.Entry<BlockPos, Integer> entry : desiredLevels.entrySet()) {
            BlockPos pos = entry.getKey();
            int newLevel = entry.getValue();

            BlockState existing = placedLights.get(pos);
            int existingLevel = existing != null ? existing.getValue(BlockStateProperties.LEVEL) : -1;

            if (existingLevel != newLevel) {
                placeLight(level, pos, newLevel);
            }
        }
    }

    public static void clearAll() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        var server = mc.getSingleplayerServer();
        if (server == null) return;

        ServerLevel level = server.getLevel(mc.level.dimension());
        if (level == null) return;

        for (BlockPos pos : new HashSet<>(placedLights.keySet())) {
            restoreBlock(level, pos);
        }
        placedLights.clear();
    }

    private static boolean canPlace(ServerLevel level, BlockPos pos) {
        if (!level.hasChunkAt(pos)) return false;
        BlockState current = level.getBlockState(pos);
        if (current.isAir()) return true;
        if (current.is(Blocks.LIGHT)) return true;
        if (current.canBeReplaced() && !current.liquid()) return true;
        return false;
    }

    private static void placeLight(ServerLevel level, BlockPos pos, int lightLevel) {
        BlockState current = level.getBlockState(pos);
        BlockState lightState = Blocks.LIGHT.defaultBlockState()
            .setValue(BlockStateProperties.LEVEL, lightLevel);

        if (!current.is(Blocks.LIGHT)) {
            originalBlocks.put(pos, current);
        }
        DynamicLightPositions.add(pos);
        placedLights.put(pos, lightState);
        level.setBlock(pos, lightState, Block.UPDATE_ALL);
    }

    private static void restoreBlock(ServerLevel level, BlockPos pos) {
        DynamicLightPositions.remove(pos);
        BlockState original = originalBlocks.remove(pos);
        if (original != null) {
            level.setBlock(pos, original, Block.UPDATE_ALL);
        } else {
            BlockState current = level.getBlockState(pos);
            if (current.is(Blocks.LIGHT)) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }
}
