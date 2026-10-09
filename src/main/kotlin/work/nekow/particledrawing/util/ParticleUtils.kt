package work.nekow.particledrawing.util

import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import java.util.UUID

/**
 * 粒子相关的工具方法。
 */
object ParticleUtils {

    /**
     * 取服务端世界对应的维度 UUID。
     *
     * @return 维度对应的 UUID
     */
    @JvmStatic
    fun dimensionUUID(level: ServerLevel): UUID = dimensionUUID(level.dimension().identifier())

    /**
     * 由资源标识符生成确定性 UUID（基于名称哈希）。
     *
     * @return 维度对应的 UUID
     */
    @JvmStatic
    fun dimensionUUID(location: Identifier): UUID =
        UUID.nameUUIDFromBytes(location.toString().encodeToByteArray())
}
