package work.nekow.particledrawing.animation.script

import kotlin.math.*

// this 对象名（与 ScriptParser 一致）。
private const val CTX_NAME = "this"

// 向量分量别名：r/g/b → x/y/z，a → w。
private val COMP_ALIAS = mapOf("x" to "x", "y" to "y", "z" to "z", "w" to "w", "r" to "x", "g" to "y", "b" to "z", "a" to "w", "alpha" to "w")

/** 该向量是否含某规范化分量（x/y/z/w）。 */
private fun hasComp(v: Any?, comp: String): Boolean = when (v) {
    is Vec2 -> comp == "x" || comp == "y"
    is Vec3 -> comp == "x" || comp == "y" || comp == "z"
    is Vec4 -> comp == "x" || comp == "y" || comp == "z" || comp == "w"
    else -> false
}

/** 写回单分量，返回新向量（对应 JS setVecComp）。 */
private fun setVecComp(v: Any?, comp: String, value: Double): Any = when (v) {
    is Vec2 -> Vec2(if (comp == "x") value else v.x, if (comp == "y") value else v.y)
    is Vec3 -> Vec3(if (comp == "x") value else v.x, if (comp == "y") value else v.y, if (comp == "z") value else v.z)
    is Vec4 -> Vec4(if (comp == "x") value else v.x, if (comp == "y") value else v.y, if (comp == "z") value else v.z, if (comp == "w") value else v.w)
    else -> throw ScriptException("not a vector")
}

// this.spawn(config) 可选字段（与编辑器 generators.js 一致）。
private val SPAWN_CONFIG_FIELDS = setOf("position", "color", "velocity", "scale", "glow", "light", "life", "uv")

private fun cfgVec(v: Any?, field: String, len: Int): List<Double> {
    if (isVec(v)) {
        val c = vecComps(v!!)
        if (c.size != len) throw ScriptException("spawn config '$field' requires a vec$len")
        return c
    }
    if (v is MutableList<*>) {
        if (v.size != len) throw ScriptException("spawn config '$field' requires an array of $len numbers")
        return v.mapIndexed { i, x -> (x as? Double) ?: throw ScriptException("spawn config '$field[$i]' requires a num") }
    }
    throw ScriptException("spawn config '$field' requires a vec$len or array of $len numbers")
}

private fun cfgColor(v: Any?): List<Double> = when {
    v is ColorVal -> listOf(v.r, v.g, v.b, v.a)
    v is Vec4 -> listOf(v.x, v.y, v.z, v.w)
    v is Vec3 -> listOf(v.x, v.y, v.z)
    v is MutableList<*> && (v.size == 3 || v.size == 4) ->
        v.mapIndexed { i, x -> (x as? Double) ?: throw ScriptException("spawn config 'color[$i]' requires a num") }
    else -> throw ScriptException("spawn config 'color' requires a color, vec3, vec4 or [r,g,b(,a)]")
}

private fun applySpawnConfig(host: ParticleHost, config: Any?) {
    if (config !is ObjVal) throw ScriptException("this.spawn(config) requires an object")
    for ((key, v) in config.fields) {
        if (key !in SPAWN_CONFIG_FIELDS) throw ScriptException("unknown spawn config field '$key'")
        when (key) {
            "position" -> { val c = cfgVec(v, key, 3); host.pos[0] = c[0]; host.pos[1] = c[1]; host.pos[2] = c[2] }
            "velocity" -> { val c = cfgVec(v, key, 3); host.vel[0] = c[0]; host.vel[1] = c[1]; host.vel[2] = c[2] }
            "color" -> {
                val c = cfgColor(v)
                host.color[0] = clamp01(c[0]); host.color[1] = clamp01(c[1]); host.color[2] = clamp01(c[2])
                host.color[3] = if (c.size == 4) clamp01(c[3]) else 1.0
            }
            "scale" -> host.scale = (v as? Double) ?: throw ScriptException("spawn config 'scale' requires a num")
            "glow" -> host.glow = if (v is Boolean) v else ((v as? Double) ?: throw ScriptException("spawn config 'glow' requires a num")) > 0.5
            "light" -> host.light = clampNum(jsRound((v as? Double) ?: throw ScriptException("spawn config 'light' requires a num")), 0.0, 15.0)
            "life" -> {
                val n = jsRound((v as? Double) ?: throw ScriptException("spawn config 'life' requires a num"))
                host.life = if (n.isFinite()) (if (n < 0.0) -1.0 else n) else -1.0
            }
            "uv" -> host.fields["uv"] = cfgVec(v, key, 2)
        }
    }
}

// spawn 模型脚本运行时（对应编辑器 script-lang.js）。
// 生命周期：setup（对象级一次）/ tick（每动画 tick）/ process（每渲染帧）；this 只给 time/duration/particles/spawn，粒子经句柄字段读写与 kill()。
// 旧 ProcessCtx + ExpressionRunner 保留给 UV 字段裸表达式。
object ScriptRuntime {

    const val TICKS_PER_SEC = 20
    private const val MAX_TOTAL_LOOP_ITERATIONS = 1_000_000
    private const val MAX_VALUE_DEPTH = 128
    private const val MAX_REPEAT_ITERATIONS = 100_000

    class ObjectState(
        val globals: MutableMap<String, Any?>,
        val rand: RandState,
        val seed: Int,
        val constGlobals: MutableSet<String> = HashSet(),
        var topLevelDone: Boolean = false,
    )

    /** 表达式阶段输出（UV 字段表达式把 out 字段镜像为最终渲染值）。 */
    class ScriptOut(
        val pos: DoubleArray = DoubleArray(3),
        val color: DoubleArray = doubleArrayOf(1.0, 1.0, 1.0, 1.0),
        val vel: DoubleArray = DoubleArray(3),
        var scale: Double = 1.0,
        var glow: Boolean = false,
        var light: Double = 0.0,
        var life: Double = -1.0,
    )

    /** 表达式阶段上下文（UV 字段裸表达式；旧 index/count/time/delta/duration/uv 字段 + out）。 */
    class ProcessCtx(
        var i: Double,
        var n: Double,
        var t: Double,
        var dt: Double,
        var life: Double,
        var uv_x: Double,
        var uv_y: Double,
        val vars: Map<String, Double>,
        val out: ScriptOut = ScriptOut(),
        var fastMath: Boolean = false,
        var duration: Double = 0.0,
    ) {
        /** 复用同一个 ProcessCtx 执行多个粒子时，重置输出为进程默认值。 */
        fun resetOut() {
            val o = out
            o.pos[0] = 0.0; o.pos[1] = 0.0; o.pos[2] = 0.0
            o.color[0] = 1.0; o.color[1] = 1.0; o.color[2] = 1.0; o.color[3] = 1.0
            o.vel[0] = 0.0; o.vel[1] = 0.0; o.vel[2] = 0.0
            o.scale = 1.0
            o.glow = false
            o.light = 0.0
            o.life = -1.0
        }
    }

    /**
     * spawn 模型上下文（setup/tick/process 三阶段共用；setup 用 env 形态但字段同构）。
     * 时间单位均为毫秒。
     *
     * @param t 绝对播放毫秒（动画全局时间轴位置）
     * @param st 函数对象起点 fx.st（毫秒）
     * @param duration 函数对象自身时长 fx.duration（毫秒；≤0 表示无上限，this.duration 回退 maxMs）
     * @param maxMs 整个动画总长（毫秒）
     * @param particles 运行时粒子列表（this.particles）
     * @param spawn 创建并返回一个粒子句柄（this.spawn()）
     * @param deltaMs 帧毫秒增量（内部调度保留字段；脚本语言不再将其暴露给 process）
     */
    class ScriptCtx(
        var t: Double,
        var duration: Double,
        val vars: Map<String, Double>,
        val particles: MutableList<ParticleHost>,
        val spawn: () -> ParticleHost,
        var deltaMs: Double = 0.0,
        var fastMath: Boolean = false,
        var print: (String) -> Unit = {},
        var st: Double = 0.0,
        var maxMs: Double = 0.0,
        val spawnConfig: (Any?) -> ParticleHost = { cfg -> spawn().also { if (cfg != null) applySpawnConfig(it, cfg) } },
        val get: (String) -> Any? = { throw ScriptException("this.get is not available here") },
    )

    fun createObjectState(seed: Int): ObjectState = ObjectState(HashMap(), RandState(seed), seed)

    fun runTopLevel(program: ScriptProgram, obj: ObjectState, ctx: ScriptCtx) {
        if (obj.topLevelDone) return
        obj.topLevelDone = true
        val rt = Runtime("toplevel", program, obj, ctx, null)
        rt.pushScope(HashMap())
        try {
            for (d in program.globals) {
                if (d is DeclareNode) {
                    for (dec in d.decls) {
                        if (obj.globals.containsKey(dec.name)) {
                            throw ScriptException("duplicate global '${dec.name}'", dec.line, dec.col)
                        }
                        val v = if (dec.init != null) rt.evalExpr(dec.init) else Undefined
                        obj.globals[dec.name] = v
                        if (d.kind == "const") obj.constGlobals.add(dec.name)
                    }
                } else if (d is DestructureNode) {
                    val v = rt.evalExpr(d.value)
                    val fields = (v as? ObjVal)?.fields
                        ?: throw ScriptException("destructuring requires an object, got ${typeName(v)}", d.line, d.col)
                    for (name in d.names) {
                        if (obj.globals.containsKey(name)) {
                            throw ScriptException("duplicate global '$name'", d.line, d.col)
                        }
                        obj.globals[name] = fields[name] ?: Undefined
                        if (d.kind == "const") obj.constGlobals.add(name)
                    }
                }
            }
        } finally {
            rt.popScope()
        }
    }

    fun runSpawnSetup(program: ScriptProgram, obj: ObjectState, ctx: ScriptCtx) {
        runTopLevel(program, obj, ctx)
        val rt = Runtime("setup", program, obj, ctx, null)
        rt.pushScope(HashMap())
        try {
            for (st in program.setup) rt.execStmt(st)
        } finally {
            rt.popScope()
        }
    }

    fun runTickFrame(program: ScriptProgram, obj: ObjectState, ctx: ScriptCtx) {
        if (program.tick.isEmpty()) return
        val rt = Runtime("tick", program, obj, ctx, null)
        rt.pushScope(HashMap())
        try {
            for (st in program.tick) rt.execStmt(st)
        } finally {
            rt.popScope()
        }
    }

    fun runProcessFrame(program: ScriptProgram, obj: ObjectState, ctx: ScriptCtx) {
        if (program.process.isEmpty()) return
        val rt = Runtime("process", program, obj, ctx, null)
        rt.pushScope(HashMap())
        try {
            for (st in program.process) rt.execStmt(st)
        } finally {
            rt.popScope()
        }
    }

    /**
     * 可复用的裸表达式执行器（UV 字段表达式等）：同一表达式跨粒子复用 Runtime / 作用域。
     * 表达式必须求值为标量（Double）。
     */
    class ExpressionRunner(expr: String) {
        private val node: Node = parseExpression(expr)
        private val program = ScriptProgram(emptyList(), emptyList(), emptyList(), emptyMap())
        private val obj = createObjectState(0)
        private val rt = Runtime("expr", program, obj, null, null)
        private val topScope = HashMap<String, Any?>()

        fun eval(ctx: ProcessCtx): Double {
            rt.resetExpr(ctx, topScope)
            val v = rt.evalExpr(node)
            if (v !is Double) {
                throw ScriptException("expression must evaluate to a number, got ${typeName(v)}", node.line, node.col)
            }
            return v
        }
    }

    fun evalExpression(expr: String, ctx: ProcessCtx): Double = ExpressionRunner(expr).eval(ctx)

    private class Flow(val kind: String, val value: Any? = null) : Throwable()
    private val IT_UNSET = Any()

