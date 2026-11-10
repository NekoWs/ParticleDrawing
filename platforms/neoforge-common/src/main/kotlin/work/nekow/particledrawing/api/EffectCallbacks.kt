package work.nekow.particledrawing.api

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// 特效播放回调：按播放 ID 注册，服务端线程注册与触发。
// onFinished 在播放结束（自然播完或 stop）时触发一次，随后清空该 ID 的全部回调；
// onParamChanged 在 setParam/updateVariable 时触发。

object EffectCallbacks {

    private val finished = ConcurrentHashMap<UUID, MutableList<(UUID) -> Unit>>()
    private val paramChanged = ConcurrentHashMap<UUID, MutableList<(UUID, String, String) -> Unit>>()

    @JvmStatic
    fun onFinished(playbackId: UUID, cb: (UUID) -> Unit) {
        finished.computeIfAbsent(playbackId) { mutableListOf() }.add(cb)
    }

    @JvmStatic
    fun onParamChanged(playbackId: UUID, cb: (UUID, String, String) -> Unit) {
        paramChanged.computeIfAbsent(playbackId) { mutableListOf() }.add(cb)
    }

    @JvmStatic
    fun fireFinished(playbackId: UUID) {
        val list = finished.remove(playbackId) ?: return
        for (cb in list) cb(playbackId)
    }

    @JvmStatic
    fun fireParamChanged(playbackId: UUID, name: String, value: String) {
        val list = paramChanged[playbackId] ?: return
        for (cb in list) cb(playbackId, name, value)
    }

    @JvmStatic
    fun clear(playbackId: UUID) {
        finished.remove(playbackId)
        paramChanged.remove(playbackId)
    }
}

/**
 * 变量覆盖记忆：返回最近一次 setParam/updateVariable 设置的值，从未设置过返回 null。
 * 服务器读不到客户端运行时里的当前 base，所以这里不是实时值。
 */
class EffectVarStore {

    private val last = ConcurrentHashMap<String, String>()

    fun set(name: String, value: String) {
        last[name] = value
    }

    fun get(name: String): String? = last[name]

    /** 按数字读回；设置过但不是数字时返回 null。 */
    fun getDouble(name: String): Double? {
        val v = last[name] ?: return null
        return v.toDoubleOrNull()
    }

    fun size(): Int = last.size
}