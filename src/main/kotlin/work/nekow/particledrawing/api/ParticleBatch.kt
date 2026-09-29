package work.nekow.particledrawing.api

import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * 程序化粒子集：成员逐 tick 增删、每颗粒子各自运动与寿命、按位置/速度条件回收的粒子流
 * （黑洞吸入、重力场下落、跟随实体的一圈粒子）。
 *
 * 与 [ParticleGroup] 的分工：[ParticleGroup] 是**编排式动画**——成员基本固定、组级统一变换、
 * 指令流一次下发、客户端本地求值；本类相反：成员随时增删、每粒子独立受力或直设位置，
 * 位置按「服务端权威 + 批量直设」下发。要写组级编排动画就用 [ParticleGroup]，别用本类。
 *
 * 簿记规则：
 * - 位置与速度一律读 PD 的权威值（[ParticleHandle.position] / [ParticleHandle.velocity]），
 *   调用方不必自己维护 id ↔ 状态表；
 * - PD 侧已过期/已销毁的成员，在下次操作时自动出列（[evictDead]），对调用方透明；
 * - [trackAll] 一次网络包覆盖全组；配合 [ParticleHandle.applyForce]，
 *   「每粒子受力」也只需在开始施力时发一次包。
 *
 * @param manager 该粒子集所在维度的粒子管理器
 */
class ParticleBatch(private val manager: ParticleManager) {

    private val core = BatchCore<ParticleHandle>(
        idOf = { it.id },
        stateOf = { it.state() },
        sendPositions = { ids, positions -> manager.trackAll(ids, positions) },
        destroy = { it.remove() },
    )

    /** 登记一个已有粒子（`Builder.spawn()` 的返回值可直接传入；null 忽略）。 */
    fun add(handle: ParticleHandle?): ParticleHandle? = handle?.let { core.add(it) }

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

    /** 成员数（先把已过期/已销毁的成员摘掉）。 */
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

    /** 逐成员算一个新位置后一次包下发（省掉调用方维护 id 列表），返回实际下发数。 */
    fun trackEach(offsetAt: (index: Int, handle: ParticleHandle) -> Vec3): Int {
        return core.trackEach { index, handle -> offsetAt(index, handle) }
    }

    /** 设置某成员的速度（blocks/tick）。 */
    fun setVelocity(handle: ParticleHandle, velocity: Vec3): ParticleBatch {
        handle.setVelocity(velocity)
        return this
    }

    /** 给某成员施力（只在开始施力时下发一次，两端按同一规则逐 tick 积分）。 */
    fun applyForce(handle: ParticleHandle, acceleration: Vec3, ticks: Int = -1): ParticleBatch {
        handle.applyForce(acceleration, ticks)
        return this
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
     * （通常是维度粒子上限拒绝了生成——此时立即停下，下次调用再补，不空转）。
     *
     * @return 本次实际新增的成员数
     */
    fun ensureSize(target: Int, spawner: (index: Int) -> ParticleHandle?): Int {
        return core.ensureSize(target) { index -> spawner(index) }
    }

    /** 销毁全部成员并清空（已不存在的成员直接出列）。 */
    fun clear() = core.clear()
}

/**
 * 成员簿记的通用实现：只依赖「id / 权威状态 / 一次包直设位置 / 销毁」四个访问器，
 * 所以能用假成员直接单测（[ParticleHandle] 要真实服务端关卡才构造得出来）。
 */
internal class BatchCore<T>(
    private val idOf: (T) -> UUID,
    private val stateOf: (T) -> Pair<Vec3, Vec3>?,
    private val sendPositions: (List<UUID>, List<Vec3>) -> Int,
    private val destroy: (T) -> Unit,
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

    /** 摘掉权威状态读不到的成员（PD 侧已过期/已销毁）。 */
    fun evictDead(): Int {
        var removed = 0
        val it = members.iterator()
        while (it.hasNext()) {
            if (stateOf(it.next()) == null) {
                it.remove()
                removed++
            }
        }
        return removed
    }

    fun track(member: T, pos: Vec3) {
        if (stateOf(member) == null) {
            members.remove(member)
            return
        }
        sendPositions(listOf(idOf(member)), listOf(pos))
    }

    fun trackAll(positions: List<Vec3>): Int {
        evictDead()
        if (members.isEmpty() || positions.isEmpty()) return 0
        val ids = ArrayList<UUID>(members.size)
        val pos = ArrayList<Vec3>(members.size)
        for (i in 0 until minOf(members.size, positions.size)) {
            ids.add(idOf(members[i]))
            pos.add(positions[i])
        }
        if (ids.isEmpty()) return 0
        return sendPositions(ids, pos)
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
                members.remove(member)
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
     * （通常是维度粒子上限拒绝——立即停下，下次调用再补，不空转）。
     *
     * @return 本次实际新增的成员数
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
