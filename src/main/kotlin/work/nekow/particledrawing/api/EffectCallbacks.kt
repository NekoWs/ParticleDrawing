package work.nekow.particledrawing.api

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// 特效播放回调与变量记忆（阶段 5 API；纯 Kotlin，无 Minecraft 依赖，可单测）。
//
// 回调注册按播放 ID（Effects.play 返回的句柄 ID）：
// - onFinished：播放结束（自然播完或 stop）时触发一次，随后清空该 ID 的全部回调；
// - onParamChanged：setParam/updateVariable 成功后触发（本地覆盖触发）。
// 线程：服务端线程注册与触发；用 ConcurrentHashMap 保证不因并发散架。

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
 * 变量覆盖记忆：getParam 的诚实实现。
 * 服务器收不到客户端运行时里的当前 base（变量状态在客户端动画运行时），所以 getParam 返回的是
 * **最近一次通过 setParam/updateVariable 设置的值**；从未设置过返回 null。文档与指南里如实标注。
 */
class EffectVarStore {

    private val last = ConcurrentHashMap<String, String>()

    fun set(name: String, value: String) {
        last[name] = value
    }

    fun get(name: String): String? = last[name]

    /** 按数字读回（设置过但不是数字时返回 null——与 §5.4「覆盖未知变量返回 false」一致的诚实失败）。 */
    fun getDouble(name: String): Double? {
        val v = last[name] ?: return null
        return v.toDoubleOrNull()
    }

    fun size(): Int = last.size
}