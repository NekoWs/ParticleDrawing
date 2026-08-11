package work.nekow.particledrawing.api

import net.minecraft.resources.Identifier

/**
 * 定义粒子的视觉样式，映射到原版粒子渲染类型和精灵图。
 */
@Suppress("unused")
enum class ParticleStyle(
    val spriteLocation: Identifier,
    val renderStyle: ParticleRenderStyle,
    val supportsColor: Boolean
) {
    DUST(
        Identifier.withDefaultNamespace("generic_0"),
        ParticleRenderStyle.OPAQUE,
        true
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

    NOTE(
        Identifier.withDefaultNamespace("note"),
        ParticleRenderStyle.OPAQUE,
        true
    ),

    HEART(
        Identifier.withDefaultNamespace("heart"),
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
    );

    enum class ParticleRenderStyle {
        OPAQUE,
        TRANSLUCENT,
        LIT,
        LIT_TRANSLUCENT
    }
}
