package work.nekow.particledrawing.platform

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.texture.DynamicTexture
import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.LightCoordsUtil
import net.minecraft.world.level.Level
import net.neoforged.api.distmarker.Dist
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.neoforge.client.network.ClientPacketDistributor
import java.util.function.Predicate

/**
 * 26.x 的少量跨版本差异点。
 *
 * 共享平台代码通过这里调用两边名字不同或签名不同的 API；1.21.1 目标有同名替换实现
 * （`shared_replacements` 里替换本文件）。
 */
object PlatformCompat {

    /** 当前进程是否客户端。26.x 是方法 `getDist()`，1.21.1 是字段 `dist`。 */
    val isClientDist: Boolean get() = FMLEnvironment.getDist() == Dist.CLIENT

    /** 该版本能否覆盖玩家相机姿态；1.21.1 缺 CameraMixin，只能改 FOV，命令应直接拒绝。 */
    val supportsCameraOverride: Boolean get() = true

    /** 客户端向服务端发包。26.x 走 `ClientPacketDistributor`。 */
    fun sendToServer(payload: CustomPacketPayload) = ClientPacketDistributor.sendToServer(payload)

    /** 关卡时钟（世界时间）。26.x 是 `overworldClockTime`。 */
    fun clockTime(level: ClientLevel): Long = level.overworldClockTime

    fun cameraPosition(mc: Minecraft): net.minecraft.world.phys.Vec3 = mc.gameRenderer.mainCamera().position()

    /** 取资源键的标识。26.x 是 `identifier()`，1.21.1 是 `location()`。 */
    fun keyId(key: ResourceKey<Level>): Identifier = key.identifier()

    /** 取光图里的方块光照分量。26.x 是 `LightCoordsUtil`，1.21.1 是 `LightTexture`。 */
    fun lightBlock(packed: Int): Int = LightCoordsUtil.block(packed)

    /** 保留天空光，将方块光抬到指定的亮度。 */
    fun lightWithMinimumBlock(packed: Int, minimum: Double): Int {
        val block = (minimum * 16.0).toInt().coerceIn(0, 240)
        if (block <= LightCoordsUtil.smoothBlock(packed)) return packed
        return LightCoordsUtil.smoothPack(block, LightCoordsUtil.smoothSky(packed))
    }

    /** 玩家所在服务端关卡。1.21.1 的 `Entity.level()` 静态类型是 `Level`，需要下转。 */
    fun serverLevelOf(player: ServerPlayer): ServerLevel = player.level()

    /** 按 UUID 找客户端世界里的实体。26.x 的 `ClientLevel.getEntity(UUID)` 是 O(1) 直查。 */
    fun clientEntityByUuid(level: ClientLevel, uuid: java.util.UUID) = level.getEntity(uuid)

    /** 标记区块 section 需要重建。26.x 走 `Minecraft.levelExtractor`。 */
    fun markSectionDirty(sectionX: Int, sectionY: Int, sectionZ: Int) {
        Minecraft.getInstance().levelExtractor.setSectionDirty(sectionX, sectionY, sectionZ)
    }

    /** FOV 覆盖：26.x 的 fov 字段是 float。 */
    fun applyFov(event: net.neoforged.neoforge.client.event.ViewportEvent.ComputeFov, fov: Float) {
        event.fov = fov
    }

    /** 写一个像素。26.x 是 `setPixel`，1.21.1 是 `setPixelRGBA`。 */
    fun setPixel(image: NativeImage, x: Int, y: Int, argb: Int) = image.setPixel(x, y, argb)

    /** 读一个像素。 */
    fun getPixel(image: NativeImage, x: Int, y: Int): Int = image.getPixel(x, y)

    /** 建动态贴图。26.x 需要带名字的 `Supplier`。 */
    fun dynamicTexture(name: String, image: NativeImage) = DynamicTexture({ name }, image)

    /** 登记失败时释放。26.x 由 `DynamicTexture.close()` 连带释放底图。 */
    fun closeTexture(texture: DynamicTexture, image: NativeImage) = texture.close()

    /** 命令权限：只允许游戏管理员。 */
    fun gamemastersOnly(): Predicate<CommandSourceStack> = Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)
}
