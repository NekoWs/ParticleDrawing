package work.nekow.particledrawing.animation

import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.core.server.ServerParticleEngine
import java.util.UUID

/**
 * 服务端动画播放器：在指定原点生成粒子，并按关键帧时间轴逐 tick 下发更新。
 */
class AnimationPlayer(
    private val engine: ServerParticleEngine,
    private val origin: Vec3,
    private val animation: ParticleAnimation,
    players: Collection<ServerPlayer>
) {

    private val idMap = HashMap<String, UUID>()
    private val basePos = HashMap<String, Vec3>()
    private val baseColor = HashMap<String, Color>()
    private val baseScale = HashMap<String, Float>()
    private var currentTick = 0
    private var finished = false
    private val maxTick: Int = animation.tracks.flatMap { it.keyframes }.maxOfOrNull { it.tick } ?: 0

    init {
        for (p in animation.particles) {
            val data = engine.spawnParticle(
                p.style, origin.add(p.pos), p.color, p.scale,
                -1, null, p.glowing, p.lightLevel, null, players
            ) ?: continue
            idMap[p.id] = data.id
            basePos[p.id] = p.pos
            baseColor[p.id] = p.color
            baseScale[p.id] = p.scale
        }
    }

    /**
     * 推进一 tick，返回是否仍在播放。
     *
     * 关键帧语义：在关键帧 i 触发时，向客户端下发「缓动到下一个关键帧 i+1 的值」，
     * 持续时间 = 关键帧 i+1 与 i 的 tick 之差，缓动 = 关键帧 i 的缓动类型。
     * 循环时最后一个关键帧回绕到第一个关键帧。
     */
    fun tick(players: Collection<ServerPlayer>): Boolean {
        if (finished) return false
        for (track in animation.tracks) {
            for ((i, kf) in track.keyframes.withIndex()) {
                if (kf.tick == currentTick) {
                    applySegment(track, i, players)
                }
            }
        }
        currentTick++
        if (maxTick > 0 && currentTick > maxTick) {
            if (animation.loop) {
                currentTick = 0
            } else {
                finished = true
                cleanup(players)
                return false
            }
        }
        return true
    }

    /** 立即停止并清理生成的粒子。 */
    fun stop(players: Collection<ServerPlayer>) {
        if (finished) return
        finished = true
        cleanup(players)
    }

    private fun resolveIds(track: AnimTrack): List<String> {
        val out = ArrayList<String>()
        for (id in track.ids) {
            if (id == "all") { out.addAll(idMap.keys); continue }
            if (id.startsWith("g:")) {
                animation.groups[id.substring(2)]?.let { out.addAll(it) }
                continue
            }
            out.add(id)
        }
        return out
    }

    private fun applySegment(track: AnimTrack, i: Int, players: Collection<ServerPlayer>) {
        val kfs = track.keyframes
        val next = if (i + 1 < kfs.size) kfs[i + 1]
        else if (animation.loop) kfs[0]
        else return // 非循环的最后一个关键帧：保持不动

        val duration = if (i + 1 < kfs.size) next.tick - kfs[i].tick
        else (maxTick - kfs[i].tick) + next.tick // 循环回绕
        if (duration <= 0) return

        val v = next.value
        val op = track.mode == AnimTrack.Mode.OP
        for (id in resolveIds(track)) {
            val uuid = idMap[id] ?: continue
            val b = engine.update(uuid)
            when (track.property) {
                AnimTrack.Property.POSITION -> {
                    if (op) {
                        val base = basePos[id] ?: Vec3.ZERO
                        b.position(origin.x + base.x + v[0], origin.y + base.y + v[1], origin.z + base.z + v[2])
                    } else {
                        b.position(origin.x + v[0], origin.y + v[1], origin.z + v[2])
                    }
                }
                AnimTrack.Property.COLOR -> {
                    if (op) {
                        val base = baseColor[id] ?: Color.BLACK
                        b.color(Color.of(
                            (base.r + v[0].toFloat()).coerceIn(0f, 1f),
                            (base.g + v[1].toFloat()).coerceIn(0f, 1f),
                            (base.b + v[2].toFloat()).coerceIn(0f, 1f),
                            (base.a + v[3].toFloat()).coerceIn(0f, 1f)
                        ))
                    } else {
                        b.color(Color.of(v[0].toFloat(), v[1].toFloat(), v[2].toFloat(), v[3].toFloat()))
                    }
                }
                AnimTrack.Property.SCALE -> {
                    if (op) {
                        val base = baseScale[id] ?: 1f
                        b.scale((base + v[0].toFloat()).coerceAtLeast(0.01f))
                    } else {
                        b.scale(v[0].toFloat())
                    }
                }
            }
            b.easing(kfs[i].easing, duration).send(players)
        }
    }

    private fun cleanup(players: Collection<ServerPlayer>) {
        for (uuid in idMap.values) {
            engine.destroyParticle(uuid, players)
        }
        idMap.clear()
    }
}
