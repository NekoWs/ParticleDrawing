package work.nekow.particledrawing.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.BlockAndLightGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import work.nekow.particledrawing.lighting.DynamicLightManager;

// 给原版默认亮度获取器注入动态光照：烘焙区块 section 网格时用
// {@link LightCoordsUtil.BrightnessGetter#DEFAULT} 查方块光图坐标，在返回前叠加动态光照值。
@Mixin(value = LightCoordsUtil.BrightnessGetter.class, priority = 900)
public interface BrightnessGetterMixin {

    @ModifyReturnValue(
            method = "lambda$static$0",
            at = @At("RETURN"),
            remap = false,
            allow = 1,
            require = 1
    )
    private static int particleDrawing$applyDynamicLight(
            int original, BlockAndLightGetter level, BlockPos pos
    ) {
        if (!DynamicLightManager.isEnabled()) {
            return original;
        }
        if (level.getBlockState(pos).isSolidRender()) {
            return original;
        }
        return DynamicLightManager.getLightmapWithDynamicLight(level, pos, original);
    }
}
