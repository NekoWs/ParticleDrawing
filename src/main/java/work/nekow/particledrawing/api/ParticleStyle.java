package work.nekow.particledrawing.api;

import net.minecraft.resources.Identifier;

/**
 * Defines the visual style of a particle, mapping to a vanilla particle render type and sprite.
 */
@SuppressWarnings("unused")
public enum ParticleStyle {

    DUST(
        Identifier.withDefaultNamespace("generic_0"),
        ParticleRenderStyle.OPAQUE,
        true
    ),

    END_ROD(
        Identifier.withDefaultNamespace("generic_7"),
        ParticleRenderStyle.TRANSLUCENT,
        false
    ),

    FLAME(
        Identifier.withDefaultNamespace("flame"),
        ParticleRenderStyle.LIT,
        false
    ),

    SOUL_FIRE(
        Identifier.withDefaultNamespace("soul_fire_flame"),
        ParticleRenderStyle.LIT,
        false
    ),

    PORTAL(
        Identifier.withDefaultNamespace("generic_1"),
        ParticleRenderStyle.TRANSLUCENT,
        false
    ),

    ENCHANT(
        Identifier.withDefaultNamespace("generic_2"),
        ParticleRenderStyle.TRANSLUCENT,
        false
    ),

    WITCH(
        Identifier.withDefaultNamespace("generic_3"),
        ParticleRenderStyle.TRANSLUCENT,
        false
    ),

    NOTE(
        Identifier.withDefaultNamespace("generic_4"),
        ParticleRenderStyle.OPAQUE,
        true
    ),

    HEART(
        Identifier.withDefaultNamespace("generic_5"),
        ParticleRenderStyle.TRANSLUCENT,
        false
    ),

    SPARK(
        Identifier.withDefaultNamespace("generic_6"),
        ParticleRenderStyle.TRANSLUCENT,
        false
    ),

    GLOW(
        Identifier.withDefaultNamespace("glow"),
        ParticleRenderStyle.LIT,
        false
    ),

    BUBBLE(
        Identifier.withDefaultNamespace("bubble"),
        ParticleRenderStyle.TRANSLUCENT,
        false
    ),

    DRAGON_BREATH(
        Identifier.withDefaultNamespace("generic_0"),
        ParticleRenderStyle.OPAQUE,
        true
    ),

    SMOKE(
        Identifier.withDefaultNamespace("generic_7"),
        ParticleRenderStyle.TRANSLUCENT,
        false
    ),

    CLOUD(
        Identifier.withDefaultNamespace("generic_5"),
        ParticleRenderStyle.TRANSLUCENT,
        false
    );

    private final Identifier spriteLocation;
    private final ParticleRenderStyle renderStyle;
    private final boolean supportsColor;

    ParticleStyle(Identifier spriteLocation, ParticleRenderStyle renderStyle, boolean supportsColor) {
        this.spriteLocation = spriteLocation;
        this.renderStyle = renderStyle;
        this.supportsColor = supportsColor;
    }

    public Identifier spriteLocation() {
        return spriteLocation;
    }

    public ParticleRenderStyle renderStyle() {
        return renderStyle;
    }

    public boolean supportsColor() {
        return supportsColor;
    }

    public enum ParticleRenderStyle {
        OPAQUE,
        TRANSLUCENT,
        LIT,
        LIT_TRANSLUCENT
    }
}
