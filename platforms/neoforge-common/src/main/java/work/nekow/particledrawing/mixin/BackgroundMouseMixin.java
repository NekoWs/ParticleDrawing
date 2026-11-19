package work.nekow.particledrawing.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
abstract class BackgroundMouseMixin {
    @Unique
    private static final boolean pd$backgroundCapture = Boolean.getBoolean("pd.backgroundCapture");

    @Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
    private void pd$keepCursorFree(CallbackInfo ci) {
        if (pd$backgroundCapture) {
            ci.cancel();
        }
    }
}
