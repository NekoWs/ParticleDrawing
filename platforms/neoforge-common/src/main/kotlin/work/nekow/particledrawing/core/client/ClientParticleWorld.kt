package work.nekow.particledrawing.core.client

import net.minecraft.client.Minecraft
import work.nekow.particledrawing.core.MinecraftParticleWorld
import work.nekow.particledrawing.math.Vec3

internal object ClientParticleWorld : MinecraftParticleWorld() {
    override fun level() = Minecraft.getInstance().level
    override fun fieldAcceleration(position: Vec3, tick: Long) = ClientParticleFields.accelerationAt(position, tick)
}
