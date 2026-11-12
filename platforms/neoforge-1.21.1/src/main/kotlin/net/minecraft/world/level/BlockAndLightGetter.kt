package net.minecraft.world.level

/**
 * 1.21.1 里这个接口叫 [BlockAndTintGetter]，26.x 才改名为 `BlockAndLightGetter`。
 *
 * 只做类型桥接，不产生字节码；动态光注入用的就是这个接口的读取能力，两边方法一致。
 */
typealias BlockAndLightGetter = BlockAndTintGetter
