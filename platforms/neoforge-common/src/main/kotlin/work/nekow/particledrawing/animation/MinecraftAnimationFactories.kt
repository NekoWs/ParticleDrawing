package work.nekow.particledrawing.animation

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.platform.toCore

fun AnimParticle(
    id: String, color: Color, scale: FloatArray, glowing: Boolean, lightLevel: Int,
    pos: Vec3, vel: Vec3, uv: UvData? = null, st: Int = 0, ent: Entrance? = null,
    life: Int = -1, billboard: Boolean = true, spinLocal: Boolean = true,
) = AnimParticle(id, color, scale, glowing, lightLevel, pos.toCore(), vel.toCore(), uv, st, ent, life, billboard, spinLocal)

fun TextChar(index: Int, code: Int, pos: Vec3, size: DoubleArray, particles: List<String>) =
    TextChar(index, code, pos.toCore(), size, particles)

fun ClientAnimationPlayer(
    animation: ParticleAnimation, origin: Vec3, startGameTick: Long = 0L,
    currentGameTick: Long = 0L, initialMs: Int = -1,
) = ClientAnimationPlayer(animation, origin.toCore(), startGameTick, currentGameTick, initialMs)
