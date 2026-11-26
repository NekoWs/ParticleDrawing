package work.nekow.particledrawing.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import work.nekow.particledrawing.core.client.SceneParticlePass;

@Mixin(LevelRenderer.class)
abstract class SceneParticlePassMixin {
    // Iris 合成场景后再绘制，保留当前视图矩阵供粒子和深度采样使用。
    @Inject(method = "render", order = 1100, at = @At(value = "INVOKE",
        target = "Lorg/joml/Matrix4fStack;popMatrix()Lorg/joml/Matrix4fStack;", remap = false))
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
