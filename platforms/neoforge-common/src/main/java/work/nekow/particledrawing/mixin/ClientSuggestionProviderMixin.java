package work.nekow.particledrawing.mixin;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import work.nekow.particledrawing.core.client.ClientCommandSuggestions;

import java.util.concurrent.CompletableFuture;

@Mixin(ClientSuggestionProvider.class)
public abstract class ClientSuggestionProviderMixin {
    @Inject(method = "customSuggestion", at = @At("RETURN"), cancellable = true)
    private void particledrawing$expandResources(
        CommandContext<?> context, CallbackInfoReturnable<CompletableFuture<Suggestions>> callback
    ) {
        String input = context.getInput();
        callback.setReturnValue(callback.getReturnValue().thenApply(suggestions -> ClientCommandSuggestions.expand(input, suggestions)));
    }
}
