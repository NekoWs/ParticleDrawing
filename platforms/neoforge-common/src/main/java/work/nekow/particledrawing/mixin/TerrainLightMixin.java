package work.nekow.particledrawing.mixin;

import com.mojang.blaze3d.vertex.QuadInstance;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import work.nekow.particledrawing.lighting.DynamicLightManager;

@Mixin(value = ModelBlockRenderer.class, priority = 900)
public abstract class TerrainLightMixin {
    @Shadow @Final private QuadInstance quadInstance;

    // 原版与 NeoForge 增强环境遮蔽都在这里提交四边形。
    @Inject(method = "putQuadWithTint", at = @At("HEAD"))
    private void particleDrawing$lightVertices(
            BlockQuadOutput output, float x, float y, float z, BlockAndTintGetter level,
            BlockState state, BlockPos pos, BakedQuad quad, CallbackInfo ci
    ) {
        if (!DynamicLightManager.hasTerrainLightsAt(pos.getX(), pos.getY(), pos.getZ())) return;
        var offset = state.getOffset(pos);
        for (int i = 0; i < 4; i++) {
            var vertex = quad.position(i);
            long lit = DynamicLightManager.shadeTerrainVertex(quadInstance.getColor(i), quadInstance.getLightCoords(i),
                    pos.getX() + offset.x + vertex.x(),
                    pos.getY() + offset.y + vertex.y(),
                    pos.getZ() + offset.z + vertex.z());
            quadInstance.setColor(i, (int) (lit >>> 32));
            quadInstance.setLightCoords(i, (int) lit);
        }
    }
}
