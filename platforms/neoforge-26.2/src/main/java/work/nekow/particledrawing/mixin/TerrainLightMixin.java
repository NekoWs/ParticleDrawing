package work.nekow.particledrawing.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import work.nekow.particledrawing.lighting.DynamicLightManager;

// 地形烘焙的动态光照注入点（26.x）。
//
// 26.x 把区块网格取光搬到了 BlockModelLighter.getLightCoords，原版每个顶点亮度都从这里过。
// 动态光只有在这一层叠加才会出现在地面上；实体与粒子的取光走 LightCoordsUtil.BrightnessGetter，
// 两条路都要挂，少一条就会出现「粒子亮、地面黑」的割裂。
@Mixin(value = BlockModelLighter.class, priority = 900)
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
    private static int particleDrawing$applyDynamicLightToTerrain(
            int original, BlockState state, BlockAndTintGetter level, BlockPos pos
    ) {
        if (!DynamicLightManager.isEnabled()) {
            return original;
        }
        double dynamic = DynamicLightManager.getDynamicLightLevel(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        int merged = DynamicLightManager.getLightmapWithDynamicLight(level, pos, original);
        DynamicLightManager.noteTerrainCall(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, dynamic, merged != original);
        return merged;
    }
}