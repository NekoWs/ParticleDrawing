package work.nekow.particledrawing.animation.recipe

import work.nekow.particledrawing.animation.script.*
import work.nekow.particledrawing.api.ParticleRecipe
import work.nekow.particledrawing.i18n.LocalizedArgumentException
import work.nekow.particledrawing.i18n.localizedError
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.requireNotNullLocalized
import work.nekow.particledrawing.i18n.translation

/** 复用脚本数学表达式，限制为无赋值、无循环的纯求值；每个输出分量独立求值。 */
class RecipeExpressions(recipe: ParticleRecipe, preferFast: Boolean = true) {
    // 逐粒子逐 tick 复用：只做按名查，不需要保持插入顺序，用 HashMap 省掉链表开销。
    private val values = HashMap<String, Any?>(64)
    private val ctx = ScriptRuntime.ProcessCtx(0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 0.0, values)
    private val lets = recipe.lets.map { (name, expr) -> name to compile(expr, false) }
    val birth = compileOutputs(recipe.birth, "birth")
    val motion = compileOutputs(recipe.motion, "motion")
    private val where = compile(recipe.where, true)
    private val kill = compile(recipe.kill, true)

    /** 锚点偏移公式；空串表示没有偏移。每 tick 求一次，不参与逐粒子热路径。 */
    private val orbit = recipe.orbit.takeIf(String::isNotBlank)?.let { compile(it, false) }
    private val orbitVariables = recipe.variables
    private val orbitState = recipe.state

    internal val externalInputs: Set<RecipeInput>

    /** 探测结果输入键，按探测组顺序展开；第 0 组用不带序号的旧名。 */
    internal val probeKeys: List<String> = buildProbeKeys(recipe.probes.size)

    init {
        val used = linkedSetOf<RecipeInput>()
        val names = recipe.variables.keys.toList() + recipe.state.keys.toList() + recipe.lets.keys.toList()
        requireLocalized(names.size == names.toSet().size) { translation("error.expression.name_overlap") }
        for (name in names) requireLocalized(NAME.matches(name) && name !in INPUTS && name !in FUNCTIONS &&
            name !in VECTOR_OUTPUTS && name !in setOf("PI", "E", "this")
        ) {
            translation("error.expression.variable_name", name)
        }
        requireLocalized((recipe.variables.values + recipe.state.values).all(Double::isFinite)) { translation("error.expression.variable_value") }
        val outputs = ATTRIBUTES + VECTOR_OUTPUTS.keys + recipe.state.keys
        for (name in recipe.birth.keys + recipe.motion.keys) requireLocalized(name in outputs) { translation("error.expression.read_only", name) }
        val known = (INPUTS + recipe.variables.keys + recipe.state.keys + probeKeys).toMutableSet()
        for ((name, expr) in recipe.lets) { validate(expr, known, used); known += name }
        for (expr in recipe.birth.values + recipe.motion.values + listOf(recipe.where, recipe.kill)) validate(expr, known, used)
        externalInputs = used
        // 锚点偏移只认发射器级的量：u/i/age 这类样本级输入在求锚点时没有意义，写了要报错而不是静默取 0。
        recipe.orbit.takeIf(String::isNotBlank)?.let { validate(it, ANCHOR_INPUTS + recipe.variables.keys + recipe.state.keys + recipe.lets.keys) }
    }

    /**
     * 填本颗粒子的求值表：属性 + 本刻输入 + 变量 + 派生 let。
     *
     * 单趟写进复用的 [values]，不再由调用方先建一张 `inputs` 拷贝再整表搬过来——
     * 这条路径逐粒子逐 tick 都走，少一次 map 分配就是几百万次/秒的垃圾少一截。
     */
    @Suppress("LongParameterList")
    fun prepare(
        attributes: Map<String, Double>,
        i: Double, n: Double, u: Double, t: Double, dt: Double,
        age: Double, life: Double, progress: Double,
        dx: Double, dy: Double, dz: Double,
        sx: Double, sy: Double, sz: Double,
        variables: Map<String, Double>,
        external: Map<String, Double> = emptyMap(),
    ) {
        values.clear()
        for ((k, v) in attributes) values[k] = v
        values["i"] = i; values["n"] = n; values["u"] = u; values["t"] = t; values["dt"] = dt
        values["age"] = age; values["life"] = life; values["progress"] = progress
        values["dx"] = dx; values["dy"] = dy; values["dz"] = dz
        values["sx"] = sx; values["sy"] = sy; values["sz"] = sz
        for ((k, v) in variables) values[k] = v
        values.putAll(external)
        for ((name, runner) in lets) values[name] = finiteLet(name, runner.evalAny(ctx))
    }

