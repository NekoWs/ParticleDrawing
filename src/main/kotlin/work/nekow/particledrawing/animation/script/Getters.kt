package work.nekow.particledrawing.animation.script

import work.nekow.particledrawing.api.EntityProp
import work.nekow.particledrawing.api.WorldProp

/**
 * 被动输入获取（get_* 函数）共享层。
 *
 * 公式通过 `get_entity_<prop>(<句柄>)` / `get_world_<prop>()` 在需要处取值；
 * 编译前由 [GetterRewriter] 把调用点重写为合成外部变量（`__in0…`），
 * 同时产出「本段代码实际需要的输入清单」——客户端每 tick 只采样被引用的值，
 * 服务端协议无需携带任何属性声明。属性词表见 [EntityProp] / [WorldProp] 枚举。
 *
 * 双路径共用：
 * - 纯标量快路径（表达式指令）：重写产物交给 [compileFunctionObject] 的 extNames 机制注入；
 * - 通用解释器路径（setVariableLive）：合成变量值并入求值作用域。
 *
 * 语义约束（编译期强制）：
 * - 实体 getter 参数必须是「编译期常量」：数字句柄或已登记实体名（见 ParticleGroup.defineEntity）；
 * - `get_entity_pos(h)` 仅允许独占 `[x,y,z] = get_entity_pos(h)` 赋值右侧，
 *   重写为三分量注入；其余上下文请用 get_entity_x/_y/_z；
 * - 未知名/未登记句柄一律抛 [IllegalArgumentException]（fail-fast）。
 */

/** 一条被发现的输入需求（与重写产出的合成变量一一对应）。 */
internal sealed class InputKey {
    /** 实体属性：[handleIndex] 指向程序实体注册表（下发顺序即序号）。 */
    data class Entity(val handleIndex: Int, val prop: EntityProp) : InputKey()

    /** 世界属性。 */
    data class World(val prop: WorldProp) : InputKey()
}

/** 重写结果。[extNames] 与 [keys]按下标对齐。 */
internal class GetterRewriteResult(
    val code: String,
    val extNames: List<String>,
    val keys: List<InputKey>,
)

internal object GetterRewriter {

    /** get_entity_pos 整取特判：仅支持 `[x,y,z] = get_entity_pos(<arg>)` 独占赋值形态。 */
    private val POS_TRIPLE = Regex(
        """\[\s*x\s*,\s*y\s*,\s*z\s*\]\s*=\s*get_entity_pos\s*\(\s*([^()]*?)\s*\)"""
    )

    /** 通用 getter 调用。 */
    private val CALL = Regex("""\b(get_entity|get_world)_([a-z_]+)\s*\(\s*([^()]*?)\s*\)""")

    /**
     * 重写代码中的全部 getter 调用。
     * @param handles 已登记实体名 -> 注册序号
     * @param entityCount 注册表容量（数字句柄的合法上界）
     * @throws IllegalArgumentException 属性未知名、world 带参、entity 缺参、句柄未登记或越界
     */
    fun rewrite(code: String, handles: Map<String, Int>, entityCount: Int): GetterRewriteResult {
        val extNames = ArrayList<String>()
        val keys = ArrayList<InputKey>()

        fun alloc(key: InputKey): String {
            val name = "__in${extNames.size}"
            extNames.add(name)
            keys.add(key)
            return name
        }

        fun handleIndexOf(raw: String): Int {
            val arg = raw.trim()
            arg.toDoubleOrNull()?.let { num ->
                val idx = num.toInt()
                require(num == idx.toDouble() && idx in 0 until entityCount) {
                    "实体句柄越界: $raw（注册表容量 $entityCount）"
                }
                return idx
            }
            return handles[arg] ?: throw IllegalArgumentException("未知实体句柄 '$arg'（需先 defineEntity 登记）")
        }

        // 1) pos 整取特判：展开为三分量合成变量
        var text = POS_TRIPLE.replace(code) { m ->
            val idx = handleIndexOf(m.groupValues[1])
            "[x,y,z] = [" +
                alloc(InputKey.Entity(idx, EntityProp.X)) + ", " +
                alloc(InputKey.Entity(idx, EntityProp.Y)) + ", " +
                alloc(InputKey.Entity(idx, EntityProp.Z)) + "]"
        }

        // 2) 通用 getter
        text = CALL.replace(text) { m ->
            val kind = m.groupValues[1]
            val propWire = m.groupValues[2]
            val arg = m.groupValues[3].trim()
            when (kind) {
                "get_world" -> {
                    val prop = WorldProp.fromWire(propWire)
                    require(prop != null) {
                        "未知世界属性 'get_world_$propWire'，可用: ${WorldProp.entries.joinToString("/") { it.wire }}"
                    }
                    require(arg.isEmpty()) { "get_world_$propWire 不接受参数" }
                    alloc(InputKey.World(prop))
                }
                else -> {
                    val prop = EntityProp.fromWire(propWire)
                    require(prop != null) {
                        "未知实体属性 'get_entity_$propWire'，可用: ${EntityProp.entries.joinToString("/") { it.wire }}"
                    }
                    require(prop != EntityProp.POS) {
                        "get_entity_pos 仅支持独占赋值形态: [x,y,z] = get_entity_pos(句柄)"
                    }
                    require(arg.isNotEmpty()) { "get_entity_${prop.wire} 需要实体句柄参数" }
                    val idx = handleIndexOf(arg)
                    alloc(InputKey.Entity(idx, prop))
                }
            }
        }

        return GetterRewriteResult(text, extNames, keys)
    }

    /**
     * 服务端 best-effort 预警：只报「确定错误」（属性未知名、world 带参、pos 误用）；
     * 句柄解析依赖运行时注册表，此处不判定。
     */
    fun lint(code: String): List<String> {
        val problems = ArrayList<String>()
        for (m in CALL.findAll(POS_TRIPLE.replace(code) { "[x,y,z] = [__lint,__lint,__lint]" })) {
            val kind = m.groupValues[1]
            val propWire = m.groupValues[2]
            val arg = m.groupValues[3].trim()
            when (kind) {
                "get_world" -> {
                    if (WorldProp.fromWire(propWire) == null) problems.add("未知世界属性 get_world_$propWire")
                    if (arg.isNotEmpty()) problems.add("get_world_$propWire 不接受参数")
                }
                else -> {
                    if (propWire == EntityProp.POS.wire) {
                        problems.add("get_entity_pos 仅支持 [x,y,z] = get_entity_pos(句柄) 形态")
                    } else if (EntityProp.fromWire(propWire) == null) {
                        problems.add("未知实体属性 get_entity_$propWire")
                    }
                    if (arg.isEmpty()) problems.add("get_entity_$propWire 缺少句柄参数")
                }
            }
        }
        return problems
    }
}
