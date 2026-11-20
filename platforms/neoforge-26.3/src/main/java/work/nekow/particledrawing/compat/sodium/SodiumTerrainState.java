package work.nekow.particledrawing.compat.sodium;

import net.minecraft.core.BlockPos;

/**
 * 记录区块构建线程当前正在处理的方块位置。
 *
 * Sodium 的顶点颜色钩子只能拿到颜色值，拿不到方块坐标，所以由 BlockRenderer 在渲染每个方块
 * 前把坐标放进线程本地变量，顶点上色时再取出来算动态光。
 */
public final class SodiumTerrainState {
    private static final ThreadLocal<BlockPos> CURRENT = new ThreadLocal<>();

    private SodiumTerrainState() {
    }

    public static void push(BlockPos pos) {
        CURRENT.set(pos.immutable());
    }

    public static void pop() {
        CURRENT.remove();
    }

    public static BlockPos current() {
        return CURRENT.get();
    }
}

