package work.nekow.particledrawing.platform

import net.minecraft.world.phys.Vec3
import net.minecraft.resources.Identifier
import work.nekow.particledrawing.pointset.PointSet
import work.nekow.particledrawing.math.Vec3 as CoreVec3
import work.nekow.particledrawing.math.ResourceId

fun Vec3.toCore() = CoreVec3(x, y, z)
fun CoreVec3.toMinecraft() = Vec3(x, y, z)
fun ResourceId.toMinecraft() = Identifier.fromNamespaceAndPath(namespace, path)

/** 点集与宿主坐标列表互转；跨层边界显式转换，内部一律用核心 `Vec3`。 */
fun PointSet.toMinecraft(): List<Vec3> = points.map { it.toMinecraft() }
fun List<Vec3>.toPointSet(): PointSet = PointSet.of(map { it.toCore() })
