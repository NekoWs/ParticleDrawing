package work.nekow.particledrawing.core.client

import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import work.nekow.particledrawing.animation.AnimationLoader
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/**
 * 自定义贴图缓存：把编辑器工程里的 textures/<name>.png 读成 DynamicTexture 并注册进 TextureManager。
 *
 * 每个贴图名映射到一个独立 Identifier（`particledrawing:custom/<sanitized-name>`），
 * 由其 URL 在渲染时被粒子的自定义 Layer.textureAtlasLocation 引用（26.2 渲染按该 id 绑 Sampler0）。
 *
 * 贴图在 <gameDir>/animations/textures/<name>.png（与 pdraw 同根）。
 * 注意：TextureManager 未命中会回退注册 SimpleTexture（资源包/missing），故必须先 register 再渲染。
 *
 * 仅客户端使用（依赖 Minecraft#getInstance 等客户端 API，天然隔离；调用端需自行保证在客户端
 * 环境，见 ParticleDrawCommands#reloadTextures 的 dist 判断）。
 */
object TextureCache {

    private val namespace = "particledrawing"
    private val prefix = "custom"

    /** 已成功注册的贴图 → Identifier（供粒子 Layer 引用）与原始尺寸（供 UV 归一化）。 */
    data class Entry(val id: Identifier, val width: Int, val height: Int)

    private val entries = ConcurrentHashMap<String, Entry>()

    /** 贴图名 → 已注册的纹理信息；未加载/失败返回 null。 */
    @JvmStatic
    fun get(textureName: String): Entry? = entries[textureName]

    /**
     * 加载并注册一张贴图（幂等；已注册则直接返回）。线程调用方应保证在主线程。
     * @return 注册成功的纹理信息；读取/解码失败返回 null
     */
    @JvmStatic
    fun load(textureName: String): Entry? {
        entries[textureName]?.let { return it }
        val file = AnimationLoader.TEXTURE_DIRECTORY.resolve(sanitize(textureName) + ".png")
        if (!Files.exists(file)) return null
        val img: NativeImage
        try {
            Files.newInputStream(file).use { img = NativeImage.read(it) }
        } catch (e: IOException) {
            return null
        } catch (e: NullPointerException) { // 解码失败
            return null
        }
        val w = img.width
        val h = img.height
        val dt = DynamicTexture({ "PD:$textureName" }, img)
        val id = Identifier.fromNamespaceAndPath(namespace, prefix + "/" + sanitize(textureName))
        Minecraft.getInstance().textureManager.register(id, dt)
        val entry = Entry(id, w, h)
        entries[textureName] = entry
        return entry
    }

    /** 清理缓存（可选：动画停止后释放；暂只清 map，动态纹理随 TextureManager.close 释放）。 */
    @JvmStatic
    fun clear() {
        entries.clear()
    }

    private fun sanitize(name: String): String = name.replace(Regex("[^a-zA-Z0-9_\\-.]"), "_")
}
