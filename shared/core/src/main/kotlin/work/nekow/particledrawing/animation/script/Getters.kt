package work.nekow.particledrawing.animation.script

import work.nekow.particledrawing.api.EntityProp
import work.nekow.particledrawing.api.WorldProp
import work.nekow.particledrawing.i18n.LocalizedArgumentException
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.translation

// 被动输入 getter（get_*）重写：把调用点换成合成外部变量，并产出实际需要的输入清单。
// 约束：实体 getter 参数必须是编译期常量；get_entity_pos 仅允许独占赋值形态；未知属性名与未登记句柄在编译期报错。

/** 一条输入需求，与重写产出的合成变量一一对应。 */
sealed class InputKey {
    /** 实体属性；[handleIndex] 指向程序实体注册表，序号即下发顺序。 */
    data class Entity(val handleIndex: Int, val prop: EntityProp) : InputKey()

    /** 世界属性。 */
    data class World(val prop: WorldProp) : InputKey()
}

/** 重写结果。[extNames] 与 [keys]按下标对齐。 */
class GetterRewriteResult(
    val code: String,
    val extNames: List<String>,
    val keys: List<InputKey>,
)

object GetterRewriter {

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
                requireLocalized(num == idx.toDouble() && idx in 0 until entityCount) {
                    translation("error.getter.handle_index", raw, entityCount)
                }
                return idx
            }
            return handles[arg] ?: throw LocalizedArgumentException(translation("error.getter.handle_unknown", arg))
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
                    requireLocalized(prop != null) {
                        translation("error.getter.world_property_choices", propWire, WorldProp.entries.joinToString("/") { it.wire })
                    }
                    requireLocalized(arg.isEmpty()) { translation("error.getter.world_args", propWire) }
                    alloc(InputKey.World(prop))
                }
                else -> {
                    val prop = EntityProp.fromWire(propWire)
                    requireLocalized(prop != null) {
                        translation("error.getter.entity_property_choices", propWire, EntityProp.entries.joinToString("/") { it.wire })
                    }
                    requireLocalized(prop != EntityProp.POS) {
                        translation("error.getter.entity_position")
                    }
                    requireLocalized(arg.isNotEmpty()) { translation("error.getter.entity_args", prop.wire) }
                    val idx = handleIndexOf(arg)
                    alloc(InputKey.Entity(idx, prop))
                }
            }
        }

        return GetterRewriteResult(text, extNames, keys)
    }

    /** 服务端预警：只报属性名与参数形态这类确定错误；句柄解析依赖运行时注册表，此处不判定。 */
    fun lint(code: String): List<String> {
        val problems = ArrayList<String>()
        for (m in CALL.findAll(POS_TRIPLE.replace(code) { "[x,y,z] = [__lint,__lint,__lint]" })) {
            val kind = m.groupValues[1]
            val propWire = m.groupValues[2]
            val arg = m.groupValues[3].trim()
            when (kind) {
                "get_world" -> {
                    if (WorldProp.fromWire(propWire) == null) problems.add(translation("error.getter.world_property", propWire).resolve())
                    if (arg.isNotEmpty()) problems.add(translation("error.getter.world_arguments", propWire).resolve())
                }
                else -> {
                    if (propWire == EntityProp.POS.wire) {
                        problems.add(translation("error.getter.entity_position").resolve())
                    } else if (EntityProp.fromWire(propWire) == null) {
                        problems.add(translation("error.getter.entity_property", propWire).resolve())
                    }
                    if (arg.isEmpty()) problems.add(translation("error.getter.entity_handle", propWire).resolve())
                }
            }
        }
        return problems
    }
}