    private val BUILTINS = setOf(
        "print", "assert",
        "vec2", "vec3", "vec4", "vec", "mat3", "mat4",
        "norm", "hash", "phases", "repeat",
        "clamp", "map_range", "remap", "int", "float", "bool",
        "sin", "cos", "tan", "asin", "acos", "atan", "atan2", "sqrt", "abs", "sign", "exp", "log", "ln",
        "floor", "ceil", "round", "fract", "pow", "min", "max", "step", "smoothstep", "mod",
        "noise", "fbm", "rand", "random",
        "ease_linear", "ease_in_out", "ease_out_back", "ease_in_elastic",
        "color",
        "unique", "reverse", "sort",
    )

    private class Runtime(
        val phase: String,
        val program: ScriptProgram,
        val objState: ObjectState,
        private var ctx: ScriptCtx?,
        private var pctx: ProcessCtx?,
    ) {
        private val scopes = ArrayList<MutableMap<String, Any?>>()
        private val constSets = ArrayList<MutableSet<String>?>()
        private val varsMap = HashMap<String, Double>()
        private val receiverStack = ArrayList<ParticleValue>()
        private var funcDepth = 0
        private var inFunction = false
        private var usedIterations = 0

        init {
            rebuildVarsMap()
        }

        fun pushScope(s: MutableMap<String, Any?>) { scopes.add(s); constSets.add(null) }
        fun popScope() { scopes.removeAt(scopes.size - 1); constSets.removeAt(constSets.size - 1) }
        fun currentScope(): MutableMap<String, Any?> = scopes[scopes.size - 1]
        fun markConst(name: String) {
            var cs = constSets[constSets.size - 1]
            if (cs == null) { cs = HashSet(); constSets[constSets.size - 1] = cs }
            cs.add(name)
        }

        // 全局迭代预算：所有循环/重复共用，防止嵌套循环把每循环上限相乘放大。
        private fun guardLoop(n: Node) {
            if (++usedIterations > MAX_TOTAL_LOOP_ITERATIONS) {
                err("total loop iteration limit ($MAX_TOTAL_LOOP_ITERATIONS) exceeded", n)
            }
        }

        private fun rebuildVarsMap() {
            varsMap.clear()
            val src = if (phase == "expr") pctx?.vars else ctx?.vars
            if (src != null) for ((k, v) in src) varsMap[k] = v
        }

        /** 复用表达式执行器：切换到下一次求值的 ProcessCtx 并重建变量表。 */
        fun resetExpr(ctx: ProcessCtx?, topScope: MutableMap<String, Any?>) {
            this.pctx = ctx
            this.ctx = null
            scopes.clear()
            constSets.clear()
            funcDepth = 0
            inFunction = false
            usedIterations = 0
            topScope.clear()
            scopes.add(topScope)
            constSets.add(null)
            rebuildVarsMap()
        }

        private fun err(msg: String, n: Node): Nothing {
            throw ScriptException(msg, n.line, n.col)
        }

        private fun num(v: Any?, what: String, n: Node): Double {
            if (v is Double) return v
            err("$what requires a num, got ${typeName(v)}", n)
        }

        private fun int(v: Any?, what: String, n: Node): Int {
            val d = num(v, what, n)
            if (d % 1.0 != 0.0) err("$what requires an integer, got $d", n)
            return d.toInt()
        }

        // JS `x | 0`：先要求整数（与 expectInt 一致），再按 ToInt32 回绕（与 toInt 的饱和截断不同）。
        private fun int32(v: Any?, what: String, n: Node): Int {
            val d = num(v, what, n)
            if (d % 1.0 != 0.0) err("$what requires an integer, got $d", n)
            return toInt32(d)
        }

        private fun truthy(v: Any?, n: Node): Boolean = when (v) {
            is Undefined -> false
            is Boolean -> v
            is Double -> v != 0.0
            else -> err("condition requires a bool or num, got ${typeName(v)}", n)
        }

        fun execStmt(n: Node) {
            when (n) {
                is BlockNode -> {
                    pushScope(HashMap())
                    try { for (s in n.body) execStmt(s) } finally { popScope() }
                }
                is IfNode -> {
                    if (truthy(evalExpr(n.cond), n.cond)) execStmt(n.then)
                    else if (n.els != null) execStmt(n.els)
                }
                is WhileNode -> {
                    var iter = 0
                    while (truthy(evalExpr(n.cond), n.cond)) {
                        if (++iter > 100000) err("maximum loop iterations (100000) exceeded", n)
                        guardLoop(n)
                        try { execStmt(n.body) }
                        catch (f: Flow) {
                            if (f.kind == "break") break
                            if (f.kind == "continue") continue
                            throw f
                        }
                    }
                }
                is DoNode -> {
                    var iter = 0
                    do {
                        if (++iter > 100000) err("maximum loop iterations (100000) exceeded", n)
                        guardLoop(n)
                        try { execStmt(n.body) }
                        catch (f: Flow) {
                            if (f.kind == "break") break
                            if (f.kind == "continue") { /* continue 落到条件判断 */ }
                            else throw f
                        }
                    } while (truthy(evalExpr(n.cond), n.cond))
                }
                is ForNode -> {
                    pushScope(HashMap())
                    try {
                        if (n.init != null) execForPart(n.init)
                        var iter = 0
                        while (n.cond == null || truthy(evalExpr(n.cond), n.cond)) {
                            if (++iter > 100000) err("maximum loop iterations (100000) exceeded", n)
                            guardLoop(n)
                            try { execStmt(n.body) }
                            catch (f: Flow) {
                                if (f.kind == "break") break
                                if (f.kind == "continue") { /* 落到 inc */ }
                                else throw f
                            }
                            if (n.inc != null) execForPart(n.inc)
                        }
                    } finally { popScope() }
                }
                is ForOfNode -> execForOf(n)
                is BreakNode -> throw Flow("break")
                is ContinueNode -> throw Flow("continue")
                is ReturnNode -> {
                    if (!inFunction) err("return is only allowed inside a function", n)
                    throw Flow("return", if (n.expr != null) evalExpr(n.expr) else Undefined)
                }
                is DeclareNode -> execDeclare(n)
                is AssignNode -> execAssign(n)
                is ExprStmtNode -> evalExpr(n.expr)
                is DestructureNode -> {
                    val v = evalExpr(n.value)
                    val fields = (v as? ObjVal)?.fields
                        ?: err("destructuring requires an object, got ${typeName(v)}", n)
                    for (name in n.names) {
                        if (currentScope().containsKey(name)) err("duplicate declaration '$name'", n)
                        currentScope()[name] = fields[name] ?: Undefined
                        if (n.kind == "const") markConst(name)
                    }
                }
                is WhenStmtNode -> {
                    val subj = evalExpr(n.subject)
                    for (c in n.cases) {
                        if (whenEqual(subj, evalExpr(c.label))) { execStmt(c.body); return }
                    }
                    if (n.els != null) execStmt(n.els)
                }
                else -> err("unknown statement ${n::class.simpleName}", n)
            }
        }

        private fun execDeclare(n: DeclareNode) {
            for (d in n.decls) {
                if (currentScope().containsKey(d.name)) {
                    err("duplicate declaration '${d.name}'", n)
                }
                val v = if (d.init != null) evalExpr(d.init) else Undefined
                currentScope()[d.name] = v
                if (n.kind == "const") markConst(d.name)
            }
        }

        private fun execForPart(part: Node) {
            when {
                part is AssignNode -> execAssign(part)
                part is DeclareNode -> execDeclare(part)
                else -> evalExpr(part)
            }
        }

        private fun execForOf(n: ForOfNode) {
            val iter = evalExpr(n.iter)
            val snapshot: List<Any?> = when (iter) {
                is ParticleListValue -> iter.hosts.toList().map { ParticleValue(it) }
                is MutableList<*> -> iter.toList()
                else -> err("for-of requires a particle list or array, got ${typeName(iter)}", n.iter)
            }
            pushScope(HashMap())
            if (n.kind == "const") markConst(n.name)
            try {
                var idx = 0
                for (item in snapshot) {
                    if (++idx > 100000) err("maximum loop iterations (100000) exceeded", n)
                    guardLoop(n)
                    currentScope()[n.name] = item
                    try { execStmt(n.body) }
                    catch (f: Flow) {
                        if (f.kind == "break") break
                        if (f.kind == "continue") continue
                        throw f
                    }
                }
            } finally { popScope() }
        }

        private fun execAssign(n: AssignNode) {
            val target = n.target
            if (target is UnpackTarget) {
                val v = evalExpr(n.value)
                val comps = when (v) {
                    is Vec2 -> listOf(v.x, v.y)
                    is Vec3 -> listOf(v.x, v.y, v.z)
                    is Vec4 -> listOf(v.x, v.y, v.z, v.w)
                    is MutableList<*> -> v.toList()
                    else -> err("unpack requires a vector or array, got ${typeName(v)}", n)
                }
                if (comps.size != target.names.size) err("unpack count mismatch", n)
                for ((i, name) in target.names.withIndex()) assignName(name, comps[i], n)
            } else {
                assignTarget(target, evalExpr(n.value), n)
            }
        }

        private fun assignTarget(target: AssignTarget, value: Any?, n: Node) {
            when (target) {
                is VarTarget -> assignName(target.name, value, n)
                is MemberTarget -> assignMemberField(target, value, n)
                is IndexTarget -> {
                    val arr = evalExpr(target.target)
                    if (arr is ObjVal) {
                        val key = evalExpr(target.index)
                        if (key !is String) err("object index must be a string", n)
                        arr.fields[key] = value
                        return
                    }
                    if (arr !is MutableList<*>) err("indexed assignment target is not an array", n)
                    val idx = int(evalExpr(target.index), "array index", n)
                    if (idx < 0 || idx >= arr.size) err("array index $idx out of bounds (size ${arr.size})", n)
                    (arr as MutableList<Any?>)[idx] = value
                }
                is CompTarget -> {
                    val obj = evalExpr(target.target)
                    // particle 上 .x/.y/.z/.w/.r/.g/.b/.a 不是保留字段，按自定义字段存取（p.color.a 仍是颜色分量）。
                    if (obj is ParticleValue) {
                        particleSetField(obj, target.comp, value, n)
                        return
                    }
                    if (obj is ColorVal) {
                        val d = num(value, "component value", n)
                        val updated = when (COMP_ALIAS[target.comp] ?: target.comp) {
                            "x" -> obj.copy(r = d); "y" -> obj.copy(g = d); "z" -> obj.copy(b = d); else -> obj.copy(a = d)
                        }
                        assignCompTarget(target.target, updated, n)
                        return
                    }
                    if (obj is ObjVal) {
                        obj.fields[target.comp] = value
                        return
                    }
                    if (!isVec(obj)) err("component assignment target is not a vector", n)
                    val comp = COMP_ALIAS[target.comp] ?: err("unknown component '${target.comp}'", n)
                    if (!hasComp(obj, comp)) err("${typeName(obj)} has no component '${target.comp}'", n)
                    val d = num(value, "component value", n)
                    val updated = setVecComp(obj, comp, d)
                    assignCompTarget(target.target, updated, n)
                }
                is UnpackTarget -> {
                    err("unpack assignment requires execAssign", n)
                }
            }
        }

        /** 分量赋值：把更新后的向量写回其目标（变量 / 粒子字段 / 数组下标）。 */
        private fun assignCompTarget(target: Node, updated: Any, n: Node) {
            when (target) {
                is VarNode -> assignName(target.name, updated, n)
                is MemberNode -> assignMemberField(MemberTarget(target.obj, target.field, target.line, target.col), updated, n)
                is IndexNode -> {
                    val arr = evalExpr(target.target)
                    if (arr !is MutableList<*>) err("indexed assignment target is not an array", n)
                    val idx = int(evalExpr(target.index), "array index", n)
                    if (idx < 0 || idx >= arr.size) err("array index $idx out of bounds (size ${arr.size})", n)
                    (arr as MutableList<Any?>)[idx] = updated
                }
                else -> err("component assignment requires a variable, particle field or array index target", n)
            }
        }

        private fun assignMemberField(target: MemberTarget, value: Any?, n: Node) {
            if (target.obj is VarNode && target.obj.name == CTX_NAME) {
                if (receiverStack.isNotEmpty()) {
                    particleSetField(receiverStack.last(), target.field, value, n)
                    return
                }
                err("this.${target.field} is read-only", n)
            }
            val obj = evalExpr(target.obj)
            if (obj is ParticleValue) {
                particleSetField(obj, target.field, value, n)
                return
            }
            if (obj is ObjVal) {
                obj.fields[target.field] = value
                return
            }
            err("member '.${target.field}' requires a particle or object, got ${typeName(obj)}", n)
        }

        private fun assignName(name: String, value: Any?, n: Node) {
            if (name == CTX_NAME) {
                err("cannot assign to 'this'; use this.<field> = ...", n)
            }
            for (i in scopes.indices.reversed()) {
                val s = scopes[i]
                if (s.containsKey(name)) {
                    val cs = constSets.getOrNull(i)
                    if (cs != null && name in cs) err("cannot assign to const '$name'", n)
                    s[name] = value
                    return
                }
            }
            if (objState.globals.containsKey(name)) {
                if (name in objState.constGlobals) err("cannot assign to const '$name'", n)
                objState.globals[name] = value
                return
            }
            if (name in CONSTANTS || varsMap.containsKey(name)) {
                err("cannot assign to read-only name '$name'", n)
            }
            err("undeclared variable '$name'", n)
        }

        // —— this 字段读取 ——

        private fun ctxRead(field: String, n: Node): Any? {
            if (phase == "expr") {
                val c = pctx
                when (field) {
                    "index" -> return c?.i ?: 0.0
                    "count" -> return c?.n ?: 0.0
                    "time" -> return c?.t ?: 0.0
                    "delta" -> return c?.dt ?: 0.0
                    "duration" -> return c?.duration ?: 0.0
                    "uv" -> return Vec2(c?.uv_x ?: 0.0, c?.uv_y ?: 0.0)
                }
                val out = c?.out ?: err("output unavailable", n)
                return when (field) {
                    "position" -> Vec3(out.pos[0], out.pos[1], out.pos[2])
                    "color" -> Vec4(out.color[0], out.color[1], out.color[2], out.color[3])
                    "velocity" -> Vec3(out.vel[0], out.vel[1], out.vel[2])
                    "scale" -> out.scale
                    "glow" -> out.glow
                    "light" -> out.light
                    "life" -> out.life
                    else -> err("unknown this field '.$field'", n)
                }
            }
            // spawn 模型（setup/tick/process）
            val c = ctx ?: err("context unavailable", n)
            return when (field) {
                "time" -> c.t - c.st
                "animTime" -> c.t
                "duration" -> if (c.duration > 0.0) c.duration else c.maxMs
                "particles" -> ParticleListValue(c.particles)
                else -> err("this.$field is not available here", n)
            }
        }

        // —— 粒子句柄字段读取/写入 ——

        private fun particleGetField(pv: ParticleValue, field: String, n: Node): Any? {
            val w = pv.host
            return when (field) {
                "position" -> Vec3(w.pos[0], w.pos[1], w.pos[2])
                "color" -> ColorVal(w.color[0], w.color[1], w.color[2], w.color[3])
                "velocity" -> Vec3(w.vel[0], w.vel[1], w.vel[2])
                "scale" -> w.scale
                "glow" -> w.glow
                "light" -> w.light
                "life" -> w.life
                "index" -> w.index.toDouble()
                "rotation" -> Vec3(w.rotation[0], w.rotation[1], w.rotation[2])
                "billboard" -> w.billboard
                "spinSpace" -> if (w.spinLocal) "local" else "world"
                else -> w.fields[field] ?: Undefined
            }
        }

        // —— 文字对象句柄读取（只读，与编辑器 script-lang.js 的 textGetField 一致）——

        private fun textGetField(v: TextValue, field: String, n: Node): Any? = when (field) {
            "name" -> v.obj.name
            "text" -> v.obj.text
            "st" -> v.obj.st.toDouble()
            "life" -> v.obj.life.toDouble()
            "chars" -> v.obj.chars.mapTo(ArrayList()) { TextCharValue(it) }
            else -> err("text has no field '.$field'", n)
        }

        private fun textCharGetField(v: TextCharValue, field: String, n: Node): Any? = when (field) {
            "index" -> v.ch.index.toDouble()
            "code" -> v.ch.code.toDouble()
            "pos" -> Vec3(v.ch.pos.x, v.ch.pos.y, v.ch.pos.z)
            "size" -> Vec2(v.ch.size[0], v.ch.size[1])
            "particles" -> v.ch.particles.toMutableList()
            else -> err("char has no field '.$field'", n)
        }

        // —— 音频句柄读取（只读；随时间字段按帧时查表插值，与编辑器 script-lang.js 一致）——

        private fun audioLocalMs(v: AudioValue): Double {
            val st = v.asset.st.toDouble()
            return (v.at - st).coerceIn(0.0, v.asset.durMs.toDouble())
        }

        private fun audioGetField(v: AudioValue, field: String, n: Node): Any? = when (field) {
            "name" -> v.asset.name
            "st" -> v.asset.st.toDouble()
            "length" -> v.asset.durMs.toDouble()
            "progress" -> audioLocalMs(v)
            "playing" -> v.playing
            "bpm" -> v.asset.bpm
            "beats" -> v.asset.beats.mapTo(ArrayList()) { it.toDouble() }
            "loud" -> ScriptAudio.valueAt(v.asset, audioLocalMs(v)).rms
            "peak" -> ScriptAudio.valueAt(v.asset, audioLocalMs(v)).peak
            "onset" -> ScriptAudio.valueAt(v.asset, audioLocalMs(v)).onset
            "centroid" -> ScriptAudio.valueAt(v.asset, audioLocalMs(v)).centroid
            "rolloff" -> ScriptAudio.valueAt(v.asset, audioLocalMs(v)).rolloff
            else -> err("audio has no field '.$field'", n)
        }

        private fun audioBand(v: AudioValue, idx: Any?, n: Node): Double {
            val i = if (idx is Double && idx % 1.0 == 0.0) idx.toInt() else -1
            if (i !in 0 until ScriptAudio.BANDS) {
                err("band requires an integer 0..${ScriptAudio.BANDS - 1}, got ${typeName(idx)}", n)
            }
            return ScriptAudio.valueAt(v.asset, audioLocalMs(v)).bands[i]
        }

        private fun vecFieldValues(value: Any?, len: Int, what: String, n: Node): List<Double> {
            if (value != null && isVec(value)) {
                if (vecDim(value) != len) {
                    err("$what requires a vec$len, got ${typeName(value)}", n)
                }
                return vecComps(value)
            }
            if (value is MutableList<*>) {
                if (value.size != len) {
                    err("$what requires an array of $len numbers, got length ${value.size}", n)
                }
                return value.map { num(it, "$what[$it]", n) }
            }
            err("$what requires a vec$len or array of $len numbers, got ${typeName(value)}", n)
        }

        private fun writeParticleColor(w: ParticleHost, value: Any?, n: Node) {
            when {
                value is ColorVal -> {
                    w.color[0] = clamp01(value.r); w.color[1] = clamp01(value.g); w.color[2] = clamp01(value.b); w.color[3] = clamp01(value.a)
                }
                value is Vec3 -> {
                    w.color[0] = clamp01(value.x); w.color[1] = clamp01(value.y); w.color[2] = clamp01(value.z)
                }
                value is Vec4 -> {
                    w.color[0] = clamp01(value.x); w.color[1] = clamp01(value.y); w.color[2] = clamp01(value.z); w.color[3] = clamp01(value.w)
                }
                value is MutableList<*> && value.size == 3 -> {
                    w.color[0] = clamp01(num(value[0], "particle.color[0]", n))
                    w.color[1] = clamp01(num(value[1], "particle.color[1]", n))
                    w.color[2] = clamp01(num(value[2], "particle.color[2]", n))
                }
                value is MutableList<*> && value.size == 4 -> {
                    w.color[0] = clamp01(num(value[0], "particle.color[0]", n))
                    w.color[1] = clamp01(num(value[1], "particle.color[1]", n))
                    w.color[2] = clamp01(num(value[2], "particle.color[2]", n))
                    w.color[3] = clamp01(num(value[3], "particle.color[3]", n))
                }
                else -> err("particle.color requires a vec3, vec4, [r,g,b] or [r,g,b,a], got ${typeName(value)}", n)
            }
        }

        private fun particleSetField(pv: ParticleValue, field: String, value: Any?, n: Node) {
            val w = pv.host
            when (field) {
                "position" -> {
                    val c = vecFieldValues(value, 3, "particle.position", n)
                    w.pos[0] = c[0]; w.pos[1] = c[1]; w.pos[2] = c[2]
                }
                "velocity" -> {
                    val c = vecFieldValues(value, 3, "particle.velocity", n)
                    w.vel[0] = c[0]; w.vel[1] = c[1]; w.vel[2] = c[2]
                }
                "color" -> writeParticleColor(w, value, n)
                "scale" -> w.scale = num(value, "particle.scale", n)
                "glow" -> {
                    if (!isNum(value) && !isBool(value)) {
                        err("particle.glow requires a num/bool, got ${typeName(value)}", n)
                    }
                    w.glow = if (value is Boolean) value else (value as Double) > 0.5
                }
                "light" -> w.light = clampNum(jsRound(num(value, "particle.light", n)), 0.0, 15.0)
                "life" -> {
                    val v = jsRound(num(value, "particle.life", n))
                    w.life = if (v.isFinite()) (if (v < 0.0) -1.0 else v) else -1.0
                }
                "rotation" -> {
                    val c = vecFieldValues(value, 3, "particle.rotation", n)
                    w.rotation[0] = c[0]; w.rotation[1] = c[1]; w.rotation[2] = c[2]
                }
                "billboard" -> {
                    if (!isNum(value) && !isBool(value)) {
                        err("particle.billboard requires a num/bool, got ${typeName(value)}", n)
                    }
                    w.billboard = if (value is Boolean) value else (value as Double) > 0.5
                }
                "spinSpace" -> {
                    if (value !is String || (value != "local" && value != "world")) {
                        err("particle.spinSpace requires 'local' or 'world', got ${typeName(value)}", n)
                    }
                    w.spinLocal = value == "local"
                }
                "index" -> err("particle.index is read-only", n)
                else -> w.fields[field] = value
            }
        }

        fun evalExpr(n: Node): Any? = when (n) {
            is NumNode -> n.value
            is StrNode -> n.value
            is BoolNode -> n.value
            is UndefinedNode -> Undefined
            is VarNode -> lookupName(n.name, n)
            is ArrayNode -> n.items.map { evalExpr(it) }.toMutableList()
            is UnaryNode -> {
                val v = evalExpr(n.operand)
                when (n.op) {
                    "-" -> negate(v, n)
                    "!" -> !truthy(v, n)
                    else -> err("unknown unary operator '${n.op}'", n)
                }
            }
            is BinaryNode -> evalBinary(n)
            is TernaryNode -> if (truthy(evalExpr(n.cond), n.cond)) evalExpr(n.thenExpr) else evalExpr(n.elseExpr)
            is IndexNode -> evalIndex(n)
            is CompNode -> {
                val v = evalExpr(n.target)
                // particle 上 .x/.y/.z/.w/.r/.g/.b/.a 不是保留字段，按自定义字段读取。
                if (v is ParticleValue) return particleGetField(v, n.comp, n)
                if (v is ColorVal) {
                    return when (COMP_ALIAS[n.comp] ?: n.comp) {
                        "x" -> v.r; "y" -> v.g; "z" -> v.b; else -> v.a
                    }
                }
                if (v is ObjVal) return v.fields[n.comp] ?: Undefined
                if (!isVec(v)) err("component access requires a vector, got ${typeName(v)}", n)
                val comp = COMP_ALIAS[n.comp] ?: err("unknown component '${n.comp}'", n)
                if (!hasComp(v, comp)) err("${typeName(v)} has no component '${n.comp}'", n)
                when (v) {
                    is Vec2 -> when (comp) { "x" -> v.x; else -> v.y }
                    is Vec3 -> when (comp) { "x" -> v.x; "y" -> v.y; else -> v.z }
                    is Vec4 -> when (comp) { "x" -> v.x; "y" -> v.y; "z" -> v.z; else -> v.w }
                    else -> err("component access requires a vector, got ${typeName(v)}", n)
                }
            }
            is MemberNode -> evalMember(n)
            is CallNode -> evalCall(n)
            is MethodNode -> evalMethod(n)
            is PreIncNode -> {
                val old = evalLValue(n.target)
                val nv = incDecValue(old, n.op, n)
                assignTarget(n.target, nv, n)
                nv
            }
            is PostIncNode -> {
                val old = evalLValue(n.target)
                val nv = incDecValue(old, n.op, n)
                assignTarget(n.target, nv, n)
                old
            }
            is LambdaNode -> LambdaVal(n.params, n.body, ArrayList(scopes))
            is ObjNode -> ObjVal(LinkedHashMap<String, Any?>().also { m -> for ((k, e) in n.fields) m[k] = evalExpr(e) })
            is ApplyNode -> applyReceiver(n)
            is WhenExprNode -> {
                val subj = evalExpr(n.subject)
                for (c in n.cases) {
                    if (whenEqual(subj, evalExpr(c.label))) return evalExpr(c.expr)
                }
                evalExpr(n.els)
            }
            else -> err("unknown expression ${n::class.simpleName}", n)
        }

        private fun evalIndex(n: IndexNode): Any? {
            val target = evalExpr(n.target)
            if (target is ObjVal) {
                val key = evalExpr(n.index)
                if (key !is String) err("object index must be a string", n)
                return target.fields[key] ?: Undefined
            }
            val idx = int(evalExpr(n.index), "index", n)
            if (target is ParticleListValue) {
                if (idx < 0 || idx >= target.size) err("particle list index $idx out of bounds (size ${target.size})", n)
                return target.get(idx)
            }
            if (target !is MutableList<*>) err("index access requires an array or particle list, got ${typeName(target)}", n)
            if (idx < 0 || idx >= target.size) err("array index $idx out of bounds (size ${target.size})", n)
            return target[idx]
        }

        private fun evalMember(n: MemberNode): Any? {
            if (n.obj is VarNode && n.obj.name == CTX_NAME) {
                if (receiverStack.isNotEmpty()) return particleGetField(receiverStack.last(), n.field, n)
                return ctxRead(n.field, n)
            }
            val obj = evalExpr(n.obj)
            if (obj is ParticleValue) return particleGetField(obj, n.field, n)
            if (obj is ObjVal) return obj.fields[n.field] ?: Undefined
            if (obj is TextValue) return textGetField(obj, n.field, n)
            if (obj is TextCharValue) return textCharGetField(obj, n.field, n)
            if (obj is AudioValue) return audioGetField(obj, n.field, n)
            err("member '.${n.field}' requires a particle or object, got ${typeName(obj)}", n)
        }

        private fun evalLValue(target: AssignTarget): Any? = when (target) {
            is VarTarget -> lookupName(target.name, VarNode(target.name, target.line, target.col))
            is MemberTarget -> evalMember(MemberNode(target.obj, target.field, target.line, target.col))
            is IndexTarget -> evalIndex(IndexNode(target.target, target.index, target.line, target.col))
            is CompTarget -> evalExpr(CompNode(target.target, target.comp, target.line, target.col))
            is UnpackTarget -> err("invalid increment target", VarNode("<unpack>", target.line, target.col))
        }

        private fun incDecValue(v: Any?, op: String, n: Node): Any? {
            if (v !is Double) err("'$op' requires a num, got ${typeName(v)}", n)
            return if (op == "++") v + 1.0 else v - 1.0
        }

        private fun evalCall(n: CallNode): Any? {
            val args = n.args.map { evalExpr(it) }
            val callee = n.callee
            if (callee is VarNode) {
                if (callee.name in BUILTINS) return callBuiltin(callee.name, args, n)
                if (program.functions.containsKey(callee.name)) return callUserFunc(program.functions[callee.name]!!, args, n)
            }
            val fn = evalExpr(callee)
            if (fn is FuncVal) return callUserFunc(program.functions[fn.name] ?: err("function '${fn.name}' not found", n), args, n)
            if (fn is LambdaVal) return callLambda(fn, args, n)
            err("value of type ${typeName(fn)} is not callable", n)
        }

        private fun evalMethod(n: MethodNode): Any? {
            // this.get(资产名)：取工程级资产句柄（文字对象），只读。
            if (n.obj is VarNode && n.obj.name == CTX_NAME && n.method == "get") {
                if (n.args.size != 1) err("this.get expects exactly 1 argument", n)
                val c = ctx ?: err("this.get is not available here", n)
                val name = evalExpr(n.args[0])
                if (name !is String) err("this.get expects an asset name string, got ${typeName(name)}", n)
                return try {
                    c.get(name)
                } catch (e: ScriptException) {
                    err(e.message ?: "asset lookup failed", n)
                }
            }
            // this.spawn(config?)：config 为可选 JSON 对象。
            if (n.obj is VarNode && n.obj.name == CTX_NAME && n.method == "spawn") {
                val c = ctx ?: err("this.spawn is not available here", n)
                val cfg = n.args.firstOrNull()?.let { evalExpr(it) }
                val w = try { c.spawnConfig(cfg) } catch (e: Exception) { err("spawn failed: ${e.message}", n) }
                return ParticleValue(w)
            }
            return invokeMethod(n.obj, n.method, n.args.map { evalExpr(it) }, n)
        }

        private fun invokeMethod(objNode: Node, method: String, args: List<Any?>, n: Node): Any? {
            val obj = evalExpr(objNode)
            if (obj is ParticleValue) {
                if (method == "kill") {
                    if (args.isNotEmpty()) err("'kill' takes no arguments", n)
                    obj.host.kill()
                    return 0.0
                }
                err("particle has no method '.$method()'", n)
            }
            if (obj is ParticleListValue) {
                if (method == "size") return obj.size.toDouble()
                err("particle list has no method '.$method()'", n)
            }
            if (isVec(obj)) return vecMethod(obj, method, args, n)
            if (obj is ColorVal) return colorMethod(obj, method, args, n)
            if (obj is AudioValue) {
                if (method == "band") {
                    if (args.size != 1) err("'band' expects exactly 1 argument", n)
                    return audioBand(obj, args[0], n)
                }
                err("audio has no method '.$method()'", n)
            }
            if (obj !is MutableList<*>) err("method '.$method()' requires an array, particle, particle list, vector or color, got ${typeName(obj)}", n)
            return arrayMethod(obj as MutableList<Any?>, method, args, n)
        }

        private fun lookupName(name: String, n: Node): Any? {
            if (name == CTX_NAME) {
                err("'this' is not a value; use this.<field>", n)
            }
            for (i in scopes.indices.reversed()) {
                val s = scopes[i]
                if (s.containsKey(name)) {
                    val v = s[name]
                    if (v === IT_UNSET) err("it is not defined in a no-argument lambda call", n)
                    return v
                }
            }
            if (objState.globals.containsKey(name)) return objState.globals[name]
            if (varsMap.containsKey(name)) return varsMap[name]
            if (name in CONSTANTS) return CONSTANTS[name]
            if (program.functions.containsKey(name)) return FuncVal(name)
            err("unknown variable '$name'", n)
        }

        private fun negate(v: Any?, n: Node): Any? = when (v) {
            is Double -> -v
            is Vec2 -> Vec2(-v.x, -v.y)
            is Vec3 -> Vec3(-v.x, -v.y, -v.z)
            is Vec4 -> Vec4(-v.x, -v.y, -v.z, -v.w)
            is Mat3 -> Mat3(v.m.map { r -> r.map { -it } })
            is Mat4 -> Mat4(v.m.map { r -> r.map { -it } })
            else -> err("cannot negate ${typeName(v)}", n)
        }

        private fun evalBinary(n: BinaryNode): Any? {
            // && / || 返回操作数值并短路（与编辑器一致：真值返回左操作数，否则右操作数）。
            if (n.op == "&&") {
                val l = evalExpr(n.left)
                if (!truthy(l, n.left)) return l
                val r = evalExpr(n.right)
                if (!isNum(r) && !isBool(r) && !isUndefined(r)) {
                    err("'&&' requires num/bool operands, got ${typeName(r)}", n)
                }
                return r
            }
            if (n.op == "||") {
                val l = evalExpr(n.left)
                if (truthy(l, n.left)) return l
                val r = evalExpr(n.right)
                if (!isNum(r) && !isBool(r) && !isUndefined(r)) {
                    err("'||' requires num/bool operands, got ${typeName(r)}", n)
                }
                return r
            }
            val a = evalExpr(n.left)
            val b = evalExpr(n.right)
            return when (n.op) {
                "==" -> eqExact(a, b)
                "!=" -> !eqExact(a, b)
                "<", "<=", ">", ">=" -> cmp(a, b, n.op, n)
                "+", "-", "*", "/", "%", "^" -> arith(n.op, a, b, n)
                else -> err("unknown binary operator '${n.op}'", n)
            }
        }

        private fun cmp(a: Any?, b: Any?, op: String, n: Node): Boolean {
            val x = num(a, "comparison", n); val y = num(b, "comparison", n)
            return when (op) { "<" -> x < y; "<=" -> x <= y; ">" -> x > y; else -> x >= y }
        }

        private fun arith(op: String, a: Any?, b: Any?, n: Node): Any? {
            if (a is Double && b is Double) {
                return when (op) {
                    "+" -> a + b; "-" -> a - b; "*" -> a * b
                    "/" -> if (b == 0.0) err("division by zero", n) else a / b
                    "%" -> a % b; "^" -> a.pow(b)
                    else -> err("unknown op '$op'", n)
                }
            }
            if (a is Mat3 || a is Mat4 || b is Mat3 || b is Mat4) return matArith(op, a, b, n)
            if (isVec(a) || isVec(b)) return vecArith(op, a, b, n)
            err("cannot apply '$op' to ${typeName(a)} and ${typeName(b)}", n)
        }

        private fun vecArith(op: String, a: Any?, b: Any?, n: Node): Any? {
            if (isVec(a) && isVec(b)) {
                val dim = vecDim(a!!)
                if (vecDim(b!!) != dim) err("vector dimension mismatch", n)
                val ca = vecComps(a); val cb = vecComps(b)
                val out = when (op) {
                    "+" -> ca.zip(cb).map { it.first + it.second }
                    "-" -> ca.zip(cb).map { it.first - it.second }
                    "*" -> ca.zip(cb).map { it.first * it.second }
                    else -> err("operator '$op' not supported for ${typeName(a)} and ${typeName(b)}", n)
                }
                return mkVec(dim, out)
            }
            if (isVec(a) && isNum(b)) {
                val dim = vecDim(a!!)
                val ca = vecComps(a)
                val s = b as Double
                return when (op) {
                    "*" -> mkVec(dim, ca.map { it * s })
                    "/" -> { if (s == 0.0) err("division by zero", n); mkVec(dim, ca.map { it / s }) }
                    else -> err("operator '$op' not supported for ${typeName(a)} and ${typeName(b)}", n)
                }
            }
            if (isNum(a) && isVec(b)) {
                val dim = vecDim(b!!)
                val cb = vecComps(b)
                val s = a as Double
                return when (op) {
                    "*" -> mkVec(dim, cb.map { s * it })
                    else -> err("operator '$op' not supported for ${typeName(a)} and ${typeName(b)}", n)
                }
            }
            return err("operator '$op' not supported for ${typeName(a)} and ${typeName(b)}", n)
        }

        private fun matArith(op: String, a: Any?, b: Any?, n: Node): Any? {
            when (op) {
                "*" -> {
                    if (a is Mat3 && b is Vec3) {
                        val m = a.m
                        return Vec3(
                            m[0][0] * b.x + m[0][1] * b.y + m[0][2] * b.z,
                            m[1][0] * b.x + m[1][1] * b.y + m[1][2] * b.z,
                            m[2][0] * b.x + m[2][1] * b.y + m[2][2] * b.z,
                        )
                    }
                    if (a is Mat4 && b is Vec3) {
                        val m = a.m
                        val x = m[0][0] * b.x + m[0][1] * b.y + m[0][2] * b.z + m[0][3]
                        val y = m[1][0] * b.x + m[1][1] * b.y + m[1][2] * b.z + m[1][3]
                        val z = m[2][0] * b.x + m[2][1] * b.y + m[2][2] * b.z + m[2][3]
                        return Vec3(x, y, z)
                    }
                    if (a is Mat4 && b is Vec4) {
                        val m = a.m
                        return Vec4(
                            m[0][0] * b.x + m[0][1] * b.y + m[0][2] * b.z + m[0][3] * b.w,
                            m[1][0] * b.x + m[1][1] * b.y + m[1][2] * b.z + m[1][3] * b.w,
                            m[2][0] * b.x + m[2][1] * b.y + m[2][2] * b.z + m[2][3] * b.w,
                            m[3][0] * b.x + m[3][1] * b.y + m[3][2] * b.z + m[3][3] * b.w,
                        )
                    }
                    if (a is Mat3 && b is Mat3) return Mat3(matMul(a.m, b.m))
                    if (a is Mat4 && b is Mat4) return Mat4(matMul(a.m, b.m))
                    if (a is Mat3 && b is Double) return Mat3(a.m.map { r -> r.map { it * b } })
                    if (a is Mat4 && b is Double) return Mat4(a.m.map { r -> r.map { it * b } })
                    if (b is Mat3 && a is Double) return Mat3(b.m.map { r -> r.map { it * a } })
                    if (b is Mat4 && a is Double) return Mat4(b.m.map { r -> r.map { it * a } })
                }
                "+", "-" -> {
                    if (a is Mat3 && b is Mat3) return Mat3(a.m.zip(b.m).map { (ra, rb) -> ra.zip(rb).map { if (op == "+") it.first + it.second else it.first - it.second } })
                    if (a is Mat4 && b is Mat4) return Mat4(a.m.zip(b.m).map { (ra, rb) -> ra.zip(rb).map { if (op == "+") it.first + it.second else it.first - it.second } })
                    if ((a is Mat3 && b is Mat4) || (a is Mat4 && b is Mat3)) err("matrix dimension mismatch", n)
                }
                "/" -> {
                    if (a is Mat3 && b is Double) { if (b == 0.0) err("division by zero", n); return Mat3(a.m.map { r -> r.map { it / b } }) }
                    if (a is Mat4 && b is Double) { if (b == 0.0) err("division by zero", n); return Mat4(a.m.map { r -> r.map { it / b } }) }
                }
            }
            err("unsupported matrix operation '$op'", n)
        }

        private fun matMul(a: List<List<Double>>, b: List<List<Double>>): List<List<Double>> {
            val n = a.size
            return List(n) { i -> List(n) { j -> (0 until n).sumOf { k -> a[i][k] * b[k][j] } } }
        }

        private fun eqExact(a: Any?, b: Any?, depth: Int = 0): Boolean {
            if (depth > MAX_VALUE_DEPTH) throw ScriptException("value nesting too deep")
            return when {
                isUndefined(a) || isUndefined(b) -> isUndefined(a) && isUndefined(b)
                a is Double && b is Double -> a == b
                a is Boolean && b is Boolean -> a == b
                a is Vec2 && b is Vec2 -> a == b
                a is Vec3 && b is Vec3 -> a == b
                a is Vec4 && b is Vec4 -> a == b
                a is Mat3 && b is Mat3 -> a.m == b.m
                a is Mat4 && b is Mat4 -> a.m == b.m
                a is ColorVal && b is ColorVal -> a == b
                a is MutableList<*> && b is MutableList<*> -> a.size == b.size && a.withIndex().all { (i, v) -> eqExact(v, b[i], depth + 1) }
                else -> false
            }
        }

        private fun arrayMethod(arr: MutableList<Any?>, method: String, args: List<Any?>, n: Node): Any? = when (method) {
            "push" -> { if (args.size != 1) err("push expects 1 argument", n); arr.add(args[0]); arr }
            "insert" -> {
                if (args.size != 2) err("insert expects 2 arguments", n)
                val idx = int(args[0], "insert index", n)
                if (idx < 0 || idx > arr.size) err("insert index $idx out of bounds (size ${arr.size})", n)
                arr.add(idx, args[1]); arr
            }
            "remove" -> {
                if (args.size != 1) err("remove expects 1 argument", n)
                val idx = int(args[0], "remove index", n)
                if (idx < 0 || idx >= arr.size) err("remove index $idx out of bounds (size ${arr.size})", n)
                arr.removeAt(idx); arr
            }
            "slice" -> {
                if (args.size > 2) err("slice expects at most 2 arguments", n)
                val size = arr.size
                fun normIdx(x: Double): Int { val k = jsTrunc(x).toInt(); return if (k < 0) (size + k).coerceAtLeast(0) else k.coerceAtMost(size) }
                val s = if (args.isNotEmpty()) normIdx(num(args[0], "slice start", n)) else 0
                val e = if (args.size > 1) normIdx(num(args[1], "slice end", n)) else size
                if (s > e) mutableListOf<Any?>() else arr.subList(s, e).toMutableList()
            }
            "size" -> { if (args.isNotEmpty()) err("size expects no arguments", n); arr.size.toDouble() }
            "find" -> {
                if (args.size != 1) err("find expects 1 argument", n)
                val v = args[0]
                arr.indexOfFirst { eqTol(it, v) }.toDouble()
            }
            "includes" -> { if (args.size != 1) err("includes expects 1 argument", n); arr.any { eqTol(it, args[0]) } }
            "sort" -> {
                if (args.size > 1) err("sort expects at most 1 argument", n)
                if (args.isEmpty()) arr.sortWith { x, y -> defaultCompare(x, y, n) }
                else {
                    val cmp = args[0]
                    if (cmp !is FuncVal && cmp !is LambdaVal) err("sort comparator must be a function, got ${typeName(cmp)}", n)
                    arr.sortWith { x, y ->
                        val res = when (cmp) {
                            is FuncVal -> callUserFunc(program.functions[cmp.name] ?: err("function '${cmp.name}' not found", n), listOf(x, y), n)
                            else -> callLambda(cmp as LambdaVal, listOf(x, y), n)
                        }
                        if (res !is Double) err("comparator function must return a num", n)
                        if (res > 0.0) 1 else if (res < 0.0) -1 else 0
                    }
                }
                arr
            }
            "unique" -> {
                if (args.isNotEmpty()) err("unique expects no arguments", n)
                val out = ArrayList<Any?>()
                for (v in arr) if (out.none { eqTol(it, v) }) out.add(v)
                out
            }
            "reverse" -> { if (args.isNotEmpty()) err("reverse expects no arguments", n); arr.reverse(); arr }
            else -> err("unknown array method '$method'", n)
        }

        private fun eqTol(a: Any?, b: Any?, depth: Int = 0): Boolean {
            if (depth > MAX_VALUE_DEPTH) throw ScriptException("value nesting too deep")
            return when {
                isUndefined(a) || isUndefined(b) -> isUndefined(a) && isUndefined(b)
                a is Double && b is Double -> abs(a - b) <= 1e-6
                a is Boolean && b is Boolean -> a == b
                a is Vec2 && b is Vec2 -> abs(a.x - b.x) <= 1e-6 && abs(a.y - b.y) <= 1e-6
                a is Vec3 && b is Vec3 -> abs(a.x - b.x) <= 1e-6 && abs(a.y - b.y) <= 1e-6 && abs(a.z - b.z) <= 1e-6
                a is Vec4 && b is Vec4 -> abs(a.x - b.x) <= 1e-6 && abs(a.y - b.y) <= 1e-6 && abs(a.z - b.z) <= 1e-6 && abs(a.w - b.w) <= 1e-6
                a is Mat3 && b is Mat3 -> a.m.withIndex().all { (i, row) -> row.withIndex().all { (j, v) -> abs(v - b.m[i][j]) <= 1e-6 } }
                a is Mat4 && b is Mat4 -> a.m.withIndex().all { (i, row) -> row.withIndex().all { (j, v) -> abs(v - b.m[i][j]) <= 1e-6 } }
                a is ColorVal && b is ColorVal -> abs(a.r - b.r) <= 1e-6 && abs(a.g - b.g) <= 1e-6 && abs(a.b - b.b) <= 1e-6 && abs(a.a - b.a) <= 1e-6
                a is MutableList<*> && b is MutableList<*> -> a.size == b.size && a.withIndex().all { (i, v) -> eqTol(v, b[i], depth + 1) }
                else -> false
            }
        }

        private fun defaultCompare(a: Any?, b: Any?, n: Node, depth: Int = 0): Int {
            if (depth > MAX_VALUE_DEPTH) throw ScriptException("value nesting too deep")
            val ta = typeName(a); val tb = typeName(b)
            if (ta != tb) err("cannot sort mixed types ($ta vs $tb)", n)
            fun numCmp(x: Double, y: Double): Int = if (x < y) -1 else if (x > y) 1 else 0
            return when (a) {
                is Double -> numCmp(a, b as Double)
                is Boolean -> { val x = if (a) 1 else 0; val y = if (b as Boolean) 1 else 0; if (x < y) -1 else if (x > y) 1 else 0 }
                is Vec2 -> { val y = b as Vec2; val c = numCmp(a.x, y.x); if (c != 0) c else numCmp(a.y, y.y) }
                is Vec3 -> { val y = b as Vec3; val c = numCmp(a.x, y.x); if (c != 0) c else { val c2 = numCmp(a.y, y.y); if (c2 != 0) c2 else numCmp(a.z, y.z) } }
                is Mat3 -> { val y = b as Mat3; for (i in 0 until 3) for (j in 0 until 3) { val c = numCmp(a.m[i][j], y.m[i][j]); if (c != 0) return c }; 0 }
                is Mat4 -> { val y = b as Mat4; for (i in 0 until 4) for (j in 0 until 4) { val c = numCmp(a.m[i][j], y.m[i][j]); if (c != 0) return c }; 0 }
                is MutableList<*> -> {
                    val x = a; val y = b as MutableList<*>
                    val n2 = minOf(x.size, y.size)
                    for (i in 0 until n2) { val c = defaultCompare(x[i], y[i], n, depth + 1); if (c != 0) return c }
                    if (x.size < y.size) -1 else if (x.size > y.size) 1 else 0
                }
                else -> err("values of type $ta are not sortable", n)
            }
        }

        private fun callUserFunc(fn: FunctionNode, args: List<Any?>, n: Node): Any? {
            if (funcDepth >= 64) err("maximum recursion depth (64) exceeded", n)
            funcDepth++
            val prev = inFunction
            inFunction = true
            pushScope(HashMap())
            for ((i, p) in fn.params.withIndex()) currentScope()[p] = if (i < args.size) args[i] else Undefined
            var result: Any? = Undefined
            try {
                execStmt(fn.body)
            } catch (f: Flow) {
                if (f.kind == "return") result = f.value else throw f
            } finally {
                popScope()
                inFunction = prev
                funcDepth--
            }
            return result
        }

        private fun callLambda(fn: LambdaVal, args: List<Any?>, n: Node): Any? {
            if (funcDepth >= 64) err("maximum recursion depth (64) exceeded", n)
            funcDepth++
            val prev = inFunction
            inFunction = true
            for (s in fn.closure) pushScope(s)
            pushScope(HashMap())
            val pscope = currentScope()
            if (fn.params.isEmpty()) {
                when {
                    args.size == 1 -> pscope["it"] = args[0]
                    args.size > 1 -> err("lambda expects at most 1 argument, got ${args.size}", n)
                    else -> pscope["it"] = IT_UNSET
                }
            } else {
                if (args.size != fn.params.size) err("lambda expects ${fn.params.size} argument(s), got ${args.size}", n)
                for ((i, p) in fn.params.withIndex()) pscope[p] = args[i]
            }
            var result: Any? = Undefined
            try {
                val bodyStmts = fn.body.body
                for (i in bodyStmts.indices) {
                    val st = bodyStmts[i]
                    if (i == bodyStmts.size - 1 && st is ExprStmtNode) result = evalExpr(st.expr)
                    else execStmt(st)
                }
            } catch (f: Flow) {
                if (f.kind == "return") result = f.value else throw f
            } finally {
                popScope()
                for (s in fn.closure) popScope()
                inFunction = prev
                funcDepth--
            }
            return result
        }

        private fun applyReceiver(n: ApplyNode): Any? {
            val target = evalExpr(n.target)
            if (target !is ParticleValue) err(".apply requires a particle, got ${typeName(target)}", n)
            val receiverScope = object : MutableMap<String, Any?> {
                private val backing = HashMap<String, Any?>()
                override val size get() = backing.size
                override val entries get() = backing.entries
                override val keys get() = backing.keys
                override val values get() = backing.values
                override fun containsKey(key: String) = backing.containsKey(key) || particleHasField(target, key)
                override fun containsValue(value: Any?) = backing.containsValue(value)
                override fun get(key: String): Any? {
                    if (backing.containsKey(key)) return backing[key]
                    if (particleHasField(target, key)) return particleGetField(target, key, n)
                    return backing[key]
                }
                override fun isEmpty() = backing.isEmpty()
                override fun clear() = backing.clear()
                override fun put(key: String, value: Any?): Any? {
                    if (particleHasField(target, key)) { particleSetField(target, key, value, n); return value }
                    return backing.put(key, value)
                }
                override fun putAll(from: Map<out String, Any?>) { for ((k, v) in from) put(k, v) }
                override fun remove(key: String): Any? = backing.remove(key)
            }
            pushScope(receiverScope)
            pushScope(HashMap()) // 参数作用域：apply 的 lambda 无参，保持为空
            receiverStack.add(target)
            val prev = inFunction
            inFunction = true
            try {
                val bodyStmts = n.body.body.body
                for (i in bodyStmts.indices) {
                    val st = bodyStmts[i]
                    if (i == bodyStmts.size - 1 && st is ExprStmtNode) evalExpr(st.expr)
                    else execStmt(st)
                }
            } catch (f: Flow) {
                if (f.kind != "return") throw f
            } finally {
                receiverStack.removeAt(receiverStack.size - 1)
                popScope() // 参数作用域
                popScope() // 接收者作用域
                inFunction = prev
            }
            return target
        }

        private fun particleHasField(pv: ParticleValue, field: String): Boolean = when (field) {
            "position", "color", "velocity", "scale", "glow", "light", "life", "index", "rotation", "billboard", "spinSpace" -> true
            else -> pv.host.fields.containsKey(field)
        }

        private fun whenEqual(a: Any?, b: Any?, depth: Int = 0): Boolean {
            if (depth > MAX_VALUE_DEPTH) throw ScriptException("value nesting too deep")
            return when {
                isUndefined(a) || isUndefined(b) -> isUndefined(a) && isUndefined(b)
                a is Double && b is Double -> abs(a - b) <= 1e-6
                a is Boolean && b is Boolean -> a == b
                a is String && b is String -> a == b
                a is MutableList<*> && b is MutableList<*> -> a.size == b.size && a.withIndex().all { (i, v) -> whenEqual(v, b[i], depth + 1) }
                else -> eqTol(a, b)
            }
        }

        private fun vecMethod(v: Any?, method: String, args: List<Any?>, n: Node): Any? {
            fun argVec(name: String): Any? {
                if (args.size != 1) err("$name expects 1 argument", n)
                return args[0]
            }
            return when (method) {
                "normalize" -> {
                    if (args.isNotEmpty()) err("normalize expects no arguments", n)
                    val l = lenVec(v, n); if (l == 0.0) err("cannot normalize a zero-length vector", n); scaleVec(v, 1.0 / l, n)
                }
                "dot" -> dot(v, argVec("dot"), n)
                "cross" -> {
                    if (args.size != 1) err("cross expects 1 argument", n)
                    val a = v as? Vec3 ?: err("cross requires vec3", n)
                    val b = args[0] as? Vec3 ?: err("cross requires vec3", n)
                    Vec3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)
                }
                "len" -> { if (args.isNotEmpty()) err("len expects no arguments", n); lenVec(v, n) }
                "len2" -> { if (args.isNotEmpty()) err("len2 expects no arguments", n); val l = lenVec(v, n); l * l }
                "dist" -> lenVec(subVec(v, argVec("dist"), n), n)
                "angleTo" -> {
                    val b = argVec("angleTo")
                    val la = lenVec(v, n); val lb = lenVec(b, n)
                    if (la == 0.0 || lb == 0.0) PI / 2 else acos((dot(v, b, n) as Double / (la * lb)).coerceIn(-1.0, 1.0))
                }
                "project" -> {
                    val b = argVec("project")
                    val bb = dot(b, b, n) as Double
                    if (bb == 0.0) err("project onto zero-length vector", n)
                    scaleVec(b, (dot(v, b, n) as Double) / bb, n)
                }
                "reflect" -> {
                    val nn = argVec("reflect")
                    val d = (dot(v, nn, n) as Double) * 2
                    subVec(v, scaleVec(nn, d, n), n)
                }
                "lerp" -> {
                    if (args.size != 2) err("lerp expects 2 arguments", n)
                    lerp(v, args[0], num(args[1], "lerp t", n), n)
                }
                "rotateX" -> { if (args.size != 1) err("rotateX expects 1 argument", n); rotateVec3X(v, num(args[0], "rotateX angle", n), n) }
                "rotateY" -> { if (args.size != 1) err("rotateY expects 1 argument", n); rotateVec3Y(v, num(args[0], "rotateY angle", n), n) }
                "rotateZ" -> { if (args.size != 1) err("rotateZ expects 1 argument", n); rotateVec3Z(v, num(args[0], "rotateZ angle", n), n) }
                "translate" -> {
                    val dim = vecDim(v!!)
                    if (args.size != dim) err("translate expects $dim argument(s), got ${args.size}", n)
                    val c = args.mapIndexed { i, x -> num(x, "translate[$i]", n) }
                    mkVec(dim, vecComps(v).mapIndexed { i, x -> x + c[i] })
                }
                "scale" -> {
                    if (args.size != 1) err("scale expects 1 argument", n)
                    val dim = vecDim(v!!)
                    val s = args[0]
                    when (s) {
                        is Double -> mkVec(dim, vecComps(v).map { it * s })
                        is Vec2, is Vec3, is Vec4 -> {
                            if (vecDim(s) != dim) err("scale requires a scalar or same-dimension vec", n)
                            val cs = vecComps(s)
                            mkVec(dim, vecComps(v).mapIndexed { i, x -> x * cs[i] })
                        }
                        else -> err("scale requires a scalar or vec", n)
                    }
                }
                else -> err("vec has no method '.$method()'", n)
            }
        }

