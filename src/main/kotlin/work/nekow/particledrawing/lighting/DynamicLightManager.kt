package work.nekow.particledrawing.lighting

import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.config.ParticleDrawingConfig
import work.nekow.particledrawing.core.client.ClientParticleEngine
import work.nekow.particledrawing.core.client.RenderParticle
import java.util.concurrent.locks.ReentrantReadWriteLock

@Suppress("unused")
object DynamicLightManager {

    private val LOCK = ReentrantReadWriteLock()
    private val ACTIVE_LIGHTS = ArrayList<LightEntry>()
    @Volatile
    private var lightCount = 0

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
            val dist = Math.sqrt(dx * dx + dy * dy + dz * dz)
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
                val dist = Math.sqrt(dx * dx + dy * dy + dz * dz)
                if (dist > maxDistConfig) continue

                val atten = LightAttenuation.SMOOTHSTEP.evaluate(dist.toFloat(), maxDistConfig.toFloat())
                val contrib = light.brightness * atten * 15.0
                if (contrib > maxContrib) {
                    maxContrib = contrib
                }
            }

            return Math.round(maxContrib).toInt()
        } finally {
            LOCK.readLock().unlock()
        }
    }

    @JvmStatic
    fun getDynamicLightPacked(x: Double, y: Double, z: Double): Int {
        val level = getDynamicLightLevel(x, y, z)
        if (level <= 0) return 0
        return (level shl 4) or level
    }

    private class LightEntry(
        val x: Double, val y: Double, val z: Double,
        val r: Float, val g: Float, val b: Float, val a: Float,
        val brightness: Float,
        val score: Float
    )
}
