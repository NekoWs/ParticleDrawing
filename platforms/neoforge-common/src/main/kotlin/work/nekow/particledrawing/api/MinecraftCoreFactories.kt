package work.nekow.particledrawing.api

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.platform.toCore
import java.util.UUID

/** Minecraft 入口转换一次，采样与公式运行期间使用核心坐标。 */
fun ParticleSeed(position: Vec3, color: Color = Color.WHITE, u: Double = 0.0) = ParticleSeed(position.toCore(), color, u)

fun RecipeAnchor(position: Vec3, entity: UUID? = null, entityId: Int = -1, offset: Vec3 = Vec3.ZERO, local: Boolean = false) =
    RecipeAnchor(position.toCore(), entity, entityId, offset.toCore(), local)

fun ParticleVisual.alignTo(from: Vec3, to: Vec3): ParticleVisual = alignTo(from.toCore(), to.toCore())

fun ParticleSampling.grid(min: Vec3, max: Vec3, step: Double) = grid(min.toCore(), max.toCore(), step)
