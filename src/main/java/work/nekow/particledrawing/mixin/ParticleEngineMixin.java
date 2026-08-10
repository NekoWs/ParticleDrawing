package work.nekow.particledrawing.mixin;

import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Removes vanilla particle count limits to prevent culling when
 * creating large numbers of particles through the vanilla API.
 * While ParticleDrawing uses its own render pipeline, this ensures
 * compatibility if any particles are routed through the vanilla engine.
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleEngineMixin {

    /**
     * Patch all int constants that might represent particle limits.
     * This conservative approach catches undocumented limits.
     */
    @ModifyConstant(
        method = {
            "tick",
            "render",
            "createParticle",
            "add",
            "updateTracking"
        },
        constant = @Constant(intValue = 16384),
        require = 0,
        expect = 0
    )
    private int removeParticleLimit(int original) {
        return Integer.MAX_VALUE >> 2;
    }

    @ModifyConstant(
        method = {
            "tick",
            "render",
            "createParticle",
            "add",
            "updateTracking"
        },
        constant = @Constant(intValue = 4096),
        require = 0,
        expect = 0
    )
    private int removeSmallParticleLimit(int original) {
        return Integer.MAX_VALUE >> 2;
    }
}