        private fun colorMethod(c: ColorVal, method: String, args: List<Any?>, n: Node): Any? {
            fun channel(read: (ColorVal) -> Double, write: (ColorVal, Double) -> ColorVal): Any? {
                if (args.isEmpty()) return read(c)
                if (args.size == 1) return write(c, num(args[0], method, n))
                err("$method expects 0 or 1 argument(s), got ${args.size}", n)
            }
            return when (method) {
                "toRGB" -> {
                    if (args.isNotEmpty()) err("toRGB takes no arguments", n)
                    ObjVal(LinkedHashMap<String, Any?>().also { m ->
                        m["r"] = c.r; m["g"] = c.g; m["b"] = c.b; m["a"] = c.a
                    })
                }
                "toHSV" -> {
                    if (args.isNotEmpty()) err("toHSV takes no arguments", n)
                    val h = rgbToHsv(c, n)
                    ObjVal(LinkedHashMap<String, Any?>().also { m ->
                        m["h"] = h.x; m["s"] = h.y; m["v"] = h.z
                    })
                }
                "red" -> channel({ it.r }, { x, v -> x.copy(r = v.coerceIn(0.0, 1.0)) })
                "green" -> channel({ it.g }, { x, v -> x.copy(g = v.coerceIn(0.0, 1.0)) })
                "blue" -> channel({ it.b }, { x, v -> x.copy(b = v.coerceIn(0.0, 1.0)) })
                "alpha" -> channel({ it.a }, { x, v -> x.copy(a = v.coerceIn(0.0, 1.0)) })
                "hue" -> channel({ rgbToHsv(it, n).x }, { x, v -> hsvToRgb(listOf(v, rgbToHsv(x, n).y, rgbToHsv(x, n).z), n) })
                "saturation" -> channel({ rgbToHsv(it, n).y }, { x, v -> hsvToRgb(listOf(rgbToHsv(x, n).x, v.coerceIn(0.0, 1.0), rgbToHsv(x, n).z), n) })
                "value" -> channel({ rgbToHsv(it, n).z }, { x, v -> hsvToRgb(listOf(rgbToHsv(x, n).x, rgbToHsv(x, n).y, v.coerceIn(0.0, 1.0)), n) })
                "shift_hue" -> {
                    if (args.size != 1) err("shift_hue expects 1 argument", n)
                    val h = rgbToHsv(c, n)
                    hsvToRgb(listOf(h.x + num(args[0], "shift_hue", n), h.y, h.z), n)
                }
                else -> err("color has no method '.$method()'", n)
            }
        }

