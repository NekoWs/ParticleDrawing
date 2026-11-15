package work.nekow.particledrawing.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import work.nekow.particledrawing.lighting.TerrainVertexConsumer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Desc;
import work.nekow.particledrawing.lighting.DynamicLightManager;

@Mixin(value = ModelBlockRenderer.class, priority = 900)
public abstract class TerrainLightMixin {
    @WrapOperation(method = "putQuadData", at = @At(value = "INVOKE", desc = @Desc(
            owner = VertexConsumer.class, value = "putBulkData", args = {
            PoseStack.Pose.class, BakedQuad.class, float[].class, float.class, float.class,
            float.class, float.class, int[].class, int.class, boolean.class}
    )))
    private void particleDrawing$lightVertices(
            VertexConsumer consumer, PoseStack.Pose pose, BakedQuad quad, float[] shades,
            float red, float green, float blue, float alpha, int[] lights, int overlay, boolean readColor,
            Operation<Void> original, BlockAndTintGetter level,
            BlockState state, BlockPos pos
    ) {
        if (DynamicLightManager.hasTerrainLightsAt(pos.getX(), pos.getY(), pos.getZ())) {
            int[] data = quad.getVertices();
            var offset = state.getOffset(level, pos);
            consumer = new TerrainVertexConsumer(consumer, data, pos, offset);
        }
        original.call(consumer, pose, quad, shades, red, green, blue, alpha, lights, overlay, readColor);
    }
}
