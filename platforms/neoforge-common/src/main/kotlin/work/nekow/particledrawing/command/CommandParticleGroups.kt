package work.nekow.particledrawing.command

import work.nekow.particledrawing.api.ParticleGroup
import work.nekow.particledrawing.core.server.AnimationScheduler
import java.util.UUID
import kotlin.math.max

/** 数据包与命令方块创建的命名粒子组；名称在维度内唯一，服务器重启后清空。 */
internal object CommandParticleGroups {
    data class Entry(
        val name: String,
        val group: ParticleGroup,
        var programmed: Boolean = false,
        var activated: Boolean = false,
        var expectedMembers: Int = 0,
        var membersReadyTick: Long = 0,
        var flushScheduled: Boolean = false,
        val pendingPrograms: MutableList<() -> Unit> = arrayListOf(),
    )

    private val dimensions = HashMap<UUID, LinkedHashMap<String, Entry>>()

    fun create(dimension: UUID, name: String, group: ParticleGroup): Entry {
        require(NAME.matches(name)) { "组名只能含字母、数字、下划线、短横线、点和冒号" }
        val groups = dimensions.getOrPut(dimension) { linkedMapOf() }
        require(name !in groups) { "粒子组 '$name' 已存在；先 remove 或换一个名称" }
        return Entry(name, group).also { groups[name] = it }
    }

    fun get(dimension: UUID, name: String): Entry? = dimensions[dimension]?.get(name)

    fun names(dimension: UUID): List<String> = dimensions[dimension]?.keys?.toList() ?: emptyList()

    fun remove(dimension: UUID, name: String): Boolean {
        val groups = dimensions[dimension] ?: return false
        val entry = groups.remove(name) ?: return false
        disposeAfterPendingMembers(dimension, entry)
        if (groups.isEmpty()) dimensions.remove(dimension)
        return true
    }

    fun clear(dimension: UUID): Int {
        val groups = dimensions.remove(dimension) ?: return 0
        groups.values.forEach { disposeAfterPendingMembers(dimension, it) }
        return groups.size
    }

    /** 已排的 Draw stagger 任务不能取消；留一个空组接住迟到成员，全部到齐后统一销毁。 */
    private fun disposeAfterPendingMembers(dimension: UUID, entry: Entry) {
        val remaining = entry.membersReadyTick - AnimationScheduler.currentTick()
        entry.group.remove()
        if (remaining <= 0L) return
        entry.group.manager.getEngine().createGroup(entry.group.id, entry.group.pivot)
        AnimationScheduler.schedule(dimension, remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) {
            entry.group.remove()
        }
    }

    /** Draw 的 stagger 会延迟真正生成成员；程序必须等最后一颗到组里后再下发。 */
    fun waitForMembers(entry: Entry, particleCount: Int, stagger: Int) {
        if (particleCount <= 1 || stagger <= 0) return
        val lastDelay = (particleCount.toLong() - 1L) * stagger
        entry.membersReadyTick = max(entry.membersReadyTick, AnimationScheduler.currentTick() + lastDelay + 1L)
    }

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
        if (!entry.flushScheduled) {
            entry.flushScheduled = true
            AnimationScheduler.schedule(dimension, remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) {
                entry.flushScheduled = false
                if (get(dimension, entry.name) !== entry) {
                    entry.pendingPrograms.clear()
                    return@schedule
                }
                val queued = entry.pendingPrograms.toList()
                entry.pendingPrograms.clear()
                queued.forEach { it() }
            }
        }
        return true
    }

    /** 关卡卸载时引擎已经负责销毁，这里只丢弃旧世界引用。 */
    fun discard(dimension: UUID) {
        dimensions.remove(dimension)
    }

    fun discardAll() {
        dimensions.clear()
    }

    private val NAME = Regex("^[A-Za-z0-9_.:-]{1,64}$")
}