        private fun rotateVec3X(v: Any?, a: Double, n: Node): Vec3 {
            val p = v as? Vec3 ?: err("rotateX requires a vec3", n)
            val c = cos(a); val s = sin(a)
            return Vec3(p.x, p.y * c - p.z * s, p.y * s + p.z * c)
        }
        private fun rotateVec3Y(v: Any?, a: Double, n: Node): Vec3 {
            val p = v as? Vec3 ?: err("rotateY requires a vec3", n)
            val c = cos(a); val s = sin(a)
            return Vec3(p.x * c + p.z * s, p.y, -p.x * s + p.z * c)
        }
        private fun rotateVec3Z(v: Any?, a: Double, n: Node): Vec3 {
            val p = v as? Vec3 ?: err("rotateZ requires a vec3", n)
            val c = cos(a); val s = sin(a)
            return Vec3(p.x * c - p.y * s, p.x * s + p.y * c, p.z)
        }

        private fun hash32(seed: Int, salt: Int): Double {
            var x = (seed xor salt) + 0x9e3779b9
            x = (x xor (x ushr 16)) * 0x85ebca6b
            x = (x xor (x ushr 13)) * 0xc2b2ae35
            x = x xor (x ushr 16)
            return (x and 0x7fffffff).toDouble() / 2147483648.0
        }

