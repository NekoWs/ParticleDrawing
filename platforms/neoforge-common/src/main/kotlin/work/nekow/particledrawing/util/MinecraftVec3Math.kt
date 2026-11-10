package work.nekow.particledrawing.util

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.platform.toCore
import work.nekow.particledrawing.platform.toMinecraft

/** 游戏入口使用原生向量，旋转公式由核心统一实现。 */
fun Vec3.rotateAround(unitAxis: Vec3, radians: Double): Vec3 = toCore().rotateAround(unitAxis.toCore(), radians).toMinecraft()
