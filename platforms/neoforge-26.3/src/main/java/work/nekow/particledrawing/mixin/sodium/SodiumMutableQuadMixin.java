package work.nekow.particledrawing.mixin.sodium;

import net.minecraft.core.BlockPos;
import work.nekow.particledrawing.compat.sodium.SodiumTerrainState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import work.nekow.particledrawing.core.client.ShaderCompat;
import work.nekow.particledrawing.lighting.DynamicLightManager;

/**
 * 给 Sodium 的四边形顶点按动态光上色。
 *
 * Sodium 的顶点颜色最终取自 QuadInstance 的 baseColor，而 baseColor 由 setColor 写入，所以在这里
 * 按当前方块位置把颜色染成动态光颜色（和原版地形顶点着色用同一套算法）。
 */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.model.MutableQuadViewImpl", remap = false)
public abstract class SodiumMutableQuadMixin {
    @ModifyVariable(
            method = "setColor(II)Lnet/caffeinemc/mods/sodium/client/render/model/MutableQuadViewImpl;",
            at = @At("HEAD"), index = 2, argsOnly = true, remap = false
    )
    private int particleDrawing$tintVertexColor(int color) {
        // 光影包自己算地形光照，改顶点颜色等于改它的反照率，会把材质整体染灰。
        if (ShaderCompat.INSTANCE.legacyPipelines()) return color;
        BlockPos pos = SodiumTerrainState.current();
        if (pos == null) return color;
        return DynamicLightManager.tintSodiumQuadColor(
                color, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }
}



