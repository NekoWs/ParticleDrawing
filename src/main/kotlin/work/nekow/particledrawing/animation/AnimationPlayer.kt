package work.nekow.particledrawing.animation

import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.core.easing.EasingType
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
    private val baseVel = HashMap<String, Vec3>()
    private val groupPivot = HashMap<String, Vec3>()
    private var currentTick = 0
    private var finished = false
    private val maxTick: Int = animation.tracks.flatMap { it.keyframes }.maxOfOrNull { it.tick } ?: 0

    init {
        // 粒子所属组映射（用于查询组的常量位置增量）
        val particleGroup = HashMap<String, String>()
        for ((name, members) in animation.groups) {
            for (id in members) particleGroup[id] = name
        }
        // 单关键帧 op 位置轨道：增量为常量，直接并入 spawn 位置，避免「先出现在原点再闪现」
        val constantPosDelta = HashMap<String, Vec3>()
        for (track in animation.tracks) {
            if (track.property != AnimTrack.Property.POSITION || track.mode != AnimTrack.Mode.OP) continue
            if (track.keyframes.size != 1) continue
            val kf0 = track.keyframes[0]
            if (kf0.tick != 0) continue
            val groupName = track.ids.firstOrNull { it.startsWith("g:") }?.substring(2) ?: continue
            constantPosDelta[groupName] = Vec3(kf0.value[0], kf0.value[1], kf0.value[2])
        }

        for (p in animation.particles) {
            val constDelta = particleGroup[p.id]?.let { constantPosDelta[it] } ?: Vec3.ZERO
            val data = engine.spawnParticle(
                p.style, origin.add(p.pos).add(constDelta), p.color, p.scale,
                -1, null, p.glowing, p.lightLevel, null, players
            ) ?: continue
            idMap[p.id] = data.id
            basePos[p.id] = p.pos
            baseColor[p.id] = p.color
            baseScale[p.id] = p.scale
            baseVel[p.id] = p.vel
            if (p.vel.x != 0.0 || p.vel.y != 0.0 || p.vel.z != 0.0) {
                engine.setVelocity(data.id, p.vel, players)
            }
        }
        for ((name, members) in animation.groups) {
            var sx = 0.0; var sy = 0.0; var sz = 0.0; var n = 0
            for (id in members) {
                val pos = basePos[id] ?: continue
                sx += pos.x; sy += pos.y; sz += pos.z; n++
            }
            if (n > 0) groupPivot[name] = Vec3(sx / n, sy / n, sz / n)
        }
        // 应用 t=0 的初始关键帧增量（无缓动），避免粒子先出现在原点再缓动到初始位置
        applyInitialStates(players)
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
        if (maxTick in 1..<currentTick) {
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
        if (kfs.size <= 1) return // 单关键帧：值恒定，初始增量已在 spawn 时应用
        val next = if (i + 1 < kfs.size) kfs[i + 1]
        else if (animation.loop) kfs[0]
        else return // 非循环的最后一个关键帧：保持不动

        val duration = if (i + 1 < kfs.size) next.tick - kfs[i].tick
        else (maxTick - kfs[i].tick) + next.tick // 循环回绕
        if (duration <= 0) return

        applyValue(track, next.value, kfs[i].easing, duration, players)
    }

    /** 将某个关键帧值应用到目标粒子（duration=0 表示立即应用，无缓动）。 */
    private fun applyValue(track: AnimTrack, v: DoubleArray, easing: EasingType, duration: Int, players: Collection<ServerPlayer>) {
        val op = track.mode == AnimTrack.Mode.OP
        val groupName = track.ids.firstOrNull { it.startsWith("g:") }?.substring(2)
        val pivot = groupName?.let { groupPivot[it] } ?: Vec3.ZERO
        for (id in resolveIds(track)) {
            val uuid = idMap[id] ?: continue
            if (track.property == AnimTrack.Property.VELOCITY) {
                if (op) {
                    val base = baseVel[id] ?: Vec3.ZERO
                    engine.setVelocity(uuid, base.add(Vec3(v[0], v[1], v[2])), players)
                } else {
                    engine.setVelocity(uuid, Vec3(v[0], v[1], v[2]), players)
                }
                continue
            }
            if (track.property == AnimTrack.Property.ROTATION) {
                val base = basePos[id] ?: Vec3.ZERO
                engine.rotateParticle(uuid, origin.add(pivot), currentOffset(id, base, pivot, groupName, currentTick), v, duration, easing, players)
                continue
            }
            val b = engine.update(uuid)
            when (track.property) {
                AnimTrack.Property.POSITION -> {
                    val base = basePos[id] ?: Vec3.ZERO
                    if (op) {
                        engine.translateParticle(uuid, origin.add(pivot), base.subtract(pivot), Vec3(v[0], v[1], v[2]), duration, easing, players)
                    } else {
                        engine.setPosition(uuid, origin.add(pivot), Vec3(v[0], v[1], v[2]).subtract(pivot), duration, easing, players)
                    }
                    continue
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
            b.easing(easing, duration).send(players)
        }
    }

    /** spawn 后应用 t=0 的关键帧初始值（无缓动），保证粒子初始状态与编辑器一致、避免首帧闪烁。 */
    private fun applyInitialStates(players: Collection<ServerPlayer>) {
        for (track in animation.tracks) {
            val kf0 = track.keyframes.firstOrNull() ?: continue
            if (kf0.tick != 0) continue
            if (track.mode == AnimTrack.Mode.OP) {
                // 单关键帧位置轨道：常量增量已直接并入 spawn 位置，无需再次下发
                if (track.property == AnimTrack.Property.POSITION && track.keyframes.size <= 1) continue
                applyValue(track, kf0.value, kf0.easing, 0, players)
            } else {
                // set 轨道：t=0 初始值立即应用（duration=0），避免首帧出现错误位置/属性
                applyValue(track, kf0.value, kf0.easing, 0, players)
            }
        }
    }

    /**
     * 计算粒子在指定 tick 的「未旋转偏移」（相对轴心）。
     * 组位置轨道为 set 模式时偏移会随时间变化（setValue - pivot），否则恒为 base - pivot。
     */
    private fun currentOffset(id: String, base: Vec3, pivot: Vec3, groupName: String?, tick: Int): Vec3 {
        if (groupName == null) return base.subtract(pivot)
        val posTrack = animation.tracks.firstOrNull {
            it.property == AnimTrack.Property.POSITION && it.mode == AnimTrack.Mode.SET && it.ids.contains("g:$groupName")
        } ?: return base.subtract(pivot)
        val value = interpolateValue(posTrack, tick)
        return Vec3(value[0], value[1], value[2]).subtract(pivot)
    }

    /** 按关键帧缓动插值轨道在指定 tick 的值（与编辑器/客户端一致）。 */
    private fun interpolateValue(track: AnimTrack, tick: Int): DoubleArray {
        val kfs = track.keyframes
        if (kfs.isEmpty()) return DoubleArray(3)
        if (tick <= kfs.first().tick) return kfs.first().value
        if (tick >= kfs.last().tick) return kfs.last().value
        for (i in 0 until kfs.size - 1) {
            val a = kfs[i]
            val b = kfs[i + 1]
            if (tick in a.tick..b.tick) {
                val dur = (b.tick - a.tick).toDouble()
                val t = if (dur == 0.0) 1.0f else ((tick - a.tick) / dur).toFloat()
                val e = a.easing.evaluate(t)
                val out = DoubleArray(a.value.size)
                for (j in a.value.indices) out[j] = a.value[j] + (b.value[j] - a.value[j]) * e
                return out
            }
        }
        return kfs.last().value
    }

    private fun cleanup(players: Collection<ServerPlayer>) {
        for (uuid in idMap.values) {
            engine.destroyParticle(uuid, players)
        }
        idMap.clear()
    }
}
