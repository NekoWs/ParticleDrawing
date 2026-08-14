package work.nekow.particledrawing.mixin;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import work.nekow.particledrawing.lighting.DynamicLightManager;

/**
 * Fixes entity rendering to account for dynamic light sources.
 * Without this, entities near glowing particles would appear dark
 * while surrounding blocks are lit.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin<T extends Entity> {

    /**
     * Adds dynamic light to the packed light coordinates used for entity rendering.
     */
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
        int dynamic = DynamicLightManager.getDynamicLightLevel(
            entity.getX(), ey, entity.getZ());
        if (dynamic > 0) {
            int skyLight = (packed >> 20) & 0xF;
            int blockLight = (packed >> 4) & 0xF;
            int newBlock = Math.min(15, blockLight + dynamic);
            cir.setReturnValue((skyLight << 20) | (newBlock << 4));
        }
    }
}