    /**
     * 逐粒子逐 tick 求值：结果写进调用方复用的一张表，不新建。
     *
     * 向量写法（`position`/`velocity`/`acceleration`）在这里只求值一次再拆分量。
     * 以前是拆成三条 `(expr).x/.y/.z` 各编一个执行器，同一个 `vec3(...)` 要整段算三遍，
     * 而逐粒子每秒要跑上百万次。
     */
    fun evaluateInto(groups: List<OutputGroup>, out: MutableMap<String, Double>): MutableMap<String, Double> {
        out.clear()
        for (g in groups) {
            if (g.names.size == 1) {
                out[g.names[0]] = finite(g.runner.eval(ctx))
            } else {
                val v = g.runner.evalAny(ctx)
                if (g.names[0] == "r") requireLocalized(v is Vec3 && listOf(v.x, v.y, v.z).all { it in 0.0..1.0 }) {
                    translation("error.command.v2.color_range")
                }
                for (k in g.names.indices) out[g.names[k]] = finite(component(v, g.components[k], g.names[k]))
            }
        }
        return out
    }

    internal fun drawVector(formula: String): (Int, Int, Double) -> Vec3 {
        validate(formula, setOf("i", "n", "u"))
        val runner = compile(formula, false)
        return { i, n, u ->
            values.clear()
            values["i"] = i.toDouble()
            values["n"] = n.toDouble()
            values["u"] = u
            val vector = runner.evalAny(ctx)
            requireNotNullLocalized(vector as? Vec3) { translation("error.expression.orbit_vec3") }
        }
    }

    fun evaluate(groups: List<OutputGroup>): Map<String, Double> =
        evaluateInto(groups, LinkedHashMap())

    /** 有没有锚点偏移公式。 */
    val hasOrbit: Boolean get() = orbit != null

    /**
     * 求锚点偏移：只填发射器级的量（`t`、variable、state、let），样本级输入不参与。
     * 每 tick 调一次，不进逐粒子热路径。
     */
    fun prepareAnchor(t: Double) {
        values.clear()
        values["t"] = t
        values["dt"] = 1.0
        for ((k, v) in orbitVariables) values[k] = v
        for ((k, v) in orbitState) values[k] = v
        for ((name, runner) in lets) {
            // let 可能引用样本级输入（u/i 等），锚点求值时没有这些值；跳过即可，逐粒子路径会自己算。
            val value = try { finiteLet(name, runner.evalAny(ctx)) } catch (error: RuntimeException) { null }
            if (value != null) values[name] = value
        }
    }

    /** 把 [prepareAnchor] 之后求出的偏移写进 out[0..2]。 */
    fun orbitInto(out: DoubleArray) {
        val runner = requireNotNullLocalized(orbit) { translation("error.expression.anchor_formula") }
        val v = runner.evalAny(ctx)
        for (i in 0..2) out[i] = component(v, i, "orbit")
    }

    fun accepts() = where.eval(ctx) != 0.0
    fun kills() = kill.eval(ctx) != 0.0

    // ── 热路径 ────────────────────────────────────────────────────────────────
    // 逐粒子逐 tick 的瓶颈是「按名字查表 + 每次求值重建变量表」。这里把 let、输出、条件
    // 一起编译成直接读写寄存器槽的标量程序：名字在编译期换成下标，运行期只有数组访问。
    // 含向量/矩阵/分量的配方编不过就返回 null，调用方自动走上面的解释器路径。