        private fun phasesObj(t: Any?, obj: Any?, n: Node): ObjVal {
            val fields = LinkedHashMap<String, Any?>()
            val src = obj as? ObjVal ?: err("phases requires an object", n)
            val tv = num(t, "phases", n)
            for ((k, v) in src.fields) {
                val pair = v as? MutableList<*> ?: err("phases value for '$k' must be [a,b]", n)
                if (pair.size != 2) err("phases value for '$k' must be [a,b]", n)
                val a = num(pair[0], "phases", n)
                val b = num(pair[1], "phases", n)
                val tt = ((tv - a) / (b - a)).coerceIn(0.0, 1.0)
                fields[k] = tt * tt * (3 - 2 * tt)
            }
            return ObjVal(fields)
        }

        private fun repeatFn(count: Any?, fn: Any?, n: Node): Any? {
            val c = jsTrunc(num(count, "repeat", n)).toInt()
            if (c <= 0) return 0.0
            for (i in 0 until c) {
                if (i >= MAX_REPEAT_ITERATIONS) err("loop iteration limit ($MAX_REPEAT_ITERATIONS) exceeded", n)
                guardLoop(n)
                when (fn) {
                    is LambdaVal -> callLambda(fn, listOf(i.toDouble()), n)
                    is FuncVal -> callUserFunc(program.functions[fn.name] ?: err("function '${fn.name}' not found", n), listOf(i.toDouble()), n)
                    else -> err("repeat requires a function, got ${typeName(fn)}", n)
                }
            }
            return 0.0
        }

