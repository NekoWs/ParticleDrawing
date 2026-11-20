package work.nekow.particledrawing.mixin.sodium;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import work.nekow.particledrawing.lighting.DynamicLightManager;

/**
 * 把动态光并进 Sodium 的方块光等级。
 *
 * Sodium 的区块光照走自己的 LightDataAccess 缓存（低 4 位是方块光），在查询结果上抬高方块光，
 * 它后面的平滑光照/AO 就会自然算出更亮的顶点光照。光影包若只把这一位当环境光用，亮度会受包限制。
 */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.model.light.data.ArrayLightDataCache", remap = false)
public abstract class SodiumArrayLightDataCacheMixin {
    @Inject(method = "get(III)I", at = @At("RETURN"), cancellable = true, remap = false)
    private void particleDrawing$raiseBlockLight(int x, int y, int z, CallbackInfoReturnable<Integer> cir) {
        int lightData = cir.getReturnValueI();
        int lit = DynamicLightManager.lightDataWithDynamicLight(lightData, x, y, z);
        if (lit != lightData) cir.setReturnValue(lit);
    }
}
