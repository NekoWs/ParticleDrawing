package work.nekow.particledrawing.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.BlockAndLightGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import work.nekow.particledrawing.lighting.DynamicLightManager;

/**
 * 将动态光照注入原版默认亮度获取器。
 * <p>
 * 原版渲染器在烘焙区块 section 网格时通过 {@link LightCoordsUtil.BrightnessGetter#DEFAULT}
 * 查询每个方块的光图坐标，注入此 lambda 即可在方块光照分量上叠加平滑的动态光照值，
 * 实现不放置光源方块的世界方块光照效果。
 */
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