    /** 每颗粒子的固定槽位：16 个属性 + state；运行期由调用方按顺序写入本刻输入。 */
    val slots: RecipeSlots = buildSlots(recipe)

    /** 本刻输入槽（u 起，共 14 项）之后接 state；再往后是各程序的临时槽。 */
    private val base = Reg.VAR_START + BUILTIN_INPUT_SLOTS.size + recipe.state.size + externalInputs.size + probeKeys.size

    /** 编译成功的热路径；null 表示该配方只能走解释器。 */
    val fast: RecipeFast? = if (preferFast) buildFast(recipe) else null

    /** 每组的输入名；第 0 组保留 `probe_hit` 这类旧名，后续组带序号。 */
    private fun buildProbeKeys(count: Int): List<String> {
        if (count <= 0) return emptyList()
        val keys = ArrayList<String>(count * 9)
        for (i in 0 until count) {
            val prefix = if (i == 0) "probe" else "probe$i"
            keys += "${prefix}_hit"
            keys += "${prefix}_matches"
            keys += "${prefix}_distance"
            keys += "${prefix}_nx"; keys += "${prefix}_ny"; keys += "${prefix}_nz"
            keys += "${prefix}_px"; keys += "${prefix}_py"; keys += "${prefix}_pz"
        }
        return keys
    }

    private fun buildSlots(recipe: ParticleRecipe): RecipeSlots {
        val map = LinkedHashMap<String, Int>(32)
        for ((name, slot) in Reg.ATTR_SLOTS) map[name] = slot
        map["ax"] = AX; map["ay"] = AY; map["az"] = AZ
        var s = Reg.VAR_START
        for (name in BUILTIN_INPUT_SLOTS) map[name] = s++
        for (name in recipe.state.keys) map[name] = s++
        for (input in externalInputs) map[input.key] = s++
        for (key in probeKeys) map[key] = s++
        return RecipeSlots(map)
    }

    private fun buildFast(recipe: ParticleRecipe): RecipeFast? {
        val lets = ArrayList<Pair<String, Node>>(recipe.lets.size)
        for ((name, expr) in recipe.lets) lets += name to parseExpression(expr)

        val birthAssigns = ArrayList(lets)
        val birthConds = HashSet<String>()
        if (!appendOutputs(birthAssigns, recipe.birth, birthConds)) return null
        val birth = compileRegisterCode(birthAssigns, slots.map, base, birthConds) ?: return null

        // 每刻：先算 kill（看的是推进前的状态），再算 motion
        val tickAssigns = ArrayList(lets)
        tickAssigns += KILL_TEMP to parseExpression(recipe.kill)
        val tickConds = HashSet<String>()
        tickConds += KILL_TEMP
        if (!appendOutputs(tickAssigns, recipe.motion, tickConds)) return null
        val tick = compileRegisterCode(
            tickAssigns, slots.map + mapOf(KILL_TEMP to base), base + 1, tickConds,
        ) ?: return null

        // 出生后的筛选与销毁（看的是出生后的状态）
        val checkAssigns = ArrayList(lets)
        checkAssigns += WHERE_TEMP to parseExpression(recipe.where)
        checkAssigns += KILL_TEMP to parseExpression(recipe.kill)
        val checkConds = setOf(WHERE_TEMP, KILL_TEMP)
        val check = compileRegisterCode(
            checkAssigns,
            slots.map + mapOf(WHERE_TEMP to base, KILL_TEMP to base + 1),
            base + 2,
            checkConds,
        ) ?: return null

        val motion = recipe.motion
        return RecipeFast(
            base = base,
            birth = birth,
            check = check,
            tick = tick,
            writesX = "x" in motion || "position" in motion,
            writesY = "y" in motion || "position" in motion,
            writesZ = "z" in motion || "position" in motion,
        )
    }

