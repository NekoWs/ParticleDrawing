package work.nekow.particledrawing.util

import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import java.util.UUID

/**
 * 粒子相关的工具方法。
 */
object ParticleUtils {

    /**
     * 根据服务端世界生成维度 UUID。
     * @param level 服务端世界实例
     * @return 维度对应的 UUID
     */
    fun dimensionUUID(level: ServerLevel): UUID = dimensionUUID(level.dimension().identifier())

    /**
     * 根据资源标识符生成确定性 UUID（基于名称哈希）。
     * @param location 维度的资源标识符
     * @return 维度对应的 UUID
     */
    fun dimensionUUID(location: Identifier): UUID =
        UUID.nameUUIDFromBytes(location.toString().encodeToByteArray())
}
