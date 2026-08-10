package work.nekow.particledrawing.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import work.nekow.particledrawing.core.client.ClientParticleEngine;
import work.nekow.particledrawing.lighting.DynamicLightEngine;
import work.nekow.particledrawing.lighting.DynamicLightManager;

/**
 * Refreshes dynamic light data on the render thread before the lightmap is computed.
 * The actual block lighting is handled by {@link DynamicLightEngine} via the server
 * light engine; this just ensures the DynamicLightManager has up-to-date data.
 */
@Mixin(LightmapRenderStateExtractor.class)
public abstract class LightmapRenderStateExtractorMixin {

    @Inject(
        method = "extract(Lnet/minecraft/client/renderer/state/LightmapRenderState;F)V",
        at = @At("HEAD"),
        require = 1
    )
    private void refreshDynamicLights(LightmapRenderState renderState, float partialTicks, CallbackInfo ci) {
        var engine = ClientParticleEngine.instance();
        if (engine != null) {
            var camera = Minecraft.getInstance().gameRenderer.mainCamera();
            DynamicLightManager.renderDynamicLights(engine, camera);
        }
    }
}
