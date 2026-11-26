package work.nekow.particledrawing.core.client

import work.nekow.particledrawing.platform.toMinecraft


import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import work.nekow.particledrawing.platform.PlatformCompat
import org.apache.logging.log4j.LogManager
import work.nekow.particledrawing.api.ParticleSheets
import work.nekow.particledrawing.api.ParticleStyle
import work.nekow.particledrawing.api.ParticleTextures
import work.nekow.particledrawing.util.BuiltinTextures
import work.nekow.particledrawing.util.HashUtils
import java.io.BufferedInputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.translation

// 自定义贴图缓存：把 PNG 字节（.pdrawc 内嵌或程序化 registerTexture 下发）解码为 DynamicTexture 并注册进 TextureManager。
// 贴图名映射到 particledrawing:custom/<md5-hex>，同名同图。
object TextureCache {

    private val LOGGER = LogManager.getLogger("ParticleDrawing")

    private const val NAMESPACE = "particledrawing"
    private const val PREFIX = "custom"
    private const val DEFAULT_WHITE = "default_white"

    /** 已成功注册的贴图 → Identifier 与原始尺寸，供 UV 归一化。 */
    data class Entry(val id: Identifier, val width: Int, val height: Int, val animation: ParticleSpriteAnimation? = null,
                     val translucent: Boolean = false)

    /** 单张贴图解码上限：16384×1024 之类的极端比例也允许，只要总像素不超 1600 万。 */
    private const val MAX_DIMENSION = 16384
    private const val MAX_PIXELS = 16_777_216L
    private const val SHEET_CACHE_PIXELS = 110_000_000L
    private const val SHEET_IDLE_NANOS = 8_000_000_000L
    private val sheetUse = linkedMapOf<String, Long>()
    private var sheetPixels = 0L

    private val entries = ConcurrentHashMap<String, Entry>()

    // 贴图表版本：每次成功注册或清空 +1；桥接粒子靠它发现生成后才到货的贴图并重新解析
    private val versionCounter = AtomicInteger()

    /** 贴图表版本号，只在主线程读。 */
    @JvmStatic
    fun version(): Int = versionCounter.get()

