package work.nekow.particledrawing.mixin;

import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import work.nekow.particledrawing.core.client.BridgeParticle;

import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * 拦截 QuadParticleRenderState 的顶点生成，为 BridgeParticle 非均匀缩放粒子注入
 * 独立的宽度/高度缩放（原版只支持单一 scale → 正方形 quad）。
 *
 * 原理：BridgeParticle.extractRotatedQuad 在调用父类前设置静态字段 nonUniformScaleW，
 * 此 mixin 的 renderVertex 检测到该字段有效时将 nx 分量替换为 (nx / scale * scaleW)，
 * 实现宽度和高度独立缩放。
 */
@Mixin(QuadParticleRenderState.class)
public class QuadParticleRenderStateMixin {

    @Inject(
        method = "renderVertex",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onRenderVertex(
        VertexConsumer builder, Quaternionf rotation,
        float x, float y, float z, float nx, float ny, float scale,
        float u, float v, int color, int lightCoords,
        CallbackInfo ci
    ) {
        float scaleW = BridgeParticle.getNonUniformScaleW();
        if (scaleW < 0f) return;  // 均匀缩放，走原版路径

        // 原版：scratch = (nx, ny, 0).rotate(rotation).mul(scale)
        // 非均匀：x 分量乘 scaleW 而非 scale（y 分量仍用 scale = scaleH）
        ci.cancel();
        Vector3f scratch = new Vector3f(nx, ny, 0.0F).rotate(rotation);
        scratch.set(scratch.x() * scaleW, scratch.y() * scale, scratch.z() * scale);
        scratch.add(x, y, z);
        builder.addVertex(scratch.x(), scratch.y(), scratch.z())
               .setUv(u, v)
               .setColor(color)
               .setLight(lightCoords);
    }
}
