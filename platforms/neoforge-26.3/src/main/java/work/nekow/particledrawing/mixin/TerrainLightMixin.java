package work.nekow.particledrawing.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import work.nekow.particledrawing.lighting.DynamicLightManager;

// 地形烘焙的动态光照注入点（26.x）。
//
// 为什么挂在内部缓存类上：BlockModelLighter.getLightCoords 只是转发给 Cache.getLightCoords，
// 而真正逐顶点取光的是 prepareQuad* 直接调缓存。挂在外面那层一次都不会被调用（实测 0 次）。
// 实体与粒子的取光走 LightCoordsUtil.BrightnessGetter，两条路都要挂。
@Mixin(targets = "net.minecraft.client.renderer.block.BlockModelLighter$Cache", priority = 900)
public abstract class TerrainLightMixin {

    @ModifyReturnValue(
            method = "getLightCoords(" +
                    "Lnet/minecraft/world/level/block/state/BlockState;" +
                    "Lnet/minecraft/client/renderer/block/BlockAndTintGetter;" +
                    "Lnet/minecraft/core/BlockPos;)I",
            at = @At("RETURN"),
            remap = false,
            allow = 1,
            require = 1
    )
    private int particleDrawing$applyDynamicLightToTerrain(
            int original, BlockState state, BlockAndTintGetter level, BlockPos pos
    ) {
        if (!DynamicLightManager.isEnabled()) {
            return original;
        }
        double dynamic = DynamicLightManager.getDynamicLightLevel(
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        int merged = DynamicLightManager.getLightmapWithDynamicLight(level, pos, original);
        DynamicLightManager.noteTerrainCall(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, dynamic, merged != original);
        return merged;
    }
}