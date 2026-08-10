package work.nekow.particledrawing.util

import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import java.util.UUID

object ParticleUtils {

    fun dimensionUUID(level: ServerLevel): UUID = dimensionUUID(level.dimension().identifier())

    fun dimensionUUID(location: Identifier): UUID {
        val hash = location.toString().hashCode().toLong()
        return if (hash < 0) UUID(0, Math.abs(hash)) else UUID(hash, 0)
    }
}
