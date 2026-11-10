package work.nekow.particledrawing.core.client

import work.nekow.particledrawing.platform.toMinecraft


import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import org.apache.logging.log4j.LogManager
import work.nekow.particledrawing.api.ParticleStyle
import work.nekow.particledrawing.api.ParticleTextures
import work.nekow.particledrawing.util.BuiltinTextures
import work.nekow.particledrawing.util.HashUtils
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// 自定义贴图缓存：把 PNG 字节（.pdrawc 内嵌或程序化 registerTexture 下发）解码为 DynamicTexture 并注册进 TextureManager。
// 贴图名映射到 particledrawing:custom/<md5-hex>，同名同图。
object TextureCache {

    private val LOGGER = LogManager.getLogger("ParticleDrawing")

    private const val NAMESPACE = "particledrawing"
    private const val PREFIX = "custom"
    private const val DEFAULT_WHITE = "default_white"

    /** 已成功注册的贴图 → Identifier 与原始尺寸，供 UV 归一化。 */
    data class Entry(val id: Identifier, val width: Int, val height: Int, val animation: ParticleSpriteAnimation? = null)

    private val entries = ConcurrentHashMap<String, Entry>()

    // 贴图表版本：每次成功注册或清空 +1；桥接粒子靠它发现生成后才到货的贴图并重新解析
    private val versionCounter = AtomicInteger()

    /** 贴图表版本号，只在主线程读。 */
    @JvmStatic
    fun version(): Int = versionCounter.get()

    /** 内置形状按需生成，particle: 引用按当前客户端资源包读取，其余名字查登记表。 */
    @JvmStatic
    fun get(textureName: String): Entry? {
        entries[textureName]?.let { return it }
        try {
            ParticleTextures.parse(textureName)?.let { reference ->
                val resources = Minecraft.getInstance().resourceManager
                val sprites = ParticleTextures.resolveFrames(reference) { id ->
                    resources.openAsReader(id.toMinecraft()).use { it.readText() }
                }
                val images = ArrayList<NativeImage>()
                try {
                    for (sprite in sprites) images += resources.open(sprite.toMinecraft()).use { NativeImage.read(it) }
                    if (images.size == 1) return registerImage(textureName, images.removeAt(0))
                    val width = images.first().width
                    val height = images.first().height
                    require(images.all { it.width == width && it.height == height }) { "粒子贴图序列的帧尺寸必须一致" }
                    require(width.toLong() * height * images.size <= 16_777_216L) { "粒子贴图序列超过像素上限" }
                    val atlas = NativeImage(width, height * images.size, false)
                    try {
                        for ((index, image) in images.withIndex()) {
                            for (y in 0 until height) for (x in 0 until width) {
                                atlas.setPixel(x, index * height + y, image.getPixel(x, y))
                            }
                        }
                    } catch (error: Exception) {
                        atlas.close()
                        throw error
                    }
                    return registerImage(textureName, atlas, ParticleSpriteAnimation(height, images.size, reference.fps))
                } finally {
                    images.forEach { it.close() }
                }
            }
        } catch (error: Exception) {
            // 缓存错误贴图，避免每颗粒子反复读缺失资源；重载资源包后可重新解析。
            LOGGER.error("ParticleDrawing: 粒子贴图 {} 加载失败：{}", textureName, error.message)
            val pixels = IntArray(16 * 16) { i ->
                if (((i % 16) / 8 + (i / 16) / 8) % 2 == 0) 0xFFFF00FF.toInt() else 0xFF000000.toInt()
            }
            return registerPixels(textureName, 16, 16, pixels)
        }
        val style = ParticleStyle.fromTextureName(textureName) ?: return null
        val pixels = BuiltinTextures.pixels(style, BuiltinTextures.SIZE)
        if (pixels.isEmpty()) return null
        return registerPixels(textureName, BuiltinTextures.SIZE, BuiltinTextures.SIZE, pixels)
    }

    /** 名字是否已注册（含内置形状）。 */
    @JvmStatic
    fun has(textureName: String): Boolean = get(textureName) != null

    /**
     * 从 PNG 字节数组加载并注册贴图，已注册则直接返回。
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

    /** 按 ARGB 像素注册贴图，内置形状用；不会出现在外部文件里。 */
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

    private fun registerImage(textureName: String, img: NativeImage, animation: ParticleSpriteAnimation? = null): Entry {
        val w = img.width
        val h = img.height
        val dt = try {
            DynamicTexture({ "PD:$textureName" }, img)
        } catch (e: Exception) {
            img.close()
            throw e
        }
        val id = Identifier.fromNamespaceAndPath(NAMESPACE, PREFIX + "/" + sanitize(textureName))
        try {
            Minecraft.getInstance().textureManager.register(id, dt)
        } catch (e: Exception) {
            // register 抛出时纹理系统还没接管 NativeImage，必须由这里释放。
            dt.close()
            throw e
        }
        val entry = Entry(id, w, h, animation)
        entries[textureName] = entry
        versionCounter.incrementAndGet()
        return entry
    }

    /** 清理全部缓存，内置形状会在下次用到时就地重建。 */
    @JvmStatic
    fun clear() {
        entries.clear()
        versionCounter.incrementAndGet()
    }

    /** 资源包重载时只失效资源引用，内嵌与服务端下发的 PNG 保持可用。客户端主线程调用。 */
    @JvmStatic
    fun clearParticleResources() {
        val textures = Minecraft.getInstance().textureManager
        for ((name, entry) in entries) {
            if (!name.startsWith("particle:")) continue
            if (entries.remove(name, entry)) textures.release(entry.id)
        }
        versionCounter.incrementAndGet()
    }

    /**
     * 无贴图粒子的默认全白纹理（8×8 实心白色），替代原版 generic_0 的单像素点，
     * 使游戏内默认粒子与编辑器中的全白方形点尺寸一致。
     */
    @JvmStatic
    fun defaultWhite(): Entry {
        entries[DEFAULT_WHITE]?.let { return it }
        val img = NativeImage(8, 8, false)
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                img.setPixel(x, y, -1) // ARGB 0xFFFFFFFF，不透明白
            }
        }
        val dt = try {
            DynamicTexture({ "PD:$DEFAULT_WHITE" }, img)
        } catch (e: Exception) {
            img.close()
            throw e
        }
        val id = Identifier.fromNamespaceAndPath(NAMESPACE, PREFIX + "/" + DEFAULT_WHITE)
        try {
            Minecraft.getInstance().textureManager.register(id, dt)
        } catch (e: Exception) {
            dt.close()
            throw e
        }
        val entry = Entry(id, 8, 8)
        entries[DEFAULT_WHITE] = entry
        versionCounter.incrementAndGet()
        return entry
    }

    /** 贴图名 → Identifier path：MD5 hex，保证 [a-z0-9] 合法且不碰撞。 */
    private fun sanitize(name: String): String =
        HashUtils.toHex(MessageDigest.getInstance("MD5").digest(name.toByteArray(Charsets.UTF_8)))
}
