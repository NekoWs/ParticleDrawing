package work.nekow.particledrawing.api

import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * 程序化粒子集：成员逐 tick 增删、每颗粒子各自运动与寿命、按位置/速度条件回收的粒子流。
 *
 * 与 [ParticleGroup] 的分工：组是编排式动画，成员基本固定、组级统一变换、指令流一次下发、
 * 客户端本地求值；本类成员随时增删，每颗粒子独立受力或直设位置，位置按服务端权威批量直设下发。
 *
 * 簿记规则：
 * - 位置与速度一律读 PD 的权威值（[ParticleHandle.position] / [ParticleHandle.velocity]）；
 * - PD 侧已过期/已销毁的成员在下次操作时自动出列（[evictDead]）；
 *   延迟生成（[ParticleHandle.Builder.delay]）的成员在到点前不算已死，不会被摘掉；
 * - [trackAll] / [setVelocityAll] / [applyForceAll] 都是一次网络包覆盖全组（每成员一个向量，
 *   按登记顺序对应）。
 *
 * @param manager 该粒子集所在维度的粒子管理器
 */
class ParticleBatch(private val manager: ParticleManager) {

    private val core = BatchCore<ParticleHandle>(
        idOf = { it.id },
        stateOf = { it.state() },
        sendPositions = { ids, positions -> manager.trackAll(ids, positions) },
        sendVelocities = { ids, velocities -> manager.setVelocityAll(ids, velocities) },
        sendForces = { ids, accelerations, ticks -> manager.applyForceAll(ids, accelerations, ticks) },
        destroy = { it.remove() },
        isPending = { it.isPending() },
    )

    /** 登记一个已有粒子；`Builder.spawn()` 的返回值可直接传入，null 忽略。 */
    fun add(handle: ParticleHandle?): ParticleHandle? = handle?.let { core.add(it) }

    /**
     * 批量生成：整批用一个包下发（[ParticleManager.spawnAll]），生成成功的成员就地登记进本集合。
     * 每颗的字段由 [ParticleSpawnSpec] 给，含寿命曲线与首帧插值端点（[ParticleSpawnSpec.prevPosition]）。
     *
     * @return 实际新增的成员数；被维度上限拒绝的那些不登记
     */
    fun spawnAll(specs: List<ParticleSpawnSpec>): Int {
        val handles = manager.spawnAll(specs)
        var added = 0
        for (handle in handles) {
            if (handle != null) {
                core.add(handle)
                added++
            }
        }
        return added
    }

    /** 批量登记；返回实际登记数（null 元素忽略）。 */
    fun addAll(handles: Collection<ParticleHandle?>): Int {
        var added = 0
        for (handle in handles) {
            if (handle != null) {
                core.add(handle)
                added++
            }
        }
        return added
    }

    /** 把一个成员移出集合（不销毁粒子本身）。 */
    fun remove(handle: ParticleHandle): Boolean = core.remove(handle)

    /** 成员数；先摘掉已过期/已销毁的成员。 */
    fun size(): Int = core.size()

    fun isEmpty(): Boolean = core.isEmpty()

    /** 成员快照，顺序 = 登记顺序（[trackAll] 的 positions 按这个顺序对应）。 */
    fun handles(): List<ParticleHandle> = core.members()

    /** 摘掉 PD 侧已过期/已销毁的成员；返回摘掉的数量。 */
    fun evictDead(): Int = core.evictDead()

    /** 直设一个成员的位置（单包）。 */
    fun track(handle: ParticleHandle, pos: Vec3): ParticleBatch {
        core.track(handle, pos)
        return this
    }

    /** 批量直设位置：一次网络包；[positions] 与 [handles] 顺序一一对应，长度不同按短的一方截断。 */
    fun trackAll(positions: List<Vec3>): Int = core.trackAll(positions)