    /**
     * 把一阶段的输出摊成逐分量赋值；向量写法必须是 vec2/3/4(...) 或等长数组字面量。
     *
     * 每个输出先落到临时槽，最后统一提交回属性槽：同一阶段里后面的输出必须看到
     * 阶段开始时的快照，不能看到前面刚写进去的新值。
     *
     * [conditions] 收集「整体按真假用」的临时槽（`glow`），这些表达式里才允许写比较和逻辑运算。
     */
    private fun appendOutputs(
        assigns: MutableList<Pair<String, Node>>,
        outputs: Map<String, String>,
        conditions: MutableSet<String> = HashSet(),
    ): Boolean {
        val commits = ArrayList<Pair<String, Node>>(outputs.size)
        var k = 0
        fun stage(target: String, node: Node) {
            val temp = "\$pd_out${k++}"
            if (target == "glow") conditions += temp
            assigns += temp to node
            commits += target to VarNode(temp, 0, 0)
        }
        for ((name, expr) in outputs) {
            if (name == "color") return false
            val node = parseExpression(expr)
            val vector = VECTOR_OUTPUTS[name]
            if (vector == null) {
                stage(name, node)
                continue
            }
            val parts = vectorParts(node, vector.size) ?: return false
            for (i in parts.indices) stage(vector[i].first, parts[i])
        }
        assigns += commits
        return true
    }

    /** `vec3(a,b,c)` 或 `[a,b,c]` 拆成分量；其它写法（变量、方法调用等）不参与热路径。 */
    private fun vectorParts(node: Node, size: Int): List<Node>? {
        if (node is ArrayNode) return node.items.takeIf { it.size == size }
        val call = node as? CallNode ?: return null
        val callee = call.callee as? VarNode ?: return null
        if (callee.name != "vec$size" || call.args.size != size) return null
        return call.args
    }

    /** 一组输出：单名或一个向量表达式拆出的三分量。 */
    class OutputGroup(val names: Array<String>, val components: IntArray, val runner: ScriptRuntime.ExpressionRunner)

    /** 标量直接一组一名；向量写法整段编一个执行器，分量在求值后再取。 */
    private fun compileOutputs(entries: Map<String, String>, stage: String): List<OutputGroup> {
        val out = ArrayList<OutputGroup>(entries.size)
        for ((name, expr) in entries) {
            val components = VECTOR_OUTPUTS[name]
            if (components == null) {
                out += OutputGroup(arrayOf(name), intArrayOf(0), compile(expr, name == "glow"))
            } else {
                for ((component, _) in components) {
                    requireLocalized(component !in entries) { translation("error.expression.output_conflict", stage, name, component) }
                }
                out += OutputGroup(
                    components.map { it.first }.toTypedArray(),
                    components.map { it.second }.toIntArray(),
                    compile(expr, false).also { runner -> if (name == "color") validateColorSyntax(runner.node) },
                )
            }
        }
        return out
    }

    private fun validateColorSyntax(node: Node) {
        val constantValid = when (node) {
            is NumNode, is StrNode, is BoolNode, is UndefinedNode, is ArrayNode -> false
            is CallNode -> {
                val name = (node.callee as? VarNode)?.name
                when (name) {
                    "vec2", "vec4", "mat3", "mat4" -> false
                    "vec3" -> node.args.size == 3 && node.args.all { it !is NumNode || it.value in 0.0..1.0 }
                    else -> true
                }
            }
            else -> true
        }
        requireLocalized(constantValid) { translation("error.command.v2.color_range") }
    }

    /** 从向量/数组里取第 [index] 个分量；写错形状直接报错，不静默取默认值。 */
    private fun component(value: Any?, index: Int, name: String): Double {
        val v = when (value) {
            is Vec2 -> when (index) { 0 -> value.x; 1 -> value.y; else -> null }
            is Vec3 -> when (index) { 0 -> value.x; 1 -> value.y; 2 -> value.z; else -> null }
            is Vec4 -> when (index) { 0 -> value.x; 1 -> value.y; 2 -> value.z; 3 -> value.w; else -> null }
            is List<*> -> value.getOrNull(index) as? Double
            else -> null
        }
        return requireNotNullLocalized(v) { translation("error.expression.component", name, index) }
    }

