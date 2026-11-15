package work.nekow.particledrawing.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.BlockAndLightGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import work.nekow.particledrawing.lighting.DynamicLightManager;

// 动态光照混入：烘焙区块 section 网格时，在 {@link LightCoordsUtil.BrightnessGetter#DEFAULT}
// 返回方块光图坐标前叠加动态光照值。
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
        // 不能再排除实体方块：地形表面本身就是实体方块，之前这一判把地面全挡在动态光之外，
        // 结果只有空气格拿到光，画面上一片漆黑。动态光只抬不压，实体内壁也不会被看见。
        int merged = DynamicLightManager.getLightmapWithDynamicLight(level, pos, original);
        DynamicLightManager.noteBakeCall(merged != original);
        return merged;
    }
}