    /** 逐成员算一个新位置后一次包下发，返回实际下发数。 */
    fun trackEach(offsetAt: (index: Int, handle: ParticleHandle) -> Vec3): Int {
        return core.trackEach { index, handle -> offsetAt(index, handle) }
    }

    /** 设置某成员的速度（blocks/tick）。 */
    fun setVelocity(handle: ParticleHandle, velocity: Vec3): ParticleBatch {
        handle.setVelocity(velocity)
        return this
    }

    /**
     * 批量设置速度：一次网络包覆盖全组（成员顺序 = 登记顺序），[velocities] 与 [handles]
     * 顺序一一对应，长度不同按短的一方截断。速度驱动接管位置，之后位置由两端按同一规则逐 tick 积分。
     *
     * @return 服务端实际生效的粒子数
     */
    fun setVelocityAll(velocities: List<Vec3>): Int = core.setVelocityAll(velocities)

    /** 给某成员施力；只在开始施力时下发一次，两端按同一规则逐 tick 积分。 */
    fun applyForce(handle: ParticleHandle, acceleration: Vec3, ticks: Int = -1): ParticleBatch {
        handle.applyForce(acceleration, ticks)
        return this
    }

    /**
     * 批量施力：一次网络包覆盖全组，每颗粒子各自的加速度、共用一个 [ticks]。
     * [accelerations] 与 [handles] 顺序一一对应，长度不同按短的一方截断。
     *
     * 默认 `ticks = 1`（只施这一 tick）；长效施力显式给 `-1`。
     *
     * @return 服务端实际生效的粒子数
     */
    fun applyForceAll(accelerations: List<Vec3>, ticks: Int = 1): Int {
        return core.applyForceAll(accelerations, ticks)
    }

    /** 遍历仍存活的成员，回调里给的是 PD 的权威位置与速度。 */
    fun forEach(action: (index: Int, handle: ParticleHandle, pos: Vec3, vel: Vec3) -> Unit) {
        core.forEach { index, handle, pos, vel -> action(index, handle, pos, vel) }
    }

    /** 按权威位置/速度条件回收：回调返回 true 的成员立即销毁并出列；返回销毁数。 */
    fun removeIf(predicate: (handle: ParticleHandle, pos: Vec3, vel: Vec3) -> Boolean): Int {
        return core.removeIf { handle, pos, vel -> predicate(handle, pos, vel) }
    }

    /**
     * 补齐到 [target] 个成员：反复调用 [spawner]（参数是当前下标），直到够了或 [spawner] 返回 null
     * （维度粒子上限拒绝了生成，此时立即停下，下次调用再补）。
     *
     * @return 实际新增的成员数
     */
    fun ensureSize(target: Int, spawner: (index: Int) -> ParticleHandle?): Int {
        return core.ensureSize(target) { index -> spawner(index) }
    }

    /** 销毁全部成员并清空（已不存在的成员直接出列）。 */
    fun clear() = core.clear()
}

/**
 * 成员簿记的通用实现：只依赖 id / 权威状态 / 一次包下发位置·速度·力 / 销毁 几个访问器。
 */
