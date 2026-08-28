package work.nekow.particledrawing.core.client

import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import work.nekow.particledrawing.util.HashUtils
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 自定义贴图缓存：把 pdraw 内嵌的 PNG 字节解码为 DynamicTexture 并注册进 TextureManager。
 *
 * 每个贴图名映射到一个 Identifier（`particledrawing:custom/<md5-hex>`），
 * 渲染时由 BridgeParticle 的自定义 Layer.textureAtlasLocation 引用。
 *
 * 贴图数据内嵌于 .pdraw v4 的 `texData` 字段（base64 PNG），不再依赖外部文件。
 */
object TextureCache {

    private const val NAMESPACE = "particledrawing"
    private const val PREFIX = "custom"
    private const val DEFAULT_WHITE = "default_white"

    /** 已成功注册的贴图 → Identifier 与原始尺寸（供 UV 归一化）。 */
    data class Entry(val id: Identifier, val width: Int, val height: Int)

    private val entries = ConcurrentHashMap<String, Entry>()

    /** 贴图名 → 已注册的纹理信息；未加载/失败返回 null。 */
    @JvmStatic
    fun get(textureName: String): Entry? = entries[textureName]

    /**
     * 从内嵌的 PNG 字节数组加载并注册贴图（幂等；已注册则直接返回）。
     * @return 注册成功的纹理信息；解码失败返回 null
     */
    @JvmStatic
    fun load(textureName: String, pngBytes: ByteArray): Entry? {
        entries[textureName]?.let { return it }
        val img: NativeImage
        try {
            pngBytes.inputStream().use { img = NativeImage.read(it) }
        } catch (_: Exception) {
            return null
        }
        val w = img.width
        val h = img.height
        val dt = DynamicTexture({ "PD:$textureName" }, img)
        val id = Identifier.fromNamespaceAndPath(NAMESPACE, PREFIX + "/" + sanitize(textureName))
        Minecraft.getInstance().textureManager.register(id, dt)
        val entry = Entry(id, w, h)
        entries[textureName] = entry
        return entry
    }

    /** 清理全部缓存。 */
    @JvmStatic
    fun clear() {
        entries.clear()
    }

    /**
     * 无贴图粒子的默认全白纹理（8×8 实心白色），替代原版 generic_0 的单像素点：
     * 使游戏内默认粒子与编辑器中的全白方形点尺寸一致。
     */
    @JvmStatic
    fun defaultWhite(): Entry {
        entries[DEFAULT_WHITE]?.let { return it }
        val img = NativeImage(8, 8, false)
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                img.setPixel(x, y, -1) // ARGB 0xFFFFFFFF → 不透明白
            }
        }
        val dt = DynamicTexture({ "PD:$DEFAULT_WHITE" }, img)
        val id = Identifier.fromNamespaceAndPath(NAMESPACE, PREFIX + "/" + DEFAULT_WHITE)
        Minecraft.getInstance().textureManager.register(id, dt)
        val entry = Entry(id, 8, 8)
        entries[DEFAULT_WHITE] = entry
        return entry
    }

    /**
     * 贴图名 → Identifier path（MD5 hex，保证 [a-z0-9] 合法且不碰撞）。
     */
    private fun sanitize(name: String): String =
        HashUtils.toHex(MessageDigest.getInstance("MD5").digest(name.toByteArray(Charsets.UTF_8)))
}