        private fun colorWith(c: Any?, n: Node): ColorVal = when (c) {
            is ColorVal -> c
            is Vec3 -> ColorVal(clamp01(c.x), clamp01(c.y), clamp01(c.z), 1.0)
            is Vec4 -> ColorVal(clamp01(c.x), clamp01(c.y), clamp01(c.z), clamp01(c.w))
            else -> err("expected a color or vec4, got ${typeName(c)}", n)
        }

        private fun rgbToHsv(c: Any?, n: Node): Vec3 {
            val col = colorWith(c, n)
            val mx = max(col.r, max(col.g, col.b))
            val mn = min(col.r, min(col.g, col.b))
            val d = mx - mn
            val h = when {
                d == 0.0 -> 0.0
                mx == col.r -> ((col.g - col.b) / d).let { if (it < 0) it + 6.0 else it } / 6.0
                mx == col.g -> ((col.b - col.r) / d + 2.0) / 6.0
                else -> ((col.r - col.g) / d + 4.0) / 6.0
            }
            val s = if (mx == 0.0) 0.0 else d / mx
            return Vec3(h, s, mx)
        }

        private fun hsvToRgb(args: List<Any?>, n: Node): ColorVal {
            val h: Double; val s: Double; val v: Double
            when (val a0 = args[0]) {
                is Vec3 -> { h = a0.x; s = a0.y; v = a0.z }
                else -> { h = num(a0, "hsv2rgb", n); s = num(args[1], "hsv2rgb", n); v = num(args[2], "hsv2rgb", n) }
            }
            val sc = s.coerceIn(0.0, 1.0)
            val vc = v.coerceIn(0.0, 1.0)
            val hh = ((h % 1.0) + 1.0) % 1.0 * 6.0
            val i = floor(hh).toInt()
            val f = hh - i
            val p = vc * (1 - sc)
            val q = vc * (1 - f * sc)
            val t = vc * (1 - (1 - f) * sc)
            return when (i % 6) {
                0 -> ColorVal(vc, t, p, 1.0)
                1 -> ColorVal(q, vc, p, 1.0)
                2 -> ColorVal(p, vc, t, 1.0)
                3 -> ColorVal(p, q, vc, 1.0)
                4 -> ColorVal(t, p, vc, 1.0)
                else -> ColorVal(vc, p, q, 1.0)
            }
        }

