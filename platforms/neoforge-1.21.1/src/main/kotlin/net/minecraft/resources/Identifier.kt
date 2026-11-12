package net.minecraft.resources

/**
 * 1.21.1 里这个类型叫 [ResourceLocation]，26.x 才改名为 `Identifier`。
 *
 * 共享平台代码按 26.x 的名字书写；这里用类型别名桥接两个世代，避免在 130 多个文件里做纯机械改名。
 * 类型别名不产生任何字节码，不会进 JAR，也不会影响 mixin 或资源扫描。
 *
 * 三个静态入口（`fromNamespaceAndPath`、`parse`、`withDefaultNamespace`）在 1.21.1 的
 * [ResourceLocation] 上同名同形，调用点不需要改。
 */
typealias Identifier = ResourceLocation
