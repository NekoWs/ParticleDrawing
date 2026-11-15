package work.nekow.particledrawing.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.core.BlockPos;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.BlockAndLightGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import work.nekow.particledrawing.lighting.DynamicLightManager;

// 为客户端动态对象取光；区块模型的顶点光照单独写入。
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
        if (!(level instanceof ClientLevel)) return original;
        return DynamicLightManager.getLightmapWithDynamicLight(
                original, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }
}
