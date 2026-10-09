package work.nekow.particledrawing.core

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import work.nekow.particledrawing.api.ParticleStyle
import work.nekow.particledrawing.util.HashUtils
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 程序化贴图登记表：双端共用一份逻辑，不依赖任何客户端/服务端类。
 *
 * - 服务端：`ParticleManager.registerTexture` 登记 PNG 字节并分配 id；贴图连同 id 下发给客户端，
 *   逐粒子载荷只写数值 id（协议字段是 id，不是名字）。
 * - 客户端：收到下发后 [declare] 记下 id → 名字，字节由 `TextureCache` 解码；本地登记的贴图也进同一张表。
 * - 内置形状（[ParticleStyle]）两端各自生成同一份像素，占用固定 id（1..99），不需要下发。
 *
 * 同名重复登记以第一次为准，贴图内容不热替换。
 */
object TextureRegistry {

    private val LOGGER: Logger = LogManager.getLogger("ParticleDrawing")

    /** 贴图名长度上限。 */
    const val MAX_NAME_LENGTH = 256

    /** 单张贴图字节上限；超过直接拒绝。 */
    const val MAX_BYTES = 1 shl 20

    /** 用户贴图的 id 起点（1..99 留给内置形状）。 */
    private const val FIRST_USER_ID = 100

    /** 一条登记：名字 + 协议 id；内置形状两端自带，字节可能缺失。 */
    class Entry internal constructor(
        val name: String,
        val id: Int,
        val builtin: Boolean,
    ) {
        @Volatile
        var bytes: ByteArray? = null
            internal set
    }

    private val byName = ConcurrentHashMap<String, Entry>()
    private val byId = ConcurrentHashMap<Int, Entry>()
    private val nextUserId = AtomicInteger(FIRST_USER_ID)

    init {
        for (style in ParticleStyle.entries) {
            val name = style.textureName ?: continue
            val entry = Entry(name, style.textureId, true)
            byName[name] = entry
            byId[style.textureId] = entry
        }
    }

    /** 名字是否合法（非空、不超长、无控制字符）。 */
    @JvmStatic
    fun isValidName(name: String): Boolean {
        if (name.isEmpty() || name.length > MAX_NAME_LENGTH) return false
        for (c in name) {
            if (c.code < 0x20) return false
        }
        return true
    }

    /** 按名字取登记；未登记返回 null。 */
    @JvmStatic
    fun byName(name: String): Entry? = byName[name]

    /** 按协议 id 取登记；客户端没收到对应下发包时返回 null（此时调用方回落纯白方块）。 */
    @JvmStatic
    fun byId(id: Int): Entry? = byId[id]

    /** 协议 id → 贴图名；未知 id 返回 null。 */
    @JvmStatic
    fun nameOf(id: Int): String? = byId[id]?.name

    /**
     * 登记一张贴图，服务端为主，客户端本地登记也走这里。
     *
     * @return 登记项；名字或数据非法返回 null，同名重复登记返回已有项并只记一条警告
     */
    @JvmStatic
    fun register(name: String, pngBytes: ByteArray): Entry? {
        if (!isValidName(name)) {
            LOGGER.warn("ParticleDrawing: 贴图名非法，已忽略（长度 {}）", name.length)
            return null
        }
        if (pngBytes.isEmpty() || pngBytes.size > MAX_BYTES) {
            LOGGER.warn("ParticleDrawing: 贴图 {} 字节数非法（{}），已忽略", name, pngBytes.size)
            return null
        }
        val existing = byName[name]
        if (existing != null) {
            val known = existing.bytes
            if (known != null && HashUtils.sha1Hex(known) != HashUtils.sha1Hex(pngBytes)) {
                LOGGER.warn("ParticleDrawing: 贴图 {} 已登记且内容不同，按第一次的生效", name)
            }
            return existing
        }
        val entry = Entry(name, nextUserId.getAndIncrement(), false)
        entry.bytes = pngBytes
        byName[name] = entry
        byId[entry.id] = entry
        return entry
    }

    /**
     * 只登记「id → 名字」，客户端收到下发包时调用；字节还没到齐也能先按 id 找到名字。
     */
    @JvmStatic
    fun declare(id: Int, name: String): Entry? {
        if (id <= 0 || !isValidName(name)) return null
        byName[name]?.let { known ->
            byId[id] = known
            return known
        }
        val entry = Entry(name, id, false)
        byName[name] = entry
        byId[id] = entry
        return entry
    }

    /** 记下已解码成功的字节；客户端在 `/pdraw reload` 后可直接重载，无需等待重新下发。 */
    @JvmStatic
    fun storeBytes(id: Int, pngBytes: ByteArray) {
        byId[id]?.bytes = pngBytes
    }

    /** 全部已登记（含内置形状）的项。 */
    @JvmStatic
    fun all(): List<Entry> = byId.values.toList()

    /** 有字节可加载的项；内置形状没有字节，由客户端按需生成。 */
    @JvmStatic
    fun allWithBytes(): List<Entry> = byId.values.filter { it.bytes != null }

    /**
     * 换连接时清掉从服务器学来的映射（不同服务器的 id 空间互相独立），保留本地登记（有字节）的项。
     */
    @JvmStatic
    fun clearDeclared() {
        val it = byId.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next().value
            if (entry.builtin || entry.bytes != null) continue
            it.remove()
            byName.remove(entry.name, entry)
        }
    }
}
