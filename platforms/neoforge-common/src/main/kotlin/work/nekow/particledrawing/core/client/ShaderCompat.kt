package work.nekow.particledrawing.core.client

import net.neoforged.fml.ModList
import org.apache.logging.log4j.LogManager
import work.nekow.particledrawing.config.ParticleDrawingConfig
import java.lang.reflect.Method

/**
 * 光影包兼容状态。
 *
 * Iris 只认识原版管线和自己声明的程序，模组自建的管线（加法混合、实例化粒子、场景材质）在光影包
 * 生效时可能不被绘制。这里反射读 Iris 的公开 API 判断“有没有光影包正在用”，命中时让这些粒子退回
 * 原版管线。用反射而不是编译期依赖，既能保持 Iris 可选，也方便 1.21.1 / 26.2 / 26.3 共用一份代码。
 */
object ShaderCompat {
    private val LOGGER = LogManager.getLogger("ParticleDrawing")
    private const val REFRESH_NANOS = 1_000_000_000L
    private const val IRIS_API = "net.irisshaders.iris.api.v0.IrisApi"

    private val lock = Any()
    private var resolved: Resolved? = null
    private var resolveFailed = false

    @Volatile
    private var active = false

    @Volatile
    private var lastCheck = 0L

    @Volatile
    private var announced = false

    private class Resolved(val instance: Any, val inUse: Method)

    /** 是否该退回原版管线：光影包生效时自动退回，调试开关可强制打开。 */
    fun legacyPipelines(): Boolean =
        ParticleDrawingConfig.CLIENT.forceLegacyPipelines.get() || (ParticleDrawingConfig.CLIENT.shaderCompat.get() && shaderpackActive())

    /** 有无光影包正在生效。读不到 Iris API 时按未启用来处理，避免光影包缺失或换版本时误降级。 */
    fun shaderpackActive(): Boolean {
        val now = System.nanoTime()
        if (now - lastCheck < REFRESH_NANOS) return active
        lastCheck = now
        val current = query()
        if (current && !announced) {
            announced = true
            LOGGER.info("[PD-compat] 光影包已启用，模组自建管线退回原版管线（加法混合 / 实例化配方 / 场景材质）")
        }
        active = current
        return current
    }

    private fun query(): Boolean {
        if (ModList.get()?.isLoaded("iris") != true) return false
        val api = resolve() ?: return false
        return try {
            (api.inUse.invoke(api.instance) as? Boolean) == true
        } catch (t: Throwable) {
            false
        }
    }

    private fun resolve(): Resolved? {
        resolved?.let { return it }
        if (resolveFailed) return null
        synchronized(lock) {
            resolved?.let { return it }
            if (resolveFailed) return null
            return try {
                val api = Class.forName(IRIS_API)
                val instance = api.getMethod("getInstance").invoke(null)
                    ?: throw IllegalStateException("IrisApi.getInstance() 返回 null")
                Resolved(instance, api.getMethod("isShaderPackInUse")).also { resolved = it }
            } catch (t: Throwable) {
                resolveFailed = true
                null
            }
        }
    }
}
