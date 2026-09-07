package work.nekow.particledrawing.mixin;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import work.nekow.particledrawing.lighting.DynamicLightManager;

// 修正实体渲染的光照，避免实体在发光粒子附近显得过暗；
// 与方块光照一致，把动态光照按小数精度合并进打包光坐标。
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin<T extends Entity> {

    @Inject(
        method = "getPackedLightCoords(Lnet/minecraft/world/entity/Entity;F)I",
        at = @At("RETURN"),
        cancellable = true,
        require = 1
    )
    private void injectDynamicLightForEntity(T entity, float partialTickTime,
                                              CallbackInfoReturnable<Integer> cir) {
        int packed = cir.getReturnValue();
        double ey = entity.getEyeY();
        int merged = DynamicLightManager.getLightmapWithDynamicLight(
            packed, entity.getX(), ey, entity.getZ());
        if (merged != packed) {
            cir.setReturnValue(merged);
        }
    }
}
