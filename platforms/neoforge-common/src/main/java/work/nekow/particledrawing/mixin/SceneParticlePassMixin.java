package work.nekow.particledrawing.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import work.nekow.particledrawing.core.client.SceneParticlePass;

@Mixin(LevelRenderer.class)
abstract class SceneParticlePassMixin {
    @Inject(method = "render", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
        target = "Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;execute(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder$Inspector;)V"))
    private void pd$renderSceneParticles(CallbackInfo ci) {
        SceneParticlePass.render();
    }

    @Inject(method = "endFrame", at = @At("TAIL"))
    private void pd$endSceneParticleFrame(CallbackInfo ci) {
        SceneParticlePass.endFrame();
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void pd$closeSceneParticlePass(CallbackInfo ci) {
        SceneParticlePass.close();
    }
}
