package work.nekow.particledrawing.core.client

import net.minecraft.client.Minecraft
import net.minecraft.client.particle.ParticleEngine
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.api.ParticleStyle
import work.nekow.particledrawing.core.easing.EasingType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Suppress("unused")
class ClientParticleEngine {

    private val particles: MutableMap<UUID, RenderParticle> = ConcurrentHashMap()
    private val bridges: MutableMap<UUID, BridgeParticle> = ConcurrentHashMap()
    private val groups: MutableMap<UUID, MutableSet<UUID>> = ConcurrentHashMap()

    fun spawnParticle(id: UUID, style: ParticleStyle, x: Double, y: Double, z: Double,
                      r: Float, g: Float, b: Float, a: Float, scale: Float,
                      lifetimeTicks: Int, groupId: UUID?, glowing: Boolean) {
        val lifetimeMs = if (lifetimeTicks > 0) lifetimeTicks * 50L else 0L
        val rp = RenderParticle(id, style, Vec3(x, y, z),
            Color.of(r, g, b, a), scale, glowing, lifetimeMs)
        particles[id] = rp

        val pe: ParticleEngine = Minecraft.getInstance().particleEngine
        val level = Minecraft.getInstance().level
        if (level != null) {
            val bp = BridgeParticle(id, style, level, x, y, z,
                Color.of(r, g, b, a), scale, glowing)
            pe.add(bp)
            bridges[id] = bp
        }

        if (groupId != null) {
            groups.computeIfAbsent(groupId) { ConcurrentHashMap.newKeySet() }.add(id)
        }
    }

    fun updateParticle(id: UUID, x: Double, y: Double, z: Double,
                       r: Float, g: Float, b: Float, a: Float, scale: Float,
                       hasPos: Boolean, hasColor: Boolean, hasScale: Boolean,
                       durationTicks: Int, easing: EasingType) {
        val rp = particles[id] ?: return

        val durationMs = durationTicks * 50L
        val pos = Vec3(if (hasPos) x else rp.x(), if (hasPos) y else rp.y(), if (hasPos) z else rp.z())
        val color = Color.of(
            if (hasColor) r else rp.r(), if (hasColor) g else rp.g(),
            if (hasColor) b else rp.b(), if (hasColor) a else rp.a())
        val scl = if (hasScale) scale else rp.scale()
        rp.setTarget(pos, color, scl, easing, durationMs)
    }

    fun destroyParticles(ids: Array<UUID>) {
        for (id in ids) {
            particles.remove(id)
            bridges.remove(id)?.remove()
        }
        for (gms in groups.values) {
            for (id in ids) gms.remove(id)
        }
    }

    fun applyGroupTransform(groupId: UUID, transformType: Int,
                            dx: Double, dy: Double, dz: Double,
                            ax: Double, ay: Double, az: Double, radians: Double,
                            r: Float, g: Float, b: Float, a: Float,
                            targetScale: Float, px: Double, py: Double, pz: Double,
                            durationTicks: Int, easing: EasingType) {
        val members = groups[groupId] ?: return

        val pivot = Vec3(px, py, pz)
        val durationMs = durationTicks * 50L

        for (memberId in members) {
            val rp = particles[memberId] ?: continue

            val curPos = Vec3(rp.x(), rp.y(), rp.z())
            val newPos: Vec3
            val newColor: Color
            val newScale: Float

            when (transformType) {
                0 -> {
                    newPos = curPos.add(dx, dy, dz)
                    newColor = Color.of(rp.r(), rp.g(), rp.b(), rp.a())
                    newScale = rp.scale()
                }
                1 -> {
                    val rel = curPos.subtract(pivot)
                    val axis = Vec3(ax, ay, az).normalize()
                    val rotated = rotateAroundAxis(rel, axis, radians)
                    newPos = pivot.add(rotated)
                    newColor = Color.of(rp.r(), rp.g(), rp.b(), rp.a())
                    newScale = rp.scale()
                }
                2 -> {
                    newPos = curPos
                    newColor = Color.of(r, g, b, a)
                    newScale = rp.scale()
                }
                3 -> {
                    val rel = curPos.subtract(pivot)
                    newPos = pivot.add(rel.scale(targetScale.toDouble()))
                    newScale = targetScale
                    newColor = Color.of(rp.r(), rp.g(), rp.b(), rp.a())
                }
                else -> continue
            }

            rp.setTarget(newPos, newColor, newScale, easing, durationMs)
        }
    }

    fun frameUpdate() {
        for (rp in particles.values) {
            val wasSnap = rp.isSnapSync()
            rp.tick()

            val bp = bridges[rp.id()]
            if (bp != null) {
                bp.syncPosition(rp.x(), rp.y(), rp.z(), wasSnap)
                bp.syncColor(rp.r(), rp.g(), rp.b(), rp.a())
                bp.syncScale(rp.scale())
            }
        }

        val it = particles.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (entry.value.isDead()) {
                val id = entry.key
                bridges.remove(id)?.remove()
                it.remove()
            }
        }

        groups.values.removeIf { it.isEmpty() }
    }

    fun activeCount(): Int = particles.size

    fun getGlowingParticles(): List<RenderParticle> {
        val glowing = ArrayList<RenderParticle>()
        for (p in particles.values) {
            if (p.glowing() && p.isAlive() && p.a() > 0.01f) {
                glowing.add(p)
            }
        }
        return glowing
    }

    companion object {
        private var INSTANCE: ClientParticleEngine? = null

        fun init() { INSTANCE = ClientParticleEngine() }
        @JvmStatic
        fun instance(): ClientParticleEngine? = INSTANCE
        @JvmStatic
        fun dispose() { INSTANCE = null }

        private fun rotateAroundAxis(v: Vec3, axis: Vec3, radians: Double): Vec3 {
            val cos = Math.cos(radians)
            val sin = Math.sin(radians)
            val dot = v.dot(axis)
            val cross = axis.cross(v)
            return Vec3(
                v.x * cos + cross.x * sin + axis.x * dot * (1 - cos),
                v.y * cos + cross.y * sin + axis.y * dot * (1 - cos),
                v.z * cos + cross.z * sin + axis.z * dot * (1 - cos)
            )
        }
    }
}
