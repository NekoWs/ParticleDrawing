package work.nekow.particledrawing.mixin.sodium;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import work.nekow.particledrawing.lighting.DynamicLightManager;

/** 单方块渲染（流体等）也走同一套光数据缓存，同样把动态光并进方块光。 */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.model.light.data.SingleBlockLightDataCache", remap = false)
public abstract class SodiumSingleBlockLightDataCacheMixin {
    @Inject(method = "get(III)I", at = @At("RETURN"), cancellable = true, remap = false)
    private void particleDrawing$raiseBlockLight(int x, int y, int z, CallbackInfoReturnable<Integer> cir) {
        int lightData = cir.getReturnValueI();
        int lit = DynamicLightManager.lightDataWithDynamicLight(lightData, x, y, z);
        if (lit != lightData) cir.setReturnValue(lit);
    }
}
