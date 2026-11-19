package work.nekow.particledrawing.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.culling.Frustum;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import work.nekow.particledrawing.core.client.SceneParticlePass;

@Mixin(LevelRenderer.class)
abstract class SceneParticlePassMixin {
    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lorg/joml/Matrix4fStack;popMatrix()Lorg/joml/Matrix4fStack;", remap = false))
    private void pd$renderSceneParticles(DeltaTracker time, boolean outline, Camera camera, GameRenderer renderer,
        LightTexture light, Matrix4f view, Matrix4f projection, CallbackInfo ci, @Local Frustum frustum) {
        SceneParticlePass.render(camera, time.getGameTimeDeltaPartialTick(false), frustum, light);
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void pd$closeSceneParticlePass(CallbackInfo ci) {
        SceneParticlePass.close();
    }
}
