package work.nekow.particledrawing.command

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.ParticleGroup
import work.nekow.particledrawing.command.v2.GroupActions
import work.nekow.particledrawing.command.v2.ProgramCommands
import work.nekow.particledrawing.core.server.AnimationScheduler
import java.util.UUID
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.translation

/** 命令创建的粒子组；匿名组使用临时名称，随成员到期回收。 */
internal object CommandParticleGroups {
    data class Entry(
        val name: String,
        val group: ParticleGroup,
        val anonymous: Boolean = false,
        val initialPivot: Vec3 = group.pivot,
        var programmed: Boolean = false,
        var activated: Boolean = false,
        var membersReadyTick: Long = 0,
        var flushScheduled: Boolean = false,
        var programState: ProgramCommands.State? = null,
        var morphDraft: GroupActions.MorphConfig? = null,
        val pendingPrograms: MutableList<() -> Unit> = arrayListOf(),
    )

    /** 每维度命名组上限，避免命令无限建组把服务端内存吃满。 */
    private const val MAX_GROUPS = 1024

    private val dimensions = HashMap<UUID, LinkedHashMap<String, Entry>>()

    fun checkCapacity(dimension: UUID, name: String) {
        val groups = dimensions[dimension].orEmpty()
        requireLocalized(groups[name]?.anonymous == false || groups.values.count { !it.anonymous } < MAX_GROUPS) {
            translation("error.group.limit", MAX_GROUPS)
        }
    }

    fun install(dimension: UUID, name: String?, group: ParticleGroup): Entry {
        val key = name ?: "anon:${group.id}"
        delete(dimension, key)
        val entry = Entry(key, group, anonymous = name == null)
        dimensions.getOrPut(dimension) { linkedMapOf() }[key] = entry
        return entry
    }

    fun pruneAnonymous(dimension: UUID) {
        val groups = dimensions[dimension] ?: return
        groups.values.removeIf { entry ->
            entry.anonymous && entry.group.manager.getEngine().getGroup(entry.group.id) == null
        }
        if (groups.isEmpty()) dimensions.remove(dimension)
    }

    fun delete(dimension: UUID, name: String): Boolean {
        val groups = dimensions[dimension] ?: return false
        val entry = groups.remove(name) ?: return false
        entry.pendingPrograms.clear()
        entry.group.remove()
        if (groups.isEmpty()) dimensions.remove(dimension)
        return true
    }

    fun clearPrepared(dimension: UUID) {
        names(dimension).forEach { delete(dimension, it) }
    }

    fun get(dimension: UUID, name: String): Entry? = dimensions[dimension]?.get(name)

    fun names(dimension: UUID): List<String> = dimensions[dimension]?.keys?.toList() ?: emptyList()

    fun hasPendingMembers(entry: Entry): Boolean =
        entry.membersReadyTick > AnimationScheduler.currentTick()

    /** 成员仍在错峰生成时合并排队，保持多条命令的录制顺序。 */
    fun runWhenMembersReady(dimension: UUID, entry: Entry, action: () -> Unit): Boolean {
        val remaining = entry.membersReadyTick - AnimationScheduler.currentTick()
        if (remaining <= 0L) {
            action()
            return false
        }
        entry.pendingPrograms += action
        schedulePrograms(dimension, entry)
        return true
    }

    private fun schedulePrograms(dimension: UUID, entry: Entry) {
        if (!entry.flushScheduled) {
            val remaining = entry.membersReadyTick - AnimationScheduler.currentTick()
            entry.flushScheduled = true
            AnimationScheduler.scheduleOwned(dimension, remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), entry.group.id) {
                entry.flushScheduled = false
                if (get(dimension, entry.name) !== entry) {
                    entry.pendingPrograms.clear()
                    return@scheduleOwned
                }
                if (hasPendingMembers(entry)) {
                    schedulePrograms(dimension, entry)
                    return@scheduleOwned
                }
                val queued = entry.pendingPrograms.toList()
                entry.pendingPrograms.clear()
                queued.forEach { it() }
            }
        }
    }

    /** 关卡卸载时引擎已经负责销毁，这里只丢弃旧世界引用。 */
    fun discard(dimension: UUID) {
        dimensions.remove(dimension)
    }

    fun discardAll() {
        dimensions.clear()
    }
}
