package work.nekow.particledrawing.platform

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.entity.Entity
import net.minecraft.client.renderer.LightTexture
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.neoforged.api.distmarker.Dist
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.neoforge.network.PacketDistributor
import java.util.function.Predicate

/**
 * 1.21.1 / NeoForge 21.1.x 的跨版本差异点。
 *
 * 和共享树里的 26.x 版本签名一致，替换掉那一个文件（见 `target.properties` 的 `shared_replacements`）。
 */
object PlatformCompat {

    /** 当前进程是否客户端。1.21.1 是字段 `dist`。 */
    val isClientDist: Boolean get() = FMLEnvironment.dist == Dist.CLIENT

    /** 客户端向服务端发包。1.21.1 走 `PacketDistributor`。 */
    fun sendToServer(payload: CustomPacketPayload) = PacketDistributor.sendToServer(payload)

    /** 关卡时钟（世界时间）。1.21.1 用 `dayTime`。 */
    fun clockTime(level: ClientLevel): Long = level.dayTime

    /** 取资源键的标识。1.21.1 是 `location()`。 */
    fun keyId(key: ResourceKey<Level>): Identifier = key.location()

    /** 取光图里的方块光照分量。1.21.1 用 `LightTexture`。 */
    fun lightBlock(packed: Int): Int = LightTexture.block(packed)

    /** 玩家所在服务端关卡：1.21.1 的 `Entity.level()` 静态类型是 `Level`，需要下转。 */
    fun serverLevelOf(player: ServerPlayer): ServerLevel = player.level() as ServerLevel

    /** 按 UUID 找客户端世界里的实体。1.21.1 的 `ClientLevel` 只有按 id 查。 */
    fun clientEntityByUuid(level: ClientLevel, uuid: java.util.UUID): Entity? =
        level.entitiesForRendering().firstOrNull { it.uuid == uuid }

    /** 标记区块 section 需要重建。1.21.1 走 `LevelRenderer`。 */
    fun markSectionDirty(sectionX: Int, sectionY: Int, sectionZ: Int) {
        Minecraft.getInstance().levelRenderer.setSectionDirty(sectionX, sectionY, sectionZ)
    }

    /** FOV 覆盖：1.21.1 的 fov 字段是 double。 */
    fun applyFov(event: net.neoforged.neoforge.client.event.ViewportEvent.ComputeFov, fov: Float) {
        event.fov = fov.toDouble()
    }

    /** 命令权限：只允许游戏管理员（等级 2）。 */
    fun gamemastersOnly(): Predicate<CommandSourceStack> = Predicate { it.hasPermission(2) }
}
