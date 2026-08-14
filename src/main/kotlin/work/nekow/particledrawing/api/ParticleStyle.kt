package work.nekow.particledrawing.api

import net.minecraft.resources.Identifier

/**
 * 定义粒子的视觉样式，映射到原版粒子精灵图与渲染类型。
 * `supportsColor` 为 `false` 的样式会忽略传入颜色。
 */
@Suppress("unused")
enum class ParticleStyle(
    val spriteLocation: Identifier,
    val renderStyle: ParticleRenderStyle,
    val supportsColor: Boolean
) {
    /** 点粒子。 */
    DOT(
        Identifier.withDefaultNamespace("generic_0"),
        ParticleRenderStyle.OPAQUE,
        true
    ),

    /** 灰尘粒子。 */
    DUST(
        Identifier.withDefaultNamespace("generic_7"),
        ParticleRenderStyle.OPAQUE,
        true
    ),

    /** 火焰粒子。 */
    FLAME(
        Identifier.withDefaultNamespace("flame"),
        ParticleRenderStyle.LIT,
        false
    ),

    /** 灵魂火焰粒子。 */
    SOUL_FIRE(
        Identifier.withDefaultNamespace("soul_fire_flame"),
        ParticleRenderStyle.LIT,
        false
    ),

    /** 音符粒子。 */
    NOTE(
        Identifier.withDefaultNamespace("note"),
        ParticleRenderStyle.OPAQUE,
        true
    ),

    /** 爱心粒子。 */
    HEART(
        Identifier.withDefaultNamespace("heart"),
        ParticleRenderStyle.TRANSLUCENT,
        false
    ),

    /** 星型粒子。 */
    SPARK(
        Identifier.withDefaultNamespace("glow"),
        ParticleRenderStyle.TRANSLUCENT,
        false
    ),

    /** 发光粒子。 */
    GLOW(
        Identifier.withDefaultNamespace("glow"),
        ParticleRenderStyle.LIT,
        false
    ),

    /** 气泡粒子。 */
    BUBBLE(
        Identifier.withDefaultNamespace("bubble"),
        ParticleRenderStyle.TRANSLUCENT,
        false
    ),

    /** 烟雾粒子。 */
    SMOKE(
        Identifier.withDefaultNamespace("generic_7"),
        ParticleRenderStyle.TRANSLUCENT,
        false
    );

    /** 粒子渲染类型。 */
    enum class ParticleRenderStyle {
        OPAQUE,
        TRANSLUCENT,
        LIT,
        LIT_TRANSLUCENT
    }
}
