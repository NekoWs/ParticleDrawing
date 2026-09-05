package work.nekow.particledrawing.api

import net.minecraft.resources.Identifier
import work.nekow.particledrawing.animation.PdrawcReader
import java.util.concurrent.ConcurrentHashMap

/**
 * 特效注册表：按 [Identifier] key 注册 .pdrawc 字节（注册时验签）。
 *
 * - 服务端：注册后可由 [Effects.play] 按 key 播放；客户端缺失时按需向服务端请求字节。
 * - 客户端：注册后可由 [ClientEffects.play] 纯本地播放（不经服务端）。
 */
object EffectRegistry {

    private val effects = ConcurrentHashMap<Identifier, ByteArray>()

    /**
     * 注册一个 .pdrawc 特效字节；验签失败返回 false（不注册）。
     */
    @JvmStatic
    fun register(key: Identifier, data: ByteArray): Boolean {
        if (!PdrawcReader.verify(data)) return false
        effects[key] = data
        return true
    }

    /** 是否已注册该 key。 */
    @JvmStatic
    fun contains(key: Identifier): Boolean = effects.containsKey(key)

    /** 取注册字节；未注册返回 null。 */
    @JvmStatic
    fun dataFor(key: Identifier): ByteArray? = effects[key]

    /** 当前注册 key 快照。 */
    @JvmStatic
    fun keys(): Set<Identifier> = effects.keys.toSet()
}