    private fun compile(expr: String, condition: Boolean): ScriptRuntime.ExpressionRunner {
        requireLocalized(expr.isNotBlank() && expr.length <= ParticleRecipe.MAX_EXPRESSION) { translation("error.expression.formula_length", ParticleRecipe.MAX_EXPRESSION) }
        return try {
            ScriptRuntime.ExpressionRunner(if (condition) "($expr) ? 1 : 0" else expr)
        } catch (error: ScriptException) {
            val text = if (error.line != null && error.col != null) {
                translation("error.expression.syntax_at", error.line, error.col)
            } else {
                translation("error.expression.syntax")
            }
            throw LocalizedArgumentException(text, error)
        }
    }

    private fun finite(value: Double): Double {
        requireLocalized(value.isFinite()) { translation("error.expression.nonfinite") }
        return value
    }

    /** let 可以留向量/矩阵，给后面的公式按分量用；其它类型不能继续参与算术。 */
    private fun finiteLet(name: String, value: Any?): Any {
        val ok = when (value) {
            is Double -> value.isFinite()
            is Vec2 -> value.x.isFinite() && value.y.isFinite()
            is Vec3 -> value.x.isFinite() && value.y.isFinite() && value.z.isFinite()
            is Vec4 -> value.x.isFinite() && value.y.isFinite() && value.z.isFinite() && value.w.isFinite()
            is Mat3 -> value.m.all { row -> row.size == 3 && row.all(Double::isFinite) }
            is Mat4 -> value.m.all { row -> row.size == 4 && row.all(Double::isFinite) }
            is Boolean -> true
            else -> false
        }
        requireLocalized(ok) { translation("error.expression.let_type", name) }
        return value as Any
    }

    private fun validate(expr: String, known: Set<String>, used: MutableSet<RecipeInput>? = null) {
        fun visit(node: Node) {
            when (node) {
                is NumNode, is BoolNode -> Unit
                is VarNode -> {
                    requireLocalized(node.name in known) { translation("error.expression.unknown_variable", node.name) }
                    RecipeInput.byKey[node.name]?.let { used?.add(it) }
                }
                is UnaryNode -> visit(node.operand)
                is BinaryNode -> { visit(node.left); visit(node.right) }
                is TernaryNode -> { visit(node.cond); visit(node.thenExpr); visit(node.elseExpr) }
                is CompNode -> visit(node.target)
                is MemberNode -> visit(node.obj)
                is IndexNode -> { visit(node.target); visit(node.index) }
                is ArrayNode -> node.items.forEach(::visit)
                is CallNode -> {
                    val callee = node.callee as? VarNode
                    requireLocalized(callee != null && callee.name in FUNCTIONS) { translation("error.expression.math_call") }
                    node.args.forEach(::visit)
                }
                is MethodNode -> { requireLocalized(node.method in METHODS) { translation("error.expression.unsupported_method", node.method) }; visit(node.obj); node.args.forEach(::visit) }
                else -> throw LocalizedArgumentException(translation("error.expression.math_only"))
            }
        }
        visit(parseExpression(expr))
    }

