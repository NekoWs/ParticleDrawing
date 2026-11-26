package work.nekow.particledrawing.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.culling.Frustum;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.lwjgl.opengl.GL11;
import work.nekow.particledrawing.core.client.SceneParticlePass;
import work.nekow.particledrawing.core.client.ShaderCompat;

@Mixin(LevelRenderer.class)
abstract class SceneParticlePassMixin {
    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lorg/joml/Matrix4fStack;popMatrix()Lorg/joml/Matrix4fStack;", remap = false))
    private void pd$renderSceneParticles(DeltaTracker time, boolean outline, Camera camera, GameRenderer renderer,
        LightTexture light, Matrix4f view, Matrix4f projection, CallbackInfo ci, @Local Frustum frustum) {
        if (!ShaderCompat.INSTANCE.postShaderParticles()) {
            SceneParticlePass.render(camera, time.getGameTimeDeltaPartialTick(false), frustum, light);
        }
    }

    // Iris 1.21.1 finishes its composite at RETURN, after vanilla has already popped the world view.
    @Inject(method = "renderLevel", order = 1100, at = @At("RETURN"))
    private void pd$renderAfterIris(DeltaTracker time, boolean outline, Camera camera, GameRenderer renderer,
        LightTexture light, Matrix4f view, Matrix4f projection, CallbackInfo ci, @Local Frustum frustum) {
        if (!ShaderCompat.INSTANCE.postShaderParticles()) return;
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        Matrix4f previousProjection = RenderSystem.getProjectionMatrix();
        VertexSorting previousSorting = RenderSystem.getVertexSorting();
        modelView.pushMatrix();
        modelView.mul(view);
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(projection, VertexSorting.DISTANCE_TO_ORIGIN);
        Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        try {
            SceneParticlePass.render(camera, time.getGameTimeDeltaPartialTick(false), frustum, light);
        } finally {
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(previousProjection, previousSorting);
        }
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void pd$closeSceneParticlePass(CallbackInfo ci) {
        SceneParticlePass.close();
    }
}
