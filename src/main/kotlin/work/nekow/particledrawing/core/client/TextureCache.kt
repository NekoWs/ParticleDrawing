package work.nekow.particledrawing.core.client

import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import work.nekow.particledrawing.api.ParticleStyle
import work.nekow.particledrawing.util.BuiltinTextures
import work.nekow.particledrawing.util.HashUtils
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// 自定义贴图缓存：把 PNG 字节（.pdrawc 内嵌 / 程序化 registerTexture 下发）解码为 DynamicTexture 并注册进 TextureManager。
// 贴图名映射到 particledrawing:custom/<md5-hex>，同名同图，与外部文件无关。
object TextureCache {

    private const val NAMESPACE = "particledrawing"
    private const val PREFIX = "custom"
    private const val DEFAULT_WHITE = "default_white"

    /** 已成功注册的贴图 → Identifier 与原始尺寸（供 UV 归一化）。 */
    data class Entry(val id: Identifier, val width: Int, val height: Int)

    private val entries = ConcurrentHashMap<String, Entry>()

    // 贴图表版本：每次成功注册/清空 +1。桥接粒子靠它发现「生成后才到货的贴图」并重新解析。
    private val versionCounter = AtomicInteger()

    /** 贴图表版本号（只在主线程读，比较用）。 */
    @JvmStatic
    fun version(): Int = versionCounter.get()

    /** 贴图名 → 已注册的纹理信息；未注册返回 null。内置形状名会就地生成（两端各生成同一份）。 */
    @JvmStatic
    fun get(textureName: String): Entry? {
        entries[textureName]?.let { return it }
        val style = ParticleStyle.fromTextureName(textureName) ?: return null
        val pixels = BuiltinTextures.pixels(style, BuiltinTextures.SIZE)
        if (pixels.isEmpty()) return null
        return registerPixels(textureName, BuiltinTextures.SIZE, BuiltinTextures.SIZE, pixels)
    }

    /** 名字是否已注册（含内置形状）。 */
    @JvmStatic
    fun has(textureName: String): Boolean = get(textureName) != null

    /**
     * 从 PNG 字节数组加载并注册贴图（幂等；已注册则直接返回）。
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
        return registerImage(textureName, img)
    }

    /** 按 ARGB 像素注册贴图（内置形状用；不会出现在外部文件里）。 */
    @JvmStatic
    fun registerPixels(textureName: String, width: Int, height: Int, pixels: IntArray): Entry? {
        entries[textureName]?.let { return it }
        if (width <= 0 || height <= 0 || pixels.size < width * height) return null
        val img = NativeImage(width, height, false)
        for (y in 0 until height) {
            for (x in 0 until width) {
                img.setPixel(x, y, pixels[y * width + x])
            }
        }
        return registerImage(textureName, img)
    }

    private fun registerImage(textureName: String, img: NativeImage): Entry {
        val w = img.width
        val h = img.height
        val dt = DynamicTexture({ "PD:$textureName" }, img)
        val id = Identifier.fromNamespaceAndPath(NAMESPACE, PREFIX + "/" + sanitize(textureName))
        Minecraft.getInstance().textureManager.register(id, dt)
        val entry = Entry(id, w, h)
        entries[textureName] = entry
        versionCounter.incrementAndGet()
        return entry
    }

    /** 清理全部缓存（内置形状会在下次用到时就地重建）。 */
    @JvmStatic
    fun clear() {
        entries.clear()
        versionCounter.incrementAndGet()
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
        versionCounter.incrementAndGet()
        return entry
    }

    /**
     * 贴图名 → Identifier path（MD5 hex，保证 [a-z0-9] 合法且不碰撞）。
     */
    private fun sanitize(name: String): String =
        HashUtils.toHex(MessageDigest.getInstance("MD5").digest(name.toByteArray(Charsets.UTF_8)))
}