        private fun colorHueSet(c: Any?, h: Double, n: Node): ColorVal {
            val hsv = rgbToHsv(c, n)
            return hsvToRgb(listOf(h, hsv.y, hsv.z), n)
        }
        private fun colorSatSet(c: Any?, s: Double, n: Node): ColorVal {
            val hsv = rgbToHsv(c, n)
            return hsvToRgb(listOf(hsv.x, s.coerceIn(0.0, 1.0), hsv.z), n)
        }
        private fun colorValSet(c: Any?, v: Double, n: Node): ColorVal {
            val hsv = rgbToHsv(c, n)
            return hsvToRgb(listOf(hsv.x, hsv.y, v.coerceIn(0.0, 1.0)), n)
        }

        private fun callBuiltin(name: String, args: List<Any?>, n: Node): Any? {
            if (name !in BUILTINS) {
                if (program.functions.containsKey(name)) return callUserFunc(program.functions[name]!!, args, n)
                err("unknown function '$name'", n)
            }
            // 快速标量数学：仅 process 且 fx.fastMath 开启时替换（类型校验与精确路径一致）。
            if (phase == "process" && ctx?.fastMath == true) {
                val fast = ScriptFastMath.FAST_MATH[name]
                if (fast != null) {
                    for (a in args) num(a, name, n)
                    return fast(args.map { it as Double })
                }
            }
            val seed = objState.seed
            return when (name) {
                "print" -> {
                    val line = args.joinToString(" ") { formatValue(it) }
                    ctx?.print?.invoke(line)
                    0.0
                }
                "assert" -> { if (!truthy(args[0], n)) throw ScriptException(args.getOrElse(1) { "" }.toString()); 0.0 }
                "vec2" -> Vec2(num(args[0], "vec2", n), num(args[1], "vec2", n))
                "vec3", "vec" -> Vec3(num(args[0], "vec3", n), num(args[1], "vec3", n), num(args[2], "vec3", n))
                "vec4" -> Vec4(num(args[0], "vec4", n), num(args[1], "vec4", n), num(args[2], "vec4", n), num(args[3], "vec4", n))
                "mat3" -> {
                    val r0 = args[0] as? Vec3 ?: err("mat3 rows must be vec3", n)
                    val r1 = args[1] as? Vec3 ?: err("mat3 rows must be vec3", n)
                    val r2 = args[2] as? Vec3 ?: err("mat3 rows must be vec3", n)
                    Mat3(listOf(listOf(r0.x, r0.y, r0.z), listOf(r1.x, r1.y, r1.z), listOf(r2.x, r2.y, r2.z)))
                }
                "mat4" -> {
                    val rows = ArrayList<List<Double>>(4)
                    for (i in 0 until 4) {
                        val r = args[i] as? Vec4 ?: err("mat4 rows must be vec4", n)
                        rows.add(listOf(r.x, r.y, r.z, r.w))
                    }
                    Mat4(rows)
                }
                "norm" -> {
                    val a = int(args[0], "norm", n)
                    val b = int(args[1], "norm", n)
                    if (a < 0 || b < 0) err("norm requires non-negative integers", n)
                    a.toDouble() / max(b - 1, 1).toDouble()
                }
                "hash" -> hash32(int32(args[0], "hash seed", n), int32(args[1], "hash salt", n))
                "phases" -> phasesObj(args[0], args[1], n)
                "repeat" -> repeatFn(args[0], args[1], n)
                "color" -> ColorVal(num(args[0], "color", n), num(args[1], "color", n), num(args[2], "color", n), num(args[3], "color", n))
                "clamp" -> clamp(args[0], args[1], args[2], n)
                "map_range", "remap" -> mapRange(args[0], args[1], args[2], args[3], args[4], name == "remap", n)
                "int" -> intConvert(args[0], n)
                "float" -> floatConvert(args[0], n)
                "bool" -> { val v = args[0]; if (isUndefined(v)) return false; if (v !is Double && v !is Boolean) err("bool requires a scalar", n); if (v is Boolean) v else v != 0.0 }
                "sin" -> sin(num(args[0], "sin", n)); "cos" -> cos(num(args[0], "cos", n)); "tan" -> tan(num(args[0], "tan", n))
                "asin" -> asin(num(args[0], "asin", n)); "acos" -> acos(num(args[0], "acos", n)); "atan" -> atan(num(args[0], "atan", n))
                "atan2" -> atan2(num(args[0], "atan2", n), num(args[1], "atan2", n))
                "sqrt" -> sqrt(num(args[0], "sqrt", n)); "abs" -> abs(num(args[0], "abs", n)); "sign" -> sign(num(args[0], "sign", n))
                "exp" -> exp(num(args[0], "exp", n)); "log", "ln" -> ln(num(args[0], "log", n))
                "floor" -> floor(num(args[0], "floor", n)); "ceil" -> ceil(num(args[0], "ceil", n)); "round" -> jsRound(num(args[0], "round", n))
                "fract" -> { val x = num(args[0], "fract", n); x - floor(x) }
                "pow" -> num(args[0], "pow", n).pow(num(args[1], "pow", n))
                "min" -> min(num(args[0], "min", n), num(args[1], "min", n))
                "max" -> max(num(args[0], "max", n), num(args[1], "max", n))
                "step" -> { val e = num(args[0], "step", n); val x = num(args[1], "step", n); if (x >= e) 1.0 else 0.0 }
                "smoothstep" -> { val e0 = num(args[0], "smoothstep", n); val e1 = num(args[1], "smoothstep", n); val x = num(args[2], "smoothstep", n); val t = ((x - e0) / (e1 - e0)).coerceIn(0.0, 1.0); t * t * (3 - 2 * t) }
                "mod" -> { val x = num(args[0], "mod", n); val y = num(args[1], "mod", n); if (y == 0.0) err("mod by zero", n); x - y * floor(x / y) }
                "noise" -> noise3D(num(args[0], "noise", n), num(args[1], "noise", n), num(args[2], "noise", n), if (args.size > 3) toInt32(num(args[3], "noise seed", n)) else seed)
                "fbm" -> fbm(num(args[0], "fbm", n), num(args[1], "fbm", n), num(args[2], "fbm", n), jsTrunc(num(args[3], "fbm octaves", n)).toInt(), if (args.size > 4) toInt32(num(args[4], "fbm seed", n)) else seed)
                "rand" -> if (args.isEmpty()) objState.rand.next() else RandState(toInt32(num(args[0], "rand seed", n))).next()
                "random" -> kotlin.random.Random.nextDouble()
                "ease_linear" -> { val a = num(args[0], "ease", n); val b = num(args[1], "ease", n); val t = num(args[2], "ease", n); a + (b - a) * t }
                "ease_in_out" -> { val a = num(args[0], "ease", n); val b = num(args[1], "ease", n); val t = num(args[2], "ease", n).coerceIn(0.0, 1.0); a + (b - a) * t * t * (3 - 2 * t) }
                "ease_out_back" -> { val a = num(args[0], "ease", n); val b = num(args[1], "ease", n); val t = num(args[2], "ease", n).coerceIn(0.0, 1.0); val c1 = 1.70158; val c3 = c1 + 1; a + (b - a) * (1 + c3 * (t - 1).pow(3) + c1 * (t - 1).pow(2)) }
                "ease_in_elastic" -> { val a = num(args[0], "ease", n); val b = num(args[1], "ease", n); val t = num(args[2], "ease", n).coerceIn(0.0, 1.0); val v = if (t == 0.0 || t == 1.0) t else -2.0.pow(10 * (t - 1)) * sin((t * 10 - 10.75) * (2 * PI) / 3); a + (b - a) * v }
                "unique" -> arrayMethod(args[0] as? MutableList<Any?> ?: err("unique requires array", n), "unique", emptyList(), n)
                "reverse" -> arrayMethod(args[0] as? MutableList<Any?> ?: err("reverse requires array", n), "reverse", emptyList(), n)
                "sort" -> arrayMethod(args[0] as? MutableList<Any?> ?: err("sort requires array", n), "sort", args.drop(1), n)
                else -> err("unknown builtin '$name'", n)
            }
        }

