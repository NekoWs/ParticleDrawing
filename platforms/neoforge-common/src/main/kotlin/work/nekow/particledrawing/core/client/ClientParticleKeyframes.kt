package work.nekow.particledrawing.core.client

import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import org.apache.logging.log4j.LogManager
import work.nekow.particledrawing.api.ParticleVisual
import work.nekow.particledrawing.core.network.ParticleKeyframesPayload
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import kotlin.math.cos
import kotlin.math.sin

/** 用固定粒子与可选的相邻链接播放世界坐标关键帧。 */
internal object ClientParticleKeyframes {
    private const val MAGIC = 0x50444731 // PDG1
    private const val FIELDS = 5 // x, y, z, 半宽, 半高
    private const val MAX_SAMPLES = 4_000_000
    private const val MAX_TRACKS = 24_000
    private val logger = LogManager.getLogger("ParticleDrawing")
    private val loader = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ParticleDrawing keyframes").apply { isDaemon = true }
    }
    @Volatile private var revision = 0
    private var playing: Playback? = null

    private data class Clip(
        val fps: Int, val frames: Int, val next: IntArray, val coordinates: ShortArray, val colors: IntArray,
    ) {
        val count: Int get() = next.size
    }

    private class Playback(val clip: Clip, val payload: ParticleKeyframesPayload, val ids: Array<UUID>,
                           val particles: Array<BridgeParticle?>) {
        val angle = Math.toRadians(payload.yaw.toDouble())
        val cosine = cos(angle)
        val sine = sin(angle)
        var last = System.nanoTime()
        var position = 0.0
    }

    fun receive(payload: ParticleKeyframesPayload) {
        clear()
        val name = payload.asset ?: return
        if (!payload.x.isFinite() || !payload.y.isFinite() || !payload.z.isFinite() || !payload.yaw.isFinite()) return
        val client = Minecraft.getInstance()
        val resource = Identifier.fromNamespaceAndPath(name.namespace, "particle_keyframes/" + name.path + ".pdg.gz")
        val resources = client.resourceManager
        val ticket = revision
        loader.execute {
            if (ticket != revision) return@execute
            val clip = try {
                resources.open(resource).use(::read)
            } catch (error: Exception) {
                if (ticket == revision) logger.warn("Unable to read particle keyframes {}", name, error)
                return@execute
            }
            client.execute {
                if (ticket == revision && client.level != null) start(clip, payload)
            }
        }
    }

    private fun read(input: InputStream): Clip = DataInputStream(BufferedInputStream(GZIPInputStream(input))).use { data ->
        require(data.readInt() == MAGIC)
        val fps = data.readUnsignedShort()
        val frames = data.readUnsignedShort()
        val count = data.readUnsignedShort()
        require(fps in 1..120 && frames >= 2 && count in 1..MAX_TRACKS && frames.toLong() * count <= MAX_SAMPLES)
        val next = IntArray(count) { index ->
            data.readShort().toInt().also { require(it == -1 || it == index + 1 && it < count) }
        }
        val colors = IntArray(frames * count)
        val coordinates = ShortArray(colors.size * FIELDS)
        for (index in colors.indices) {
            for (field in 0 until FIELDS) coordinates[index * FIELDS + field] = data.readShort()
            colors[index] = data.readInt()
        }
        require(data.read() == -1)
        Clip(fps, frames, next, coordinates, colors)
    }

    private fun start(clip: Clip, payload: ParticleKeyframesPayload) {
        val engine = ClientParticleEngine.instance() ?: return
        val look = ResolvedVisual.of(ParticleVisual().texture(TextureCache.KEYFRAME_GLOW).apply { additive = true }, 1f)
        val ids = Array(clip.count) { UUID.randomUUID() }
        val particles = Array(clip.count) { i ->
            engine.spawnParticle(ids[i], payload.x, payload.y, payload.z, 0f, 0f, 0f, 0f, 0f, -1, null,
                true, 0, look)
            engine.bridgeForKeyframes(ids[i])
        }
        for (index in particles.indices) {
            val bridge = particles[index] ?: continue
            val next = clip.next[index]
            val previous = index > 0 && clip.next[index - 1] == index
            if (next < 0 && !previous) continue
            bridge.recipeStrand = RecipeStrand().also {
                it.previous = if (previous) particles[index - 1] else null
                it.next = if (next >= 0) particles[next] else null
            }
        }
        playing = Playback(clip, payload, ids, particles)
        logger.info("Playing {} particle keyframes using {} particle tracks", payload.asset, clip.count)
    }

    fun advance() {
        val playback = playing ?: return
        val now = System.nanoTime()
        val elapsed = (now - playback.last) / 1_000_000_000.0
        playback.last = now
        if (Minecraft.getInstance().isPaused) return
        playback.position += elapsed * playback.clip.fps
        if (playback.position >= playback.clip.frames - 1) {
            clear()
            return
        }
        val clip = playback.clip
        val frame = playback.position.toInt()
        val fraction = (playback.position - frame).toFloat()
        val first = frame * clip.count
        val second = first + clip.count
        val origin = playback.payload
        for (index in playback.particles.indices) {
            val particle = playback.particles[index] ?: continue
            val a = first + index
            val b = second + index
            val colorA = clip.colors[a]
            val colorB = clip.colors[b]
            fun channel(shift: Int): Float {
                val from = colorA ushr shift and 255
                val to = colorB ushr shift and 255
                return (from + (to - from) * fraction) / 255f
            }
            fun interpolate(field: Int): Float {
                val from = clip.coordinates[a * FIELDS + field].toInt()
                val to = clip.coordinates[b * FIELDS + field].toInt()
                return from + (to - from) * fraction
            }
            val x = interpolate(0) / 256.0
            val y = interpolate(1) / 256.0
            val z = interpolate(2) / 256.0
            particle.syncPosition(origin.x + x * playback.cosine - z * playback.sine, origin.y + y,
                origin.z + x * playback.sine + z * playback.cosine, snap = true)
            // RGB encodes light intensity so faint grains and the soft outer glow survive the particle shader.
            val intensity = channel(24)
            particle.syncColor(channel(16) * intensity, channel(8) * intensity, channel(0) * intensity,
                if (intensity == 0f) 0f else 1f, snap = true)
            val pixelScale = TextureCache.KEYFRAME_GLOW_SIZE / 16f
            val sizeUnit = 1024f * BridgeParticle.EDITOR_TO_MC_SCALE * pixelScale
            particle.syncScale(interpolate(3) * 2.8f / sizeUnit, interpolate(4) * 2.8f / sizeUnit, snap = true)
        }
    }

    fun clear() {
        revision++
        val previous = playing ?: return
        playing = null
        ClientParticleEngine.instance()?.destroyParticles(previous.ids)
    }
}
