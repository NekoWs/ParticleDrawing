package work.nekow.particledrawing.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import work.nekow.particledrawing.lighting.DynamicLightManager;

// 动态光照混入（1.21.1）：区块网格烘焙取亮度走 LevelRenderer.getLightColor，
// 在这里把动态光照叠加进打包光坐标。26.x 的注入点是 LightCoordsUtil.BrightnessGetter（接口）。
// LevelRenderer 是类，所以本混入必须是类而不是接口。
@Mixin(value = LevelRenderer.class, priority = 900)
public abstract class BrightnessGetterMixin {

    @ModifyReturnValue(
            method = "getLightColor(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;)I",
            at = @At("RETURN"),
            remap = false,
            allow = 1,
            require = 1
    )
    private static int particleDrawing$applyDynamicLight(
            int original, BlockAndTintGetter level, BlockPos pos
    ) {
        if (!DynamicLightManager.isEnabled()) {
            return original;
        }
        if (level.getBlockState(pos).isSolidRender(level, pos)) {
            return original;
        }
        return DynamicLightManager.getLightmapWithDynamicLight(level, pos, original);
    }
}
