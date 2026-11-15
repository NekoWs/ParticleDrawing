package work.nekow.particledrawing.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import work.nekow.particledrawing.lighting.DynamicLightManager;

// 在客户端世界中为粒子等动态对象取光；区块的顶点光照单独写入。
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
        if (!(level instanceof ClientLevel)) return original;
        return DynamicLightManager.getLightmapWithDynamicLight(
                original, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }
}
