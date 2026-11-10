package work.nekow.particledrawing.platform

import net.minecraft.world.phys.Vec3
import net.minecraft.resources.Identifier
import work.nekow.particledrawing.math.Vec3 as CoreVec3
import work.nekow.particledrawing.math.ResourceId

fun Vec3.toCore() = CoreVec3(x, y, z)
fun CoreVec3.toMinecraft() = Vec3(x, y, z)
fun ResourceId.toMinecraft() = Identifier.fromNamespaceAndPath(namespace, path)
