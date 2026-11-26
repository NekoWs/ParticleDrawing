package work.nekow.particledrawing.core.client

import net.neoforged.fml.ModList
import org.apache.logging.log4j.LogManager
import work.nekow.particledrawing.config.ParticleDrawingConfig
import java.lang.reflect.Method

/** 查询可选的 Iris，并按渲染帧固定自定义粒子提交方式。 */
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

    @Volatile
    private var legacyFrame = false

    @Volatile
    private var postShaderFrame = false

    /** 同一帧的几何提取、绘制与区块线程共用一份兼容状态。 */
    fun refreshFrame() {
        val config = ParticleDrawingConfig.CLIENT
        legacyFrame = config.forceLegacyPipelines.get()
        postShaderFrame = config.shaderCompat.get() && !legacyFrame && shaderpackActive()
    }

    fun legacyPipelines(): Boolean = legacyFrame

    fun postShaderParticles(): Boolean = postShaderFrame

    fun useLightPools(): Boolean = ParticleDrawingConfig.CLIENT.shaderLightPools.get() &&
        (postShaderFrame || legacyFrame)

    /** 有无光影包正在生效。读不到 Iris API 时按未启用来处理，避免光影包缺失或换版本时误降级。 */
    fun shaderpackActive(): Boolean {
        val now = System.nanoTime()
        if (now - lastCheck < REFRESH_NANOS) return active
        lastCheck = now
        val current = query()
        if (current && !announced) {
            announced = true
            LOGGER.info("[PD-compat] Iris shader pack enabled")
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
                    ?: throw IllegalStateException("IrisApi.getInstance() returned null")
                Resolved(instance, api.getMethod("isShaderPackInUse")).also { resolved = it }
            } catch (t: Throwable) {
                resolveFailed = true
                null
            }
        }
    }
}
