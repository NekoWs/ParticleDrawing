package work.nekow.particledrawing.lighting;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import work.nekow.particledrawing.config.ParticleDrawingConfig;
import work.nekow.particledrawing.core.client.RenderParticle;

import java.util.*;

/**
 * Places invisible {@link Blocks#LIGHT} blocks at glow particle positions
 * and removes them when particles move or disappear.
 * This approach works because light blocks emit actual block light that
 * propagates through the vanilla light engine natively.
 */
public final class DynamicLightEngine {

    private static final int UPDATE_INTERVAL_TICKS = 2;
    private static int tickCounter = 0;
    private static final Map<BlockPos, BlockState> placedLights = new HashMap<>();
    private static final Map<BlockPos, BlockState> previousBlocks = new HashMap<>();

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

        Set<BlockPos> newLightPositions = new HashSet<>();
        int count = 0;

        for (RenderParticle p : sorted) {
            if (count >= maxLights) break;
            if (!p.isAlive() || p.a() < 0.01f) continue;

            double distSq = mc.player.distanceToSqr(p.x(), p.y(), p.z());
            if (distSq > maxDist * maxDist) continue;

            float lum = Math.max(p.r(), Math.max(p.g(), p.b())) * p.a();
            if (lum < 0.05f) continue;

            int lightLevel = Math.max(8, Math.min(15, Math.round(lum * 15)));
            BlockPos pos = BlockPos.containing(p.x(), p.y(), p.z());

            if (canPlaceLight(level, pos)) {
                newLightPositions.add(pos);
                Integer oldLevel = placedLights.containsKey(pos)
                    ? placedLights.get(pos).getValue(BlockStateProperties.LEVEL) : null;

                if (oldLevel == null || oldLevel != lightLevel) {
                    BlockState lightState = Blocks.LIGHT.defaultBlockState()
                        .setValue(BlockStateProperties.LEVEL, lightLevel);
                    placedLights.put(pos, lightState);
                    previousBlocks.putIfAbsent(pos, level.getBlockState(pos));
                    level.setBlock(pos, lightState, 3);
                }
            }
            count++;
        }

        // Remove lights from positions that no longer have a glow particle
        Iterator<Map.Entry<BlockPos, BlockState>> it = placedLights.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, BlockState> entry = it.next();
            BlockPos pos = entry.getKey();
            if (!newLightPositions.contains(pos)) {
                BlockState original = previousBlocks.remove(pos);
                level.setBlock(pos, original != null ? original : Blocks.AIR.defaultBlockState(), 3);
                it.remove();
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

        for (Map.Entry<BlockPos, BlockState> entry : placedLights.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState original = previousBlocks.remove(pos);
            level.setBlock(pos, original != null ? original : Blocks.AIR.defaultBlockState(), 3);
        }
        placedLights.clear();
    }

    private static boolean canPlaceLight(ServerLevel level, BlockPos pos) {
        if (!level.hasChunkAt(pos)) return false;
        BlockState current = level.getBlockState(pos);
        // Place light in air, water, or replaceable blocks like grass/snow
        if (current.isAir()) return true;
        if (current.canBeReplaced()) return true;
        // Also allow replacing our own existing light blocks (to change level)
        if (current.is(Blocks.LIGHT)) return true;
        return false;
    }
}
