package work.nekow.particledrawing.lighting;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks all dynamic light block positions across game sessions.
 * Positions are NOT persisted to disk — they're rebuilt each session.
 */
public final class DynamicLightPositions {

    private static final Set<BlockPos> POSITIONS = ConcurrentHashMap.newKeySet();

    static void add(BlockPos pos) { POSITIONS.add(pos.immutable()); }
    static void remove(BlockPos pos) { POSITIONS.remove(pos); }
    static boolean contains(BlockPos pos) { return POSITIONS.contains(pos); }
    static Set<BlockPos> all() { return Collections.unmodifiableSet(POSITIONS); }

    /**
     * Remove ALL light blocks from the world. Called on shutdown/load.
     */
    public static void clearAll(ServerLevel level) {
        for (BlockPos pos : POSITIONS) {
            try {
                if (level.hasChunkAt(pos) && level.getBlockState(pos).is(Blocks.LIGHT)) {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                }
            } catch (Exception ignored) {}
        }
        POSITIONS.clear();
    }

    private DynamicLightPositions() {}
}