    /** 内置形状按需生成，particle: 引用按当前客户端资源包读取，其余名字查登记表。 */
    @JvmStatic
    fun get(textureName: String): Entry? {
        entries[textureName]?.let {
            if (ParticleSheets.isReference(textureName)) touchSheet(textureName)
            return it
        }
        try {
            if (ParticleSheets.isReference(textureName)) return loadSheet(textureName)
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
                    requireLocalized(images.all { it.width == width && it.height == height }) { translation("error.texture.frame_dimensions") }
                    requireLocalized(width.toLong() * height * images.size <= 16_777_216L) { translation("error.texture.pixel_limit") }
                    val atlas = NativeImage(width, height * images.size, false)
                    try {
                        for ((index, image) in images.withIndex()) {
                            for (y in 0 until height) for (x in 0 until width) {
                                PlatformCompat.setPixel(atlas, x, index * height + y, PlatformCompat.getPixel(image, x, y))
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
            LOGGER.error(translation("log.texture.load").resolve(), textureName, error.message)
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

    private fun loadSheet(name: String): Entry {
        val resources = Minecraft.getInstance().resourceManager
        val sheet = ParticleSheets.read(name) { id -> resources.openAsReader(id.toMinecraft()).use { it.readText() } }
        resources.open(sheet.image.toMinecraft()).use { raw ->
            val stream = BufferedInputStream(raw)
            stream.mark(24)
            val size = requireNotNull(pngDimensions(stream.readNBytes(24)))
            stream.reset()
            val columns = size.first / sheet.width
            val rows = size.second / sheet.height
            val pixels = size.first.toLong() * size.second
            requireLocalized(pixels <= MAX_PIXELS && size.first % sheet.width == 0 &&
                size.second % sheet.height == 0 && sheet.frames <= columns * rows) {
                translation("error.texture.sheet", name)
            }
            trimSheets(pixels)
            val sprite = ParticleSpriteAnimation(sheet.height, sheet.frames, sheet.fps, sheet.width, columns)
            val entry = registerImage(name, NativeImage.read(stream), sprite)
            sheetUse[name] = System.nanoTime()
            sheetPixels += pixels
            return entry
        }
    }

    internal fun touchSheet(name: String) {
        sheetUse.computeIfPresent(name) { _, _ -> System.nanoTime() }
    }

    private fun trimSheets(incomingPixels: Long) {
        val now = System.nanoTime()
        val iterator = sheetUse.iterator()
        while (sheetPixels + incomingPixels > SHEET_CACHE_PIXELS && iterator.hasNext()) {
            val (name, touched) = iterator.next()
            if (now - touched < SHEET_IDLE_NANOS) continue
            iterator.remove()
            entries.remove(name)?.let {
                Minecraft.getInstance().textureManager.release(it.id)
                sheetPixels -= it.width.toLong() * it.height
                versionCounter.incrementAndGet()
            }
        }
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
        // 解码前先读 PNG 头：1MiB 的高压缩比 PNG 可以解成几百 MB 的位图，必须在分配前拒绝。
        val dimensions = pngDimensions(pngBytes) ?: return null
        if (dimensions.first.toLong() * dimensions.second > MAX_PIXELS) return null
        val img: NativeImage
        try {
            pngBytes.inputStream().use { img = NativeImage.read(it) }
        } catch (_: Exception) {
            return null
        }
        return registerImage(textureName, img)
    }

    /** 从 PNG 字节里读宽高（IHDR）；签名或长度不合法返回 null。 */
    private fun pngDimensions(pngBytes: ByteArray): Pair<Int, Int>? {
        if (pngBytes.size < 24) return null
        val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        for (i in signature.indices) if (pngBytes[i] != signature[i]) return null
        if (pngBytes[12] != 'I'.code.toByte() || pngBytes[13] != 'H'.code.toByte() ||
            pngBytes[14] != 'D'.code.toByte() || pngBytes[15] != 'R'.code.toByte()
        ) return null
        fun intAt(offset: Int): Int =
            ((pngBytes[offset].toInt() and 0xFF) shl 24) or
                ((pngBytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((pngBytes[offset + 2].toInt() and 0xFF) shl 8) or
                (pngBytes[offset + 3].toInt() and 0xFF)
        val width = intAt(16)
        val height = intAt(20)
        if (width !in 1..MAX_DIMENSION || height !in 1..MAX_DIMENSION) return null
        return width to height
    }

    /** 按 ARGB 像素注册贴图，内置形状用；不会出现在外部文件里。 */
    @JvmStatic
    fun registerPixels(textureName: String, width: Int, height: Int, pixels: IntArray): Entry? {
        entries[textureName]?.let { return it }
        if (width <= 0 || height <= 0 || pixels.size < width * height) return null
        val img = NativeImage(width, height, false)
        for (y in 0 until height) {
            for (x in 0 until width) {
                PlatformCompat.setPixel(img, x, y, pixels[y * width + x])
            }
        }
        return registerImage(textureName, img)
    }

    private fun registerImage(textureName: String, img: NativeImage, animation: ParticleSpriteAnimation? = null): Entry {
        val w = img.width
        val h = img.height
        val translucent = hasTranslucency(img)
        val dt = try {
            PlatformCompat.dynamicTexture("PD:$textureName", img)
        } catch (e: Exception) {
            img.close()
            throw e
        }
        val id = Identifier.fromNamespaceAndPath(NAMESPACE, PREFIX + "/" + sanitize(textureName))
        try {
            Minecraft.getInstance().textureManager.register(id, dt)
        } catch (e: Exception) {
            // register 抛出时纹理系统还没接管 NativeImage，必须由这里释放。
            PlatformCompat.closeTexture(dt, img)
            throw e
        }
        val entry = Entry(id, w, h, animation, translucent)
        entries[textureName] = entry
        versionCounter.incrementAndGet()
        return entry
    }

    private fun hasTranslucency(img: NativeImage): Boolean {
        for (y in 0 until img.height) for (x in 0 until img.width) {
            if (PlatformCompat.getPixel(img, x, y) ushr 24 != 255) return true
        }
        return false
    }

    /** 清理全部缓存并释放已注册的动态贴图；内置形状会在下次用到时就地重建。 */
    @JvmStatic
    fun clear() {
        val textures = Minecraft.getInstance().textureManager
        for (entry in entries.values) {
            try {
                textures.release(entry.id)
            } catch (_: Exception) {
                // 贴图可能已随资源包重载释放过，忽略
            }
        }
        entries.clear()
        sheetUse.clear()
        sheetPixels = 0L
        versionCounter.incrementAndGet()
    }

    /** 资源包重载时只失效资源引用，内嵌与服务端下发的 PNG 保持可用。客户端主线程调用。 */
    @JvmStatic
    fun clearParticleResources() {
        val textures = Minecraft.getInstance().textureManager
        for ((name, entry) in entries) {
            if (!name.startsWith("particle:") && !ParticleSheets.isReference(name)) continue
            if (entries.remove(name, entry)) textures.release(entry.id)
        }
        sheetUse.clear()
        sheetPixels = 0L
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
                PlatformCompat.setPixel(img, x, y, -1) // ARGB 0xFFFFFFFF，不透明白
            }
        }
        val dt = try {
            PlatformCompat.dynamicTexture("PD:$DEFAULT_WHITE", img)
        } catch (e: Exception) {
            img.close()
            throw e
        }
        val id = Identifier.fromNamespaceAndPath(NAMESPACE, PREFIX + "/" + DEFAULT_WHITE)
        try {
            Minecraft.getInstance().textureManager.register(id, dt)
        } catch (e: Exception) {
            PlatformCompat.closeTexture(dt, img)
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
