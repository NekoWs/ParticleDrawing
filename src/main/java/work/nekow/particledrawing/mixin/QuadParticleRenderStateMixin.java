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

// 拦截 QuadParticleRenderState 的顶点生成，让 BridgeParticle 的非均匀缩放粒子
// 有独立的宽/高缩放（原版只有一个 scale，quad 永远是正方形）。
// 原理：BridgeParticle.extractRotatedQuad 在调父类前写好静态字段 nonUniformScaleW，
// renderVertex 检测到有效值后把 nx 分量换成 (nx / scale * scaleW)。
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
