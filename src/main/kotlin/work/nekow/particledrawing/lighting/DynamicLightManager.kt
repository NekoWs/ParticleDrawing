package work.nekow.particledrawing.lighting

import kotlin.math.round
import kotlin.math.sqrt
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import work.nekow.particledrawing.config.ParticleDrawingConfig
import work.nekow.particledrawing.core.client.ClientParticleEngine
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * 动态光源管理器。
 * 维护活跃动态光源列表，提供光照等级查询。
 * 每帧根据玩家距离和亮度评分筛选光源，线程安全。
 */
@Suppress("unused")
object DynamicLightManager {

    private val LOCK = ReentrantReadWriteLock()
    private val ACTIVE_LIGHTS = ArrayList<LightEntry>()
    @Volatile
    private var lightCount = 0

    /**
     * 每帧渲染时更新活跃光源列表。
     * 按亮度-距离加权评分排序后筛选光源。
     * @param engine 客户端粒子引擎
     * @param camera 当前相机
     */
    @JvmStatic
    fun renderDynamicLights(engine: ClientParticleEngine, camera: Camera) {
        if (!ParticleDrawingConfig.CLIENT.enableDynamicLights.get()) {
            LOCK.writeLock().lock()
            try {
                ACTIVE_LIGHTS.clear()
                lightCount = 0
            } finally {
                LOCK.writeLock().unlock()
            }
            return
        }

        val glowing = engine.getGlowingParticles()
        if (glowing.isEmpty()) {
            LOCK.writeLock().lock()
            try {
                ACTIVE_LIGHTS.clear()
                lightCount = 0
            } finally {
                LOCK.writeLock().unlock()
            }
            return
        }

        val camPos = Minecraft.getInstance().player?.position() ?: return
        val maxDist = ParticleDrawingConfig.CLIENT.dynamicLightMaxDistance.get()
        val maxLights = ParticleDrawingConfig.CLIENT.maxDynamicLights.get()

        val entries = mutableListOf<LightEntry>()
        for (idx in glowing.indices) {
            val p = glowing[idx]
            val dx = p.x() - camPos.x
            val dy = p.y() - camPos.y
            val dz = p.z() - camPos.z
            val dist = sqrt(dx * dx + dy * dy + dz * dz)
            if (dist > maxDist) continue

            val brightness = p.r() * 0.3f + p.g() * 0.59f + p.b() * 0.11f
            val score = brightness / (1.0 + dist * 0.1).toFloat()
            entries += LightEntry(p.x(), p.y(), p.z(), p.r(), p.g(), p.b(), p.a(), brightness, score)
        }

        entries.sortByDescending { it.score }

        LOCK.writeLock().lock()
        try {
            ACTIVE_LIGHTS.clear()
            val count = minOf(entries.size, maxLights)
            for (i in 0 until count) {
                ACTIVE_LIGHTS += entries[i]
            }
            lightCount = count
        } finally {
            LOCK.writeLock().unlock()
        }
    }

    /**
     * 获取指定位置的动态光照等级。
     * 遍历所有活跃光源，应用衰减函数后取最大值。
     * @param x X 坐标
     * @param y Y 坐标
     * @param z Z 坐标
     * @return 光照等级 (0-15)
     */
    @JvmStatic
    fun getDynamicLightLevel(x: Double, y: Double, z: Double): Int {
        if (lightCount == 0) return 0

        LOCK.readLock().lock()
        try {
            var maxContrib = 0.0
            val maxDistConfig = ParticleDrawingConfig.CLIENT.dynamicLightMaxDistance.get()

            for (light in ACTIVE_LIGHTS) {
                val dx = x - light.x
                val dy = y - light.y
                val dz = z - light.z
                val dist = sqrt(dx * dx + dy * dy + dz * dz)
                if (dist > maxDistConfig) continue

                val atten = LightAttenuation.SMOOTHSTEP.evaluate(dist.toFloat(), maxDistConfig.toFloat())
                val contrib = light.brightness * atten * 15.0
                if (contrib > maxContrib) {
                    maxContrib = contrib
                }
            }

            return round(maxContrib).toInt()
        } finally {
            LOCK.readLock().unlock()
        }
    }

    /**
     * 获取指定位置的打包动态光照值。
     * 将亮度等级同时编码到高位和低位，供渲染管线使用。
     * @param x X 坐标
     * @param y Y 坐标
     * @param z Z 坐标
     * @return 打包后的光照值
     */
    @JvmStatic
    fun getDynamicLightPacked(x: Double, y: Double, z: Double): Int {
        val level = getDynamicLightLevel(x, y, z)
        if (level <= 0) return 0
        return (level shl 4) or level
    }

    /**
     * 光源条目数据类。
     * 包含位置、颜色、亮度和加权评分。
     */
    private class LightEntry(
        val x: Double, val y: Double, val z: Double,
        val r: Float, val g: Float, val b: Float, val a: Float,
        val brightness: Float,
        val score: Float
    )
}