    companion object {
        /** 每刻注入运行期输入的槽位顺序，紧跟寄存器里的属性之后。 */
        private val BUILTIN_INPUT_SLOTS = listOf(
            "u", "dt", "age", "life", "progress", "dx", "dy", "dz", "sx", "sy", "sz", "ax", "ay", "az",
        )
        val INPUT_SLOTS = BUILTIN_INPUT_SLOTS + RecipeInput.byKey.keys
        const val U = Reg.VAR_START + 0
        const val DT = Reg.VAR_START + 1
        const val AGE = Reg.VAR_START + 2
        const val LIFE = Reg.VAR_START + 3
        const val PROGRESS = Reg.VAR_START + 4
        const val DX = Reg.VAR_START + 5
        const val DY = Reg.VAR_START + 6
        const val DZ = Reg.VAR_START + 7
        const val SX = Reg.VAR_START + 8
        const val SY = Reg.VAR_START + 9
        const val SZ = Reg.VAR_START + 10
        const val AX = Reg.VAR_START + 11
        const val AY = Reg.VAR_START + 12
        const val AZ = Reg.VAR_START + 13

        /** 热路径里给条件留的两个固定临时名。 */
        const val KILL_TEMP = "__kill"
        const val WHERE_TEMP = "__where"

        val ATTRIBUTES = setOf("x", "y", "z", "vx", "vy", "vz", "ax", "ay", "az", "r", "g", "b", "a", "sc", "glow", "light")

        /** 一次写三个分量的写法；键不能和同名标量属性同时出现。右值是分量在向量里的下标。 */
        val VECTOR_OUTPUTS: Map<String, List<Pair<String, Int>>> = linkedMapOf(
            "position" to listOf("x" to 0, "y" to 1, "z" to 2),
            "velocity" to listOf("vx" to 0, "vy" to 1, "vz" to 2),
            "acceleration" to listOf("ax" to 0, "ay" to 1, "az" to 2),
            "color" to listOf("r" to 0, "g" to 1, "b" to 2),
        )

        /** 可写字段全集，命令补全用。 */
        val OUTPUTS: Set<String> = ATTRIBUTES + VECTOR_OUTPUTS.keys

        val INPUTS = ATTRIBUTES + setOf("i", "n", "u", "t", "age", "life", "progress", "dt", "dx", "dy", "dz", "sx", "sy", "sz") + RecipeInput.byKey.keys

        /** 锚点偏移公式能用的输入：只有发射器级的量，没有样本。 */
        val ANCHOR_INPUTS = setOf("t", "dt")
        private val NAME = Regex("[A-Za-z_][A-Za-z0-9_]{0,47}")

        /** 命令公式允许调用的函数；必须都是运行时内建里真有的（测试钉着，别只改一边）。 */
        val FUNCTIONS: Set<String> = setOf("sin", "cos", "tan", "asin", "acos", "atan", "atan2", "sqrt", "abs", "sign", "exp", "log", "ln", "floor", "ceil", "round", "fract", "pow", "min", "max", "clamp", "map_range", "remap", "int", "float", "bool", "step", "smoothstep", "mod", "noise", "fbm", "rand", "random", "hash", "vec2", "vec3", "vec4", "vec", "mat3", "mat4", "norm", "ease_linear", "ease_in_out", "ease_out_back", "ease_in_elastic")

        /** 命令公式允许的向量/矩阵方法。 */
        val METHODS: Set<String> = setOf("len", "len2", "normalize", "dot", "cross", "dist", "angleTo", "project", "reflect", "lerp", "rotateX", "rotateY", "rotateZ", "translate", "scale")
    }
}

/** 一颗粒子的数值槽表：名字在编译期定好下标，运行期只做数组下标访问。 */
class RecipeSlots(val map: Map<String, Int>) {
    fun slot(name: String): Int = map[name] ?: localizedError(translation("error.expression.unknown_field", name))
    fun has(name: String): Boolean = name in map
}

/**
 * 一条配方热路径：出生、出生后判定、每刻推进各一个标量程序，共用同一份寄存器。
 *
 * 三个程序的临时槽从 [base] 起错开分配，所以寄存器总数取各自上界的最大值。
 */
class RecipeFast(
    val base: Int,
    val birth: RegisterCode,
    val check: RegisterCode,
    val tick: RegisterCode,
    val writesX: Boolean,
    val writesY: Boolean,
    val writesZ: Boolean,
) {
    val regCount: Int = base + maxOf(birth.tempCount, 1 + tick.tempCount, 2 + check.tempCount)
    val birthStack = DoubleArray(maxOf(birth.stackSize, 1))
    val checkStack = DoubleArray(maxOf(check.stackSize, 1))
    val tickStack = DoubleArray(maxOf(tick.stackSize, 1))

    /** 每颗粒子一份寄存器，初值由运行期按种子与配方填。 */
    fun newRegs() = DoubleArray(regCount)
}
