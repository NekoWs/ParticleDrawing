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

    enum class ParticleRenderStyle {
        OPAQUE,
        TRANSLUCENT,
        LIT,
        LIT_TRANSLUCENT
    }
}