        private fun scaleTriple(args: List<Any?>, n: Node): Triple<Double, Double, Double> {
            if (args.size == 1) {
                return when (val a = args[0]) {
                    is Double -> Triple(a, a, a)
                    is Vec2 -> Triple(a.x, a.y, 1.0)
                    is Vec3 -> Triple(a.x, a.y, a.z)
                    else -> err("scale requires num or vec", n)
                }
            }
            if (args.size == 3) return Triple(num(args[0], "scale", n), num(args[1], "scale", n), num(args[2], "scale", n))
            err("scale expects 1 or 3 args", n)
        }

        private fun rotAxisMat4(axis: Any?, angle: Double, n: Node): Mat4 {
            val v = axis as? Vec3 ?: err("rotate requires a vec3 axis", n)
            val l = lenVec(v, n)
            if (l == 0.0) err("zero-length rotation axis", n)
            val x = v.x / l; val y = v.y / l; val z = v.z / l
            val c = cos(angle); val s = sin(angle); val C = 1 - c
            return Mat4(listOf(
                listOf(c + x * x * C, x * y * C - z * s, x * z * C + y * s, 0.0),
                listOf(y * x * C + z * s, c + y * y * C, y * z * C - x * s, 0.0),
                listOf(z * x * C - y * s, z * y * C + x * s, c + z * z * C, 0.0),
                listOf(0.0, 0.0, 0.0, 1.0),
            ))
        }

        private fun rotAxisMat3(axis: Any?, angle: Double, n: Node): Mat3 {
            val v = axis as? Vec3 ?: err("rotAxis requires a vec3 axis", n)
            val l = lenVec(v, n)
            if (l == 0.0) err("zero-length rotation axis", n)
            val x = v.x / l; val y = v.y / l; val z = v.z / l
            val c = cos(angle); val s = sin(angle); val C = 1 - c
            return Mat3(listOf(
                listOf(c + x * x * C, x * y * C - z * s, x * z * C + y * s),
                listOf(y * x * C + z * s, c + y * y * C, y * z * C - x * s),
                listOf(z * x * C - y * s, z * y * C + x * s, c + z * z * C),
            ))
        }

        private fun rotXMat3(t: Double) = Mat3(listOf(listOf(1.0, 0.0, 0.0), listOf(0.0, cos(t), -sin(t)), listOf(0.0, sin(t), cos(t))))
        private fun rotYMat3(t: Double) = Mat3(listOf(listOf(cos(t), 0.0, sin(t)), listOf(0.0, 1.0, 0.0), listOf(-sin(t), 0.0, cos(t))))
        private fun rotZMat3(t: Double) = Mat3(listOf(listOf(cos(t), -sin(t), 0.0), listOf(sin(t), cos(t), 0.0), listOf(0.0, 0.0, 1.0)))

        private fun lookAt(eye: Any?, target: Any?, up: Any?, n: Node): Mat4 {
            val e = eye as? Vec3 ?: err("lookAt eye must be vec3", n)
            val t = target as? Vec3 ?: err("lookAt target must be vec3", n)
            val u = up as? Vec3 ?: err("lookAt up must be vec3", n)
            val f = norm3(Vec3(t.x - e.x, t.y - e.y, t.z - e.z))
            val s = norm3(Vec3(f.y * u.z - f.z * u.y, f.z * u.x - f.x * u.z, f.x * u.y - f.y * u.x))
            val uu = Vec3(s.y * f.z - s.z * f.y, s.z * f.x - s.x * f.z, s.x * f.y - s.y * f.x)
            return Mat4(listOf(
                listOf(s.x, s.y, s.z, -(s.x * e.x + s.y * e.y + s.z * e.z)),
                listOf(uu.x, uu.y, uu.z, -(uu.x * e.x + uu.y * e.y + uu.z * e.z)),
                listOf(-f.x, -f.y, -f.z, (f.x * e.x + f.y * e.y + f.z * e.z)),
                listOf(0.0, 0.0, 0.0, 1.0),
            ))
        }

        private fun norm3(v: Vec3): Vec3 { val l = sqrt(v.x * v.x + v.y * v.y + v.z * v.z); return if (l == 0.0) v else Vec3(v.x / l, v.y / l, v.z / l) }

        private fun dot(a: Any?, b: Any?, n: Node): Double = when {
            a is Vec2 && b is Vec2 -> a.x * b.x + a.y * b.y
            a is Vec3 && b is Vec3 -> a.x * b.x + a.y * b.y + a.z * b.z
            a is Vec4 && b is Vec4 -> a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w
            else -> err("dot requires same-dimension vectors", n)
        }

        private fun lenVec(v: Any?, n: Node): Double = when (v) {
            is Vec2 -> sqrt(v.x * v.x + v.y * v.y)
            is Vec3 -> sqrt(v.x * v.x + v.y * v.y + v.z * v.z)
            is Vec4 -> sqrt(v.x * v.x + v.y * v.y + v.z * v.z + v.w * v.w)
            else -> err("len requires a vector", n)
        }

        private fun scaleVec(v: Any?, s: Double, n: Node): Any? = when (v) {
            is Vec2 -> Vec2(v.x * s, v.y * s)
            is Vec3 -> Vec3(v.x * s, v.y * s, v.z * s)
            is Vec4 -> Vec4(v.x * s, v.y * s, v.z * s, v.w * s)
            else -> err("scaleVec requires a vector", n)
        }

        private fun subVec(a: Any?, b: Any?, n: Node): Any? = when {
            a is Vec2 && b is Vec2 -> Vec2(a.x - b.x, a.y - b.y)
            a is Vec3 && b is Vec3 -> Vec3(a.x - b.x, a.y - b.y, a.z - b.z)
            a is Vec4 && b is Vec4 -> Vec4(a.x - b.x, a.y - b.y, a.z - b.z, a.w - b.w)
            else -> err("subtraction requires vectors", n)
        }

        private fun lerp(a: Any?, b: Any?, t: Double, n: Node): Any? = when {
            a is Double && b is Double -> a + (b - a) * t
            a is Vec2 && b is Vec2 -> Vec2(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
            a is Vec3 && b is Vec3 -> Vec3(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t)
            a is Vec4 && b is Vec4 -> Vec4(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t, a.w + (b.w - a.w) * t)
            else -> err("lerp requires two nums or two vectors", n)
        }

        private fun clamp(v: Any?, lo: Any?, hi: Any?, n: Node): Any? {
            if (v is Double) return clampNum(v, num(lo, "clamp lo", n), num(hi, "clamp hi", n))
            if (isVec(v)) {
                val dim = vecDim(v!!)
                val comps = vecComps(v)
                fun bound(x: Any?, i: Int, label: String): Double = if (isVec(x)) {
                    if (vecDim(x!!) != dim) err("clamp bound dimension mismatch", n)
                    vecComps(x)[i]
                } else num(x, label, n)
                return mkVec(dim, comps.mapIndexed { i, x -> clampNum(x, bound(lo, i, "clamp lo"), bound(hi, i, "clamp hi")) })
            }
            return err("clamp not supported for ${typeName(v)}", n)
        }

        private fun mapRange(v: Any?, in1: Any?, in2: Any?, out1: Any?, out2: Any?, clampOut: Boolean, n: Node): Any? {
            val x = num(v, "map_range", n); val a = num(in1, "map_range", n); val b = num(in2, "map_range", n); val c = num(out1, "map_range", n); val d = num(out2, "map_range", n)
            if (b == a) err(if (clampOut) "remap input range is empty" else "map_range input range is empty", n)
            val t = (x - a) / (b - a)
            val r = c + (d - c) * t
            return if (clampOut) r.coerceIn(min(c, d), max(c, d)) else r
        }

        private fun intConvert(v: Any?, n: Node): Any? = when (v) {
            is Double -> jsTrunc(v)
            is Vec2 -> Vec2(jsTrunc(v.x), jsTrunc(v.y))
            is Vec3 -> Vec3(jsTrunc(v.x), jsTrunc(v.y), jsTrunc(v.z))
            is Vec4 -> Vec4(jsTrunc(v.x), jsTrunc(v.y), jsTrunc(v.z), jsTrunc(v.w))
            is Mat3 -> Mat3(v.m.map { r -> r.map { jsTrunc(it) } })
            is Mat4 -> Mat4(v.m.map { r -> r.map { jsTrunc(it) } })
            else -> err("int requires scalar, vector or matrix", n)
        }

        private fun floatConvert(v: Any?, n: Node): Any? = when (v) {
            is Double -> v
            is Vec2 -> Vec2(v.x, v.y)
            is Vec3 -> Vec3(v.x, v.y, v.z)
            is Vec4 -> Vec4(v.x, v.y, v.z, v.w)
            is Mat3 -> Mat3(v.m.map { r -> r.map { it } })
            is Mat4 -> Mat4(v.m.map { r -> r.map { it } })
            else -> err("float requires scalar, vector or matrix", n)
        }
    }

    /** JS String(value) 的近似：脚本值格式化（print 用）。 */
    private fun formatValue(v: Any?, depth: Int = 0): String {
        if (depth > MAX_VALUE_DEPTH) throw ScriptException("value nesting too deep")
        return when (v) {
            null -> "null"
            is Undefined -> "undefined"
            is Double -> if (v % 1.0 == 0.0 && v.isFinite()) v.toLong().toString() else v.toString()
            is Boolean -> v.toString()
            is String -> v
            is Vec2 -> "vec2(${v.x}, ${v.y})"
            is Vec3 -> "vec3(${v.x}, ${v.y}, ${v.z})"
            is Vec4 -> "vec4(${v.x}, ${v.y}, ${v.z}, ${v.w})"
            is Mat3 -> "mat3(${v.m.joinToString(", ") { row -> "[${row.joinToString(", ")}]" }})"
            is Mat4 -> "mat4(${v.m.joinToString(", ") { row -> "[${row.joinToString(", ")}]" }})"
            is MutableList<*> -> "[" + v.joinToString(", ") { formatValue(it, depth + 1) } + "]"
            is FuncVal -> "func ${v.name}"
            is LambdaVal -> "lambda(${v.params.joinToString(", ")})"
            is ColorVal -> "color(${v.r}, ${v.g}, ${v.b}, ${v.a})"
            is ObjVal -> "{${v.fields.entries.joinToString(", ") { (k, x) -> "$k: ${formatValue(x, depth + 1)}" }}}"
            is ParticleValue -> "particle#${v.host.index}"
            is ParticleListValue -> "particleList(${v.size})"
            is TextValue -> "text(${v.obj.name})"
            is TextCharValue -> "char(${v.ch.index})"
            is AudioValue -> "audio(${v.asset.name})"
            else -> v.toString()
        }
    }

    private val CONSTANTS = mapOf(
        "TAU" to 2 * PI,
        "HALF_PI" to PI / 2,
        "QUARTER_PI" to PI / 4,
        "DEG2RAD" to PI / 180,
        "RAD2DEG" to 180 / PI,
        "PI" to PI,
        "E" to E,
    )
}

/** 供 parser 校验保留名使用。 */
object BuiltinRegistry {
    val names: Set<String> = setOf(
        "print", "assert",
        "vec2", "vec3", "vec4", "vec", "mat3", "mat4",
        "norm",
        "clamp", "map_range", "remap", "int", "float", "bool",
        "sin", "cos", "tan", "asin", "acos", "atan", "atan2", "sqrt", "abs", "sign", "exp", "log", "ln",
        "floor", "ceil", "round", "fract", "pow", "min", "max", "step", "smoothstep", "mod",
        "noise", "fbm", "rand", "random",
        "ease_linear", "ease_in_out", "ease_out_back", "ease_in_elastic",
        "hash", "phases", "repeat",
        "color",
        "unique", "reverse", "sort",
    )
}