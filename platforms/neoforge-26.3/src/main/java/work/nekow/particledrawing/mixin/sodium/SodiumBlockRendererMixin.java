package work.nekow.particledrawing.mixin.sodium;

import net.minecraft.core.BlockPos;
import work.nekow.particledrawing.compat.sodium.SodiumTerrainState;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 记录 Sodium 区块构建时当前方块的位置，供顶点上色钩子查询动态光。
 *
 * Sodium 有自己的一套方块渲染器，不走原版的 ModelBlockRenderer，所以动态光只能从这里接。
 */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer", remap = false)
public abstract class SodiumBlockRendererMixin {
    private static final String RENDER_MODEL =
            "renderModel(Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;"
                    + "Lnet/minecraft/world/level/block/state/BlockState;"
                    + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;)V";

    @Inject(method = RENDER_MODEL, at = @At("HEAD"), remap = false)
    private void particleDrawing$pushBlockPos(
            BlockStateModel model, BlockState state, BlockPos pos, BlockPos renderPos, CallbackInfo ci
    ) {
        SodiumTerrainState.push(pos);
    }

    @Inject(method = RENDER_MODEL, at = @At("RETURN"), remap = false)
    private void particleDrawing$popBlockPos(
            BlockStateModel model, BlockState state, BlockPos pos, BlockPos renderPos, CallbackInfo ci
    ) {
        SodiumTerrainState.pop();
    }
}