internal class BatchCore<T>(
    private val idOf: (T) -> UUID,
    private val stateOf: (T) -> Pair<Vec3, Vec3>?,
    private val sendPositions: (List<UUID>, List<Vec3>) -> Int,
    private val sendVelocities: (List<UUID>, List<Vec3>) -> Int,
    private val sendForces: (List<UUID>, List<Vec3>, Int) -> Int,
    private val destroy: (T) -> Unit,
    private val isPending: (T) -> Boolean = { false },
) {

    private val members = ArrayList<T>()

    fun members(): List<T> = members.toList()

    /** 不去重、不判活的成员数。 */
    fun rawSize(): Int = members.size

    fun add(member: T): T {
        members.add(member)
        return member
    }

    fun remove(member: T): Boolean = members.remove(member)

    fun size(): Int {
        evictDead()
        return members.size
    }

    fun isEmpty(): Boolean = size() == 0

    /** 摘掉权威状态读不到的成员（PD 侧已过期/已销毁）；延迟生成中（[isPending]）的成员保留。 */
    fun evictDead(): Int {
        var removed = 0
        val it = members.iterator()
        while (it.hasNext()) {
            val member = it.next()
            if (stateOf(member) == null && !isPending(member)) {
                it.remove()
                removed++
            }
        }
        return removed
    }

    fun track(member: T, pos: Vec3) {
        if (stateOf(member) == null) {
            if (!isPending(member)) members.remove(member)
            return
        }
        sendPositions(listOf(idOf(member)), listOf(pos))
    }

    fun trackAll(positions: List<Vec3>): Int {
        val batch = aligned(positions) ?: return 0
        return sendPositions(batch.first, batch.second)
    }

    /** 批量设速：与 [trackAll] 同一套对齐规则（成员顺序 = 登记顺序，长度不同按短的一方截断）。 */
    fun setVelocityAll(velocities: List<Vec3>): Int {
        val batch = aligned(velocities) ?: return 0
        return sendVelocities(batch.first, batch.second)
    }

    /** 批量施力：与 [trackAll] 同一套对齐规则，[ticks] 全组共用。 */
    fun applyForceAll(accelerations: List<Vec3>, ticks: Int): Int {
        val batch = aligned(accelerations) ?: return 0
        return sendForces(batch.first, batch.second, ticks)
    }

    /**
     * 摘掉过期成员后，把一列「每成员一个的向量」按成员下标对齐成「id 列表 + 值列表」；
     * 成员或值为空时返回 null，此时不发包。
     */
    private fun aligned(vectors: List<Vec3>): Pair<List<UUID>, List<Vec3>>? {
        evictDead()
        if (members.isEmpty() || vectors.isEmpty()) return null
        val count = minOf(members.size, vectors.size)
        val ids = ArrayList<UUID>(count)
        val values = ArrayList<Vec3>(count)
        for (i in 0 until count) {
            ids.add(idOf(members[i]))
            values.add(vectors[i])
        }
        return ids to values
    }

    fun trackEach(offsetAt: (index: Int, member: T) -> Vec3): Int {
        evictDead()
        if (members.isEmpty()) return 0
        val ids = ArrayList<UUID>(members.size)
        val pos = ArrayList<Vec3>(members.size)
        for ((i, member) in members.withIndex()) {
            ids.add(idOf(member))
            pos.add(offsetAt(i, member))
        }
        return sendPositions(ids, pos)
    }

    fun forEach(action: (index: Int, member: T, pos: Vec3, vel: Vec3) -> Unit) {
        evictDead()
        var index = 0
        for (member in members) {
            val state = stateOf(member) ?: continue
            action(index++, member, state.first, state.second)
        }
    }

    fun removeIf(predicate: (member: T, pos: Vec3, vel: Vec3) -> Boolean): Int {
        var removed = 0
        for (member in members.toList()) {
            val state = stateOf(member)
            if (state == null) {
                if (!isPending(member)) members.remove(member)
                continue
            }
            if (predicate(member, state.first, state.second)) {
                destroy(member)
                members.remove(member)
                removed++
            }
        }
        return removed
    }

    fun clear() {
        for (member in members) {
            if (stateOf(member) != null) destroy(member)
        }
        members.clear()
    }

    /**
     * 补齐到 [target] 个成员：反复调用 [spawner]（参数是当前下标），直到够了或 [spawner] 返回 null
     * （维度粒子上限拒绝，此时立即停下，下次调用再补）。
     *
     * @return 实际新增的成员数
     */
    fun ensureSize(target: Int, spawner: (index: Int) -> T?): Int {
        evictDead()
        var added = 0
        while (members.size < target) {
            val member = spawner(members.size) ?: break
            members.add(member)
            added++
        }
        return added
    }
}
