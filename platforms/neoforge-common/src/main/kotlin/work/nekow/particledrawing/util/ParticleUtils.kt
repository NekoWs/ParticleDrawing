package work.nekow.particledrawing.util

import work.nekow.particledrawing.platform.PlatformCompat
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
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
    fun dimensionUUID(level: ServerLevel): UUID = dimensionUUID(PlatformCompat.keyId(level.dimension()))

    /**
     * 同上；1.21.1 里 `Entity.level()` 的静态类型是 `Level`，调用方需要这个通用入口。
     */
    @JvmStatic
    fun dimensionUUID(level: Level): UUID = dimensionUUID(PlatformCompat.keyId(level.dimension()))

    /**
     * 由资源标识符生成确定性 UUID（基于名称哈希）。
     *
     * @return 维度对应的 UUID
     */
    @JvmStatic
    fun dimensionUUID(location: Identifier): UUID =
        UUID.nameUUIDFromBytes(location.toString().encodeToByteArray())
}
