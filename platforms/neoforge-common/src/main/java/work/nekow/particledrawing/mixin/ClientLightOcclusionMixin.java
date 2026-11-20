package work.nekow.particledrawing.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import work.nekow.particledrawing.lighting.ClientLightOccluders;

@Mixin(ClientLevel.class)
public abstract class ClientLightOcclusionMixin {
    @Inject(method = "setBlock", at = @At("RETURN"))
    private void pd$invalidateBlockShadow(BlockPos pos, BlockState state, int flags, int depth,
                                          CallbackInfoReturnable<Boolean> result) {
        if (result.getReturnValueZ()) ClientLightOccluders.blockChanged((ClientLevel) (Object) this, pos);
    }

    @Inject(method = "onChunkLoaded", at = @At("RETURN"))
    private void pd$invalidateLoadedChunkShadows(ChunkPos pos, CallbackInfo result) {
        ClientLightOccluders.chunkChanged((ClientLevel) (Object) this, pos);
    }

    @Inject(method = "unload", at = @At("RETURN"))
    private void pd$invalidateUnloadedChunkShadows(LevelChunk chunk, CallbackInfo result) {
        ClientLightOccluders.chunkChanged((ClientLevel) (Object) this, chunk.getPos());
    }
}
