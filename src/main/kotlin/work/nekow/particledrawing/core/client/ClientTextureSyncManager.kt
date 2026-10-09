package work.nekow.particledrawing.core.client

import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.api.distmarker.Dist
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import work.nekow.particledrawing.ParticleDrawing
import work.nekow.particledrawing.api.ParticleStyle
import work.nekow.particledrawing.core.TextureRegistry
import work.nekow.particledrawing.core.network.ParticleTexturePayload
import java.io.ByteArrayOutputStream

/**
 * 客户端程序化贴图接收：按 id 累积分块，收齐后解码注册进 [TextureCache]。
 *
 * 只学到「id → 名字」也算数：同 id 的粒子在字节到齐前渲染成纯白方块，不丢粒子；
 * 名字先登记，是为了让本地按名登记的贴图也能被 id 引用命中。
 */
object ClientTextureSyncManager {

    private val LOGGER: Logger = LogManager.getLogger("ParticleDrawing")

    /** 正在累积的贴图（id → 字节流）。 */
    private val pending = LinkedHashMap<Int, ByteArrayOutputStream>()

    /** 接收一个贴图块。 */
    @JvmStatic
    fun onChunk(payload: ParticleTexturePayload) {
        TextureRegistry.declare(payload.id, payload.name)
        val out = pending.getOrPut(payload.id) { ByteArrayOutputStream() }
        if (out.size() + payload.data.size > TextureRegistry.MAX_BYTES) {
            LOGGER.warn("ParticleDrawing: 贴图 {} 超过字节上限，已丢弃", payload.name)
            pending.remove(payload.id)
            return
        }
        out.write(payload.data)
        if (!payload.eof) return

        pending.remove(payload.id)
        val bytes = out.toByteArray()
        if (loadLocal(payload.name, bytes)) {
            // 留一份字节，`/pdraw reload` 清空缓存后能就地重载
            TextureRegistry.storeBytes(payload.id, bytes)
        } else {
            LOGGER.warn("ParticleDrawing: 贴图 {} 解码失败，引用它的粒子回落纯白方块", payload.name)
        }
    }

    /**
     * 本机直接解码注册（客户端侧登记、以及收到完整贴图块时调用）。
     * @return 解码并注册成功为 true
     */
    @JvmStatic
    fun loadLocal(name: String, bytes: ByteArray): Boolean {
        return try {
            TextureCache.load(name, bytes) != null
        } catch (e: Exception) {
            LOGGER.warn("ParticleDrawing: 贴图 {} 注册失败：{}", name, e.message)
            false
        }
    }

    /** 重新加载全部有字节的登记（贴图缓存被清空后调用）。 */
    @JvmStatic
    fun reloadAll(): Int {
        var loaded = 0
        for (entry in TextureRegistry.allWithBytes()) {
            val bytes = entry.bytes ?: continue
            if (loadLocal(entry.name, bytes)) loaded++
        }
        return loaded
    }

    /** 就地生成全部内置形状贴图（幂等）；返回可用的内置贴图数。 */
    @JvmStatic
    fun ensureBuiltins(): Int {
        var count = 0
        for (style in ParticleStyle.entries) {
            val name = style.textureName ?: continue
            if (TextureCache.get(name) != null) count++
        }
        return count
    }

    /** 换连接：丢掉未收齐的块与从服务器学来的 id → 名字映射；各服务器的 id 空间独立。 */
    @JvmStatic
    fun clearRemote() {
        pending.clear()
        TextureRegistry.clearDeclared()
    }
}

/** 客户端生命周期：换服务器时清掉上一台服务器的贴图 id 映射。 */
@EventBusSubscriber(modid = ParticleDrawing.MODID, value = [Dist.CLIENT])
@Suppress("unused")
object ClientTextureLifecycle {

    @SubscribeEvent
    @JvmStatic
    fun onLoggingIn(event: ClientPlayerNetworkEvent.LoggingIn) {
        ClientTextureSyncManager.clearRemote()
    }
}
