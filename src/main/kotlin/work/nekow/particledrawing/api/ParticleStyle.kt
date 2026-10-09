package work.nekow.particledrawing.api

/**
 * 程序化粒子的内置外观形状。
 *
 * 每个非 [SQUARE] 形状对应一张自带的 16×16 柔边小贴图，由客户端按需生成，两端各自生成同一份，
 * 名字与 id 写死在枚举里。也可先用 [ParticleManager.registerBuiltinTextures] 注册，
 * 再按 [textureName] 当普通贴图引用。
 *
 * 贴图边长为 16，所以贴图尺寸系数 = 1，`scale` 与尺寸的换算不被贴图放大。
 */
enum class ParticleStyle(
    /** 内置贴图名；[SQUARE] 没有贴图，为 null。 */
    val textureName: String?,
    /** 协议里引用内置贴图的固定 id（1 起；[SQUARE] 恒为 0 = 无贴图）。 */
    val textureId: Int
) {
    /** 纯白方块（默认）：无贴图，整颗按颜色染色，边缘是硬的。 */
    SQUARE(null, 0),

    /** 柔边圆点：中心实、边缘平滑过渡，用于不该是方块的粒子。 */
    SOFT_DOT("particledrawing:builtin/soft_dot", 1),

    /**
     * 线段：沿长轴（四边形局部 X）拉伸、两端渐隐、上下柔边，单颗就是一条完整的丝。
     * 配合各向异性尺寸（长 = 线段长、短 = 粗）与固定朝向（[ParticleVisual.alignTo]）使用。
     */
    LINE("particledrawing:builtin/line", 2);

    companion object {
        /** 内置贴图名 → 形状；不是内置名时返回 null。 */
        @JvmStatic
        fun fromTextureName(name: String?): ParticleStyle? {
            if (name == null) return null
            for (style in entries) {
                if (style.textureName == name) return style
            }
            return null
        }

        /** 内置贴图 id → 形状；不是内置 id 时返回 null。 */
        @JvmStatic
        fun fromTextureId(id: Int): ParticleStyle? {
            if (id <= 0) return null
            for (style in entries) {
                if (style.textureId == id) return style
            }
            return null
        }
    }
}
