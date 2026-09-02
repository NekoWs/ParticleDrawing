package work.nekow.particledrawing.animation.script

import kotlin.math.*

// this 对象名（与 ScriptParser 一致）。
private const val CTX_NAME = "this"

// this 输出字段（process 内可读可写）。
private val CTX_OUT_FIELDS = setOf("position", "color", "velocity", "scale", "glow", "light", "life")

// 向量分量别名：r/g/b → x/y/z，a → w。
private val COMP_ALIAS = mapOf("x" to "x", "y" to "y", "z" to "z", "w" to "w", "r" to "x", "g" to "y", "b" to "z", "a" to "w")

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

/**
 * setup/process 脚本解释器（对应编辑器 script-lang.js）。
 *
 * 值类型见 ScriptValues；PRNG/Simplex 见 ScriptNoise。
 */
object ScriptRuntime {

    const val TICKS_PER_SEC = 20

    class ObjectState(
        val globals: MutableMap<String, Any?>,
        val rand: () -> Double,
        val seed: Int,
    )

    class ScriptOut(
        val pos: DoubleArray = DoubleArray(3),
        val color: DoubleArray = doubleArrayOf(1.0, 1.0, 1.0, 1.0),
        val vel: DoubleArray = DoubleArray(3),
        var scale: Double = 1.0,
        var glow: Boolean = false,
        var light: Double = 0.0,
        var life: Double = -1.0,
    )

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

    class SetupEnv(val n: Double, val t: Double, val vars: Map<String, Double>, val duration: Double = 0.0)

    private class Flow(val kind: String, val value: Any? = null) : Throwable()

    private val BUILTINS = setOf(
        "print", "assert",
        "vec2", "vec3", "vec4", "vec", "mat3", "translate", "scale", "rotate", "lookAt", "rotX", "rotY", "rotZ", "rotAxis",
        "dot", "cross", "len", "len2", "norm", "lerp", "mix", "distance", "angle_between", "project", "reflect",
        "clamp", "map_range", "remap", "int", "float", "bool",
        "sin", "cos", "tan", "asin", "acos", "atan", "atan2", "sqrt", "abs", "sign", "exp", "log", "ln",
        "floor", "ceil", "round", "fract", "pow", "min", "max", "step", "smoothstep", "mod",
        "noise", "fbm", "rand", "random",
        "ease_linear", "ease_in_out", "ease_out_back", "ease_in_elastic",
        "unique", "reverse", "sort", "len",
    )

    fun createObjectState(seed: Int): ObjectState = ObjectState(HashMap(), mulberry32(seed), seed)

    fun createStatics(): MutableMap<String, Any?> = HashMap()

    fun runSetup(program: ScriptProgram, obj: ObjectState, env: SetupEnv) {
        val rt = Runtime("setup", program, obj, null, env, null)
        rt.pushScope(HashMap())
        try {
            for (st in program.setup) rt.execStmt(st)
        } finally {
            rt.popScope()
        }
    }

    fun evalProcess(program: ScriptProgram, obj: ObjectState, statics: MutableMap<String, Any?>, ctx: ProcessCtx): ScriptOut {
        if (!ctx.fastMath) {
            val fast = scalarFast(program, obj, ctx.vars.keys.toList())
            if (fast != null) {
                fast.eval(obj, ctx, ctx.out, fast.allocRegs(), fast.allocStack())
                return ctx.out
            }
        }
        ctx.resetOut()
        val rt = Runtime("process", program, obj, statics, null, ctx)
        rt.pushScope(HashMap())
        try {
            for (st in program.process) rt.execStmt(st)
        } finally {
            rt.popScope()
        }
        return ctx.out
    }

    // 标量快路径缓存：按 program + varNames + globalNames 签名复用编译产物。
    private val scalarFastCache = HashMap<ScriptProgram, HashMap<String, ScriptScalarProgram?>>()

    private fun scalarFast(program: ScriptProgram, obj: ObjectState, varNames: List<String>): ScriptScalarProgram? {
        val globalNames = obj.globals.keys.sorted()
        val key = varNames.joinToString("\u0000") + "|" + globalNames.joinToString("\u0000")
        return scalarFastCache.getOrPut(program) { HashMap() }.getOrPut(key) {
            ScriptScalarProgram.compile(program, varNames, globalNames)
        }
    }

    /**
     * 可复用的 process 执行器：同一函数对象在同一 tick 内逐粒子复用，避免每个粒子都
     * 新建 Runtime / ArrayList / HashMap（20w 粒子场景下这是主要分配来源）。
     * 标量直线脚本优先走 [ScriptScalarProgram]，寄存器/栈也随执行器复用。
     */
    class ProcessExecutor(program: ScriptProgram, obj: ObjectState) {
        private val rt = Runtime("process", program, obj, null, null, null)
        private val topScope = HashMap<String, Any?>()
        private var fast: ScriptScalarProgram? = null
        private var fastRegs: DoubleArray? = null
        private var fastStack: DoubleArray? = null
        private var fastVarKey: String? = null

        fun eval(statics: MutableMap<String, Any?>, ctx: ProcessCtx): ScriptOut {
            val varKey = ctx.vars.keys.joinToString("\u0000")
            if (fastVarKey != varKey) {
                fast = scalarFast(rt.program, rt.objState, ctx.vars.keys.toList())
                fastRegs = fast?.allocRegs()
                fastStack = fast?.allocStack()
                fastVarKey = varKey
            }
            val f = fast
            if (f != null && !ctx.fastMath) {
                f.eval(rt.objState, ctx, ctx.out, fastRegs!!, fastStack!!)
                return ctx.out
            }
            ctx.resetOut()
            rt.resetProcess(statics, ctx, topScope)
            try {
                for (st in rt.program.process) rt.execStmt(st)
            } finally {
                rt.popScope()
            }
            return ctx.out
        }
    }

    fun createProcessExecutor(program: ScriptProgram, obj: ObjectState): ProcessExecutor =
        ProcessExecutor(program, obj)

    /* ---------------------------------------------------------------- */

    private class Runtime(
        val phase: String,
        val program: ScriptProgram,
        val objState: ObjectState,
        private var statics: MutableMap<String, Any?>?,
        private val setupEnv: SetupEnv?,
        private var ctx: ProcessCtx?,
    ) {
        private val scopes = ArrayList<MutableMap<String, Any?>>()
        private var loopDepth = 0
        private var funcDepth = 0
        private var inFunction = false

        fun pushScope(s: MutableMap<String, Any?>) { scopes.add(s) }
        fun popScope() { scopes.removeAt(scopes.size - 1) }
        fun currentScope(): MutableMap<String, Any?> = scopes[scopes.size - 1]

        /** 复用执行器：清空作用域/调用深度，并切换到下一个粒子的 statics/ctx。 */
        fun resetProcess(statics: MutableMap<String, Any?>?, ctx: ProcessCtx?, topScope: MutableMap<String, Any?>) {
            this.statics = statics
            this.ctx = ctx
            scopes.clear()
            loopDepth = 0
            funcDepth = 0
            inFunction = false
            topScope.clear()
            scopes.add(topScope)
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

        private fun truthy(v: Any?, n: Node): Boolean = when (v) {
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
                        if (n.init != null) execStmt(n.init)
                        var iter = 0
                        while (n.cond == null || truthy(evalExpr(n.cond), n.cond)) {
                            if (++iter > 100000) err("maximum loop iterations (100000) exceeded", n)
                            try { execStmt(n.body) }
                            catch (f: Flow) {
                                if (f.kind == "break") break
                                if (f.kind == "continue") { /* 落到 inc */ }
                                else throw f
                            }
                            if (n.inc != null) execStmt(n.inc)
                        }
                    } finally { popScope() }
                }
                is BreakNode -> throw Flow("break")
                is ContinueNode -> throw Flow("continue")
                is ReturnNode -> {
                    if (!inFunction) err("return is only allowed inside a function", n)
                    throw Flow("return", if (n.expr != null) evalExpr(n.expr) else 0.0)
                }
                is GlobalNode -> {
                    if (phase != "setup" || inFunction) err("'global' is only allowed in setup", n)
                    val v = if (n.init != null) evalExpr(n.init) else 0.0
                    objState.globals[n.name] = v
                }
                is StaticNode -> {
                    if (phase != "process" || inFunction) err("'static' is only allowed in process", n)
                    val m = statics ?: err("static state unavailable", n)
                    if (!m.containsKey(n.name)) m[n.name] = if (n.init != null) evalExpr(n.init) else 0.0
                }
                is AssignNode -> execAssign(n.target, evalExpr(n.value), n)
                is ExprStmtNode -> evalExpr(n.expr)
                else -> err("unknown statement ${n::class.simpleName}", n)
            }
        }

        private fun execAssign(target: AssignTarget, value: Any?, n: Node) {
            when (target) {
                is VarTarget -> assignName(target.name, value, n)
                is MemberTarget -> assignCtxField(target, value, n)
                is IndexTarget -> {
                    val arr = evalExpr(target.target)
                    if (arr !is MutableList<*>) err("indexed assignment target is not an array", n)
                    val idx = int(evalExpr(target.index), "array index", n)
                    if (idx < 0 || idx >= arr.size) err("array index $idx out of bounds (size ${arr.size})", n)
                    (arr as MutableList<Any?>)[idx] = value
                }
                is CompTarget -> {
                    val obj = evalExpr(target.target)
                    if (!isVec(obj)) err("component assignment target is not a vector", n)
                    val comp = COMP_ALIAS[target.comp] ?: err("unknown component '${target.comp}'", n)
                    if (!hasComp(obj, comp)) err("${typeName(obj)} has no component '${target.comp}'", n)
                    val d = num(value, "component value", n)
                    val updated = setVecComp(obj, comp, d)
                    assignCompTarget(target.target, updated, n)
                }
                is UnpackTarget -> {
                    val comps = when (value) {
                        is Vec2 -> listOf(value.x, value.y)
                        is Vec3 -> listOf(value.x, value.y, value.z)
                        is Vec4 -> listOf(value.x, value.y, value.z, value.w)
                        is MutableList<*> -> value.toList()
                        else -> err("unpack requires a vector or array, got ${typeName(value)}", n)
                    }
                    if (comps.size != target.names.size) err("unpack count mismatch", n)
                    for ((i, name) in target.names.withIndex()) assignName(name, comps[i], n)
                }
            }
        }

        /** 分量赋值：把更新后的向量写回其目标（变量 / this 字段 / 数组下标）。 */
        private fun assignCompTarget(target: Node, updated: Any, n: Node) {
            when (target) {
                is VarNode -> assignName(target.name, updated, n)
                is MemberNode -> assignCtxField(MemberTarget(target.obj, target.field, target.line, target.col), updated, n)
                is IndexNode -> {
                    val arr = evalExpr(target.target)
                    if (arr !is MutableList<*>) err("indexed assignment target is not an array", n)
                    val idx = int(evalExpr(target.index), "array index", n)
                    if (idx < 0 || idx >= arr.size) err("array index $idx out of bounds (size ${arr.size})", n)
                    (arr as MutableList<Any?>)[idx] = updated
                }
                else -> err("component assignment requires a variable, this field or array index target", n)
            }
        }

        private fun assignCtxField(target: MemberTarget, value: Any?, n: Node) {
            if (target.obj !is VarNode || target.obj.name != CTX_NAME) {
                err("only this has fields '.${target.field}'", n)
            }
            ctxWrite(target.field, value, n)
        }

        private fun assignName(name: String, value: Any?, n: Node) {
            if (name == CTX_NAME) {
                err("cannot assign to 'this'; use this.<field> = ...", n)
            }
            for (i in scopes.indices.reversed()) {
                val s = scopes[i]
                if (s.containsKey(name)) { s[name] = value; return }
            }
            if (objState.globals.containsKey(name)) {
                if (phase == "setup" && !inFunction) { objState.globals[name] = value; return }
                err("global '$name' is read-only here", n)
            }
            val st = statics
            if (phase == "process" && st != null && st.containsKey(name)) { st[name] = value; return }
            val c = ctx
            if (name in CONSTANTS || (c != null && c.vars.containsKey(name))) {
                err("cannot assign to read-only name '$name'", n)
            }
            currentScope()[name] = value
        }

        // ---- this 字段读写（§8/§9）----

        private fun ctxRead(field: String, n: Node): Any? {
            if (phase == "setup") {
                if (field == "count") return setupEnv?.n ?: 0.0
                if (field == "time") return setupEnv?.t ?: 0.0
                if (field == "duration") return setupEnv?.duration ?: 0.0
                err("this.$field is not available in setup", n)
            }
            // process
            val c = ctx
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

        private fun ctxVecValues(value: Any?, len: Int, field: String, n: Node): List<Double> {
            if (isVec(value)) {
                val v = value ?: err("this.$field requires a vec$len, got null", n)
                if (vecDim(v) != len) {
                    err("this.$field requires a vec$len, got ${typeName(v)}", n)
                }
                return vecComps(v)
            }
            if (value is MutableList<*>) {
                if (value.size != len) {
                    err("this.$field requires an array of $len numbers, got length ${value.size}", n)
                }
                return value.map { num(it, "this.$field[$it]", n) }
            }
            err("this.$field requires a vec$len or array of $len numbers, got ${typeName(value)}", n)
        }

        private fun ctxWrite(field: String, value: Any?, n: Node) {
            if (phase != "process") err("this.$field is read-only here", n)
            if (field !in CTX_OUT_FIELDS) err("this.$field is read-only", n)
            val out = ctx?.out ?: err("output unavailable", n)
            when (field) {
                "position" -> {
                    val c = ctxVecValues(value, 3, "position", n)
                    out.pos[0] = c[0]; out.pos[1] = c[1]; out.pos[2] = c[2]
                }
                "velocity" -> {
                    val c = ctxVecValues(value, 3, "velocity", n)
                    out.vel[0] = c[0]; out.vel[1] = c[1]; out.vel[2] = c[2]
                }
                "color" -> {
                    when {
                        value is Vec3 -> {
                            out.color[0] = clamp01(value.x); out.color[1] = clamp01(value.y); out.color[2] = clamp01(value.z)
                        }
                        value is Vec4 -> {
                            out.color[0] = clamp01(value.x); out.color[1] = clamp01(value.y); out.color[2] = clamp01(value.z); out.color[3] = clamp01(value.w)
                        }
                        value is MutableList<*> && value.size == 3 -> {
                            out.color[0] = clamp01(num(value[0], "this.color[0]", n))
                            out.color[1] = clamp01(num(value[1], "this.color[1]", n))
                            out.color[2] = clamp01(num(value[2], "this.color[2]", n))
                        }
                        value is MutableList<*> && value.size == 4 -> {
                            out.color[0] = clamp01(num(value[0], "this.color[0]", n))
                            out.color[1] = clamp01(num(value[1], "this.color[1]", n))
                            out.color[2] = clamp01(num(value[2], "this.color[2]", n))
                            out.color[3] = clamp01(num(value[3], "this.color[3]", n))
                        }
                        else -> err("this.color requires a vec3, vec4, [r,g,b] or [r,g,b,a], got ${typeName(value)}", n)
                    }
                }
                "scale" -> out.scale = num(value, "this.scale", n)
                "glow" -> {
                    if (!isNum(value) && !isBool(value)) {
                        err("this.glow requires a num/bool, got ${typeName(value)}", n)
                    }
                    val nv = if (value is Boolean) (if (value) 1.0 else 0.0) else value as Double
                    out.glow = nv > 0.5
                }
                "light" -> out.light = clampNum(jsRound(num(value, "this.light", n)), 0.0, 15.0)
                "life" -> {
                    val v = jsRound(num(value, "this.life", n))
                    out.life = if (v.isFinite()) (if (v < 0.0) -1.0 else v) else -1.0
                }
            }
        }

        fun evalExpr(n: Node): Any? = when (n) {
            is NumNode -> n.value
            is StrNode -> n.value
            is BoolNode -> n.value
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
            is BinaryNode -> evalBinary(n.op, evalExpr(n.left), evalExpr(n.right), n)
            is TernaryNode -> if (truthy(evalExpr(n.cond), n.cond)) evalExpr(n.thenExpr) else evalExpr(n.elseExpr)
            is IndexNode -> {
                val arr = evalExpr(n.target)
                if (arr !is MutableList<*>) err("indexing requires an array, got ${typeName(arr)}", n)
                val idx = int(evalExpr(n.index), "array index", n)
                if (idx < 0 || idx >= arr.size) err("array index $idx out of bounds (size ${arr.size})", n)
                arr[idx]
            }
            is CompNode -> {
                val v = evalExpr(n.target)
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
            is MemberNode -> {
                if (n.obj !is VarNode || n.obj.name != CTX_NAME) {
                    err("only this has fields '.${n.field}'", n)
                }
                ctxRead(n.field, n)
            }
            is CallNode -> {
                val name = (n.callee as? VarNode)?.name ?: err("callee must be a name", n)
                callBuiltin(name, n.args.map { evalExpr(it) }, n)
            }
            is MethodNode -> {
                val obj = evalExpr(n.obj)
                if (obj !is MutableList<*>) err("method '.${n.method}()' requires an array, got ${typeName(obj)}", n)
                arrayMethod(obj as MutableList<Any?>, n.method, n.args.map { evalExpr(it) }, n)
            }
            else -> err("unknown expression ${n::class.simpleName}", n)
        }

        private fun lookupName(name: String, n: Node): Any? {
            if (name == CTX_NAME) {
                err("'this' is not a value; use this.<field>", n)
            }
            for (i in scopes.indices.reversed()) {
                val s = scopes[i]
                if (s.containsKey(name)) return s[name]
            }
            if (objState.globals.containsKey(name)) return objState.globals[name]
            val st = statics
            if (phase == "process" && st != null && st.containsKey(name)) return st[name]
            if (phase == "setup") {
                if (setupEnv?.vars?.containsKey(name) == true) return setupEnv.vars[name]
            } else {
                val c = ctx
                if (c?.vars?.containsKey(name) == true) return c.vars[name]
            }
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

        private fun evalBinary(op: String, a: Any?, b: Any?, n: Node): Any? = when (op) {
            "&&" -> truthy(a, n) && truthy(b, n)
            "||" -> truthy(a, n) || truthy(b, n)
            "==" -> eqExact(a, b)
            "!=" -> !eqExact(a, b)
            "<", "<=", ">", ">=" -> cmp(a, b, op, n)
            "+", "-", "*", "/", "%", "^" -> arith(op, a, b, n)
            else -> err("unknown binary operator '$op'", n)
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
            val va = a
            val vb = b
            val dim = when { isVec(va) -> vecDim(va!!); isVec(vb) -> vecDim(vb!!); else -> 0 }
            fun coords(v: Any?): List<Double> = when (v) {
                is Double -> List(dim) { v }
                else -> vecComps(v!!)
            }
            val ca = if (isVec(va)) vecComps(va!!) else coords(va)
            val cb = if (isVec(vb)) vecComps(vb!!) else coords(vb)
            val out = when (op) {
                "+" -> ca.zip(cb).map { it.first + it.second }
                "-" -> ca.zip(cb).map { it.first - it.second }
                "*" -> ca.zip(cb).map { it.first * it.second }
                "/" -> if (isNum(b)) ca.map { it / (b as Double) } else err("vector division only supports scalar", n)
                else -> err("unknown vector op '$op'", n)
            }
            return mkVec(dim, out)
        }

        private fun matArith(op: String, a: Any?, b: Any?, n: Node): Any? {
            // 仅支持 mat * vec / mat * mat / mat * scalar / scalar * mat / mat + mat / mat - mat。
            if (op == "*") {
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
            err("unsupported matrix operation '$op'", n)
        }

        private fun matMul(a: List<List<Double>>, b: List<List<Double>>): List<List<Double>> {
            val n = a.size
            return List(n) { i -> List(n) { j -> (0 until n).sumOf { k -> a[i][k] * b[k][j] } } }
        }

        private fun eqExact(a: Any?, b: Any?): Boolean = when {
            a is Double && b is Double -> a == b
            a is Boolean && b is Boolean -> a == b
            a is Vec2 && b is Vec2 -> a == b
            a is Vec3 && b is Vec3 -> a == b
            a is Vec4 && b is Vec4 -> a == b
            a is Mat3 && b is Mat3 -> a.m == b.m
            a is Mat4 && b is Mat4 -> a.m == b.m
            a is MutableList<*> && b is MutableList<*> -> a.size == b.size && a.withIndex().all { (i, v) -> eqExact(v, b[i]) }
            else -> false
        }

        private fun arrayMethod(arr: MutableList<Any?>, method: String, args: List<Any?>, n: Node): Any? = when (method) {
            "push" -> { arr.add(args[0]); arr }
            "insert" -> { val idx = int(args[0], "insert index", n); if (idx < 0 || idx > arr.size) err("insert index $idx out of bounds", n); arr.add(idx, args[1]); arr }
            "remove" -> { val idx = int(args[0], "remove index", n); if (idx < 0 || idx >= arr.size) err("remove index $idx out of bounds", n); arr.removeAt(idx); arr }
            "slice" -> {
                val size = arr.size
                fun normIdx(x: Int): Int = if (x < 0) (size + x).coerceAtLeast(0) else x.coerceAtMost(size)
                val s = normIdx(int(args[0], "slice start", n))
                val e = if (args.size > 1) normIdx(int(args[1], "slice end", n)) else size
                val from = s.coerceAtMost(e)
                arr.subList(from, e).toMutableList()
            }
            "size" -> arr.size.toDouble()
            "find" -> {
                val v = args[0]
                arr.indexOfFirst { eqTol(it, v) }.toDouble()
            }
            "includes" -> arr.any { eqTol(it, args[0]) }
            "sort" -> {
                if (args.isEmpty()) arr.sortWith { x, y -> defaultCompare(x, y, n) }
                else {
                    val cmpName = (args[0] as? FuncVal)?.name ?: err("sort comparator must be a function name", n)
                    arr.sortWith { x, y -> comparatorResult(callUserFunc(program.functions[cmpName] ?: err("function '$cmpName' not found", n), listOf(x, y), n)) }
                }
                arr
            }
            "unique" -> {
                val out = ArrayList<Any?>()
                for (v in arr) if (out.none { eqTol(it, v) }) out.add(v)
                out
            }
            "reverse" -> { arr.reverse(); arr }
            else -> err("unknown array method '$method'", n)
        }

        private fun eqTol(a: Any?, b: Any?): Boolean = when {
            a is Double && b is Double -> abs(a - b) <= 1e-6
            a is Boolean && b is Boolean -> a == b
            a is Vec2 && b is Vec2 -> abs(a.x - b.x) <= 1e-6 && abs(a.y - b.y) <= 1e-6
            a is Vec3 && b is Vec3 -> abs(a.x - b.x) <= 1e-6 && abs(a.y - b.y) <= 1e-6 && abs(a.z - b.z) <= 1e-6
            a is Vec4 && b is Vec4 -> abs(a.x - b.x) <= 1e-6 && abs(a.y - b.y) <= 1e-6 && abs(a.z - b.z) <= 1e-6 && abs(a.w - b.w) <= 1e-6
            a is MutableList<*> && b is MutableList<*> -> a.size == b.size && a.withIndex().all { (i, v) -> eqTol(v, b[i]) }
            else -> false
        }

        private fun defaultCompare(a: Any?, b: Any?, n: Node): Int {
            val ta = typeName(a); val tb = typeName(b)
            if (ta != tb) err("cannot sort mixed types ($ta vs $tb)", n)
            return when (a) {
                is Double -> (a as Double).compareTo(b as Double)
                is Boolean -> a.compareTo(b as Boolean)
                is Vec2 -> compareValuesBy(a, b as Vec2, { it.x }, { it.y })
                is Vec3 -> compareValuesBy(a, b as Vec3, { it.x }, { it.y }, { it.z })
                is Vec4 -> compareValuesBy(a, b as Vec4, { it.x }, { it.y }, { it.z }, { it.w })
                else -> err("values of type $ta are not sortable", n)
            }
        }

        private fun comparatorResult(v: Any?): Int = when (v) {
            is Double -> v.toInt()
            else -> throw ScriptException("comparator must return a num")
        }

        private fun callUserFunc(fn: FunctionNode, args: List<Any?>, n: Node): Any? {
            if (funcDepth >= 64) err("maximum recursion depth (64) exceeded", n)
            funcDepth++
            val prev = inFunction
            inFunction = true
            pushScope(HashMap())
            for ((i, p) in fn.params.withIndex()) currentScope()[p] = if (i < args.size) args[i] else 0.0
            var result: Any? = 0.0
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
                "print" -> { if (phase != "setup") err("'print' is only allowed in setup", n); 0.0 }
                "assert" -> { if (phase != "setup") err("'assert' is only allowed in setup", n); if (!truthy(args[0], n)) throw ScriptException(args[1].toString()); 0.0 }
                "vec2" -> Vec2(num(args[0], "vec2", n), num(args[1], "vec2", n))
                "vec3", "vec" -> Vec3(num(args[0], "vec3", n), num(args[1], "vec3", n), num(args[2], "vec3", n))
                "vec4" -> Vec4(num(args[0], "vec4", n), num(args[1], "vec4", n), num(args[2], "vec4", n), num(args[3], "vec4", n))
                "mat3" -> {
                    val r0 = args[0] as? Vec3 ?: err("mat3 rows must be vec3", n)
                    val r1 = args[1] as? Vec3 ?: err("mat3 rows must be vec3", n)
                    val r2 = args[2] as? Vec3 ?: err("mat3 rows must be vec3", n)
                    Mat3(listOf(listOf(r0.x, r0.y, r0.z), listOf(r1.x, r1.y, r1.z), listOf(r2.x, r2.y, r2.z)))
                }
                "translate" -> {
                    val v = args[0] as? Vec3 ?: err("translate requires a vec3", n)
                    Mat4(listOf(listOf(1.0, 0.0, 0.0, v.x), listOf(0.0, 1.0, 0.0, v.y), listOf(0.0, 0.0, 1.0, v.z), listOf(0.0, 0.0, 0.0, 1.0)))
                }
                "scale" -> {
                    val (sx, sy, sz) = scaleTriple(args, n)
                    Mat4(listOf(listOf(sx, 0.0, 0.0, 0.0), listOf(0.0, sy, 0.0, 0.0), listOf(0.0, 0.0, sz, 0.0), listOf(0.0, 0.0, 0.0, 1.0)))
                }
                "rotate" -> rotAxisMat4(args[0], num(args[1], "rotate angle", n), n)
                "lookAt" -> lookAt(args[0], args[1], args[2], n)
                "rotX" -> rotXMat3(num(args[0], "rotX", n))
                "rotY" -> rotYMat3(num(args[0], "rotY", n))
                "rotZ" -> rotZMat3(num(args[0], "rotZ", n))
                "rotAxis" -> rotAxisMat3(args[0], num(args[1], "rotAxis", n), n)
                "dot" -> dot(args[0], args[1], n)
                "cross" -> { val a = args[0] as? Vec3 ?: err("cross requires vec3", n); val b = args[1] as? Vec3 ?: err("cross requires vec3", n); Vec3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x) }
                "len" -> { val v = args[0]; if (v is Double) abs(v) else lenVec(v, n) }
                "len2" -> { val v = args[0]; if (v is Double) v * v else lenVec(v, n).let { it * it } }
                "norm" -> { val v = args[0]; val l = lenVec(v, n); if (l == 0.0) v else scaleVec(v, 1.0 / l, n) }
                "lerp", "mix" -> lerp(args[0], args[1], num(args[2], "$name t", n), n)
                "distance" -> { val a = args[0]; val b = args[1]; lenVec(subVec(a, b, n), n) }
                "angle_between" -> { val a = args[0]; val b = args[1]; val la = lenVec(a, n); val lb = lenVec(b, n); if (la == 0.0 || lb == 0.0) PI / 2 else acos((dot(a, b, n) as Double / (la * lb)).coerceIn(-1.0, 1.0)) }
                "project" -> { val a = args[0]; val b = args[1]; val bb = dot(b, b, n) as Double; if (bb == 0.0) err("project onto zero-length vector", n); scaleVec(b, (dot(a, b, n) as Double) / bb, n) }
                "reflect" -> { val v = args[0]; val nn = args[1]; val d = (dot(v, nn, n) as Double) * 2; subVec(v, scaleVec(nn, d, n), n) }
                "clamp" -> clamp(args[0], args[1], args[2], n)
                "map_range", "remap" -> mapRange(args[0], args[1], args[2], args[3], args[4], name == "remap", n)
                "int" -> intConvert(args[0], n)
                "float" -> floatConvert(args[0], n)
                "bool" -> { val v = args[0]; if (v !is Double) err("bool requires a scalar", n); v != 0.0 }
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
                "mod" -> { val x = num(args[0], "mod", n); val y = num(args[1], "mod", n); x - y * floor(x / y) }
                "noise" -> noise3D(num(args[0], "noise", n), num(args[1], "noise", n), num(args[2], "noise", n), if (args.size > 3) int(args[3], "noise seed", n) else seed)
                "fbm" -> fbm(num(args[0], "fbm", n), num(args[1], "fbm", n), num(args[2], "fbm", n), int(args[3], "fbm octaves", n), if (args.size > 4) int(args[4], "fbm seed", n) else seed)
                "rand" -> if (args.isEmpty()) objState.rand() else mulberry32(int(args[0], "rand seed", n))()
                "random" -> kotlin.random.Random.nextDouble()
                "ease_linear" -> { val a = num(args[0], "ease", n); val b = num(args[1], "ease", n); val t = num(args[2], "ease", n); a + (b - a) * t }
                "ease_in_out" -> { val a = num(args[0], "ease", n); val b = num(args[1], "ease", n); val t = num(args[2], "ease", n).coerceIn(0.0, 1.0); a + (b - a) * t * t * (3 - 2 * t) }
                "ease_out_back" -> { val a = num(args[0], "ease", n); val b = num(args[1], "ease", n); val t = num(args[2], "ease", n).coerceIn(0.0, 1.0); val c1 = 1.70158; val c3 = c1 + 1; a + (b - a) * (1 + c3 * (t - 1).pow(3) + c1 * (t - 1).pow(2)) }
                "ease_in_elastic" -> { val a = num(args[0], "ease", n); val b = num(args[1], "ease", n); val t = num(args[2], "ease", n).coerceIn(0.0, 1.0); val v = if (t == 0.0 || t == 1.0) t else -2.0.pow(10 * (t - 1)) * sin((t * 10 - 10.75) * (2 * PI) / 3); a + (b - a) * v }
                "unique" -> arrayMethod(args[0] as? MutableList<Any?> ?: err("unique requires array", n), "unique", emptyList(), n)
                "reverse" -> arrayMethod(args[0] as? MutableList<Any?> ?: err("reverse requires array", n), "reverse", emptyList(), n)
                "sort" -> arrayMethod(args[0] as? MutableList<Any?> ?: err("sort requires array", n), "sort", args.drop(1), n)
                "len" -> { val v = args[0]; if (v is MutableList<*>) v.size.toDouble() else lenVec(v, n) }
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

        private fun clamp(v: Any?, lo: Any?, hi: Any?, n: Node): Any? = when (v) {
            is Double -> clampNum(v, num(lo, "clamp lo", n), num(hi, "clamp hi", n))
            is Vec2 -> Vec2(clampNum(v.x, num(lo, "clamp lo", n), num(hi, "clamp hi", n)), clampNum(v.y, num(lo, "clamp lo", n), num(hi, "clamp hi", n)))
            is Vec3 -> Vec3(clampNum(v.x, num(lo, "clamp lo", n), num(hi, "clamp hi", n)), clampNum(v.y, num(lo, "clamp lo", n), num(hi, "clamp hi", n)), clampNum(v.z, num(lo, "clamp lo", n), num(hi, "clamp hi", n)))
            is Vec4 -> Vec4(clampNum(v.x, num(lo, "clamp lo", n), num(hi, "clamp hi", n)), clampNum(v.y, num(lo, "clamp lo", n), num(hi, "clamp hi", n)), clampNum(v.z, num(lo, "clamp lo", n), num(hi, "clamp hi", n)), clampNum(v.w, num(lo, "clamp lo", n), num(hi, "clamp hi", n)))
            else -> err("clamp requires a scalar or vector", n)
        }

        private fun mapRange(v: Any?, in1: Any?, in2: Any?, out1: Any?, out2: Any?, clampOut: Boolean, n: Node): Any? {
            val x = num(v, "map_range", n); val a = num(in1, "map_range", n); val b = num(in2, "map_range", n); val c = num(out1, "map_range", n); val d = num(out2, "map_range", n)
            val t = if (b == a) 0.0 else (x - a) / (b - a)
            val r = c + (d - c) * t
            return if (clampOut) r.coerceIn(min(c, d), max(c, d)) else r
        }

        private fun intConvert(v: Any?, n: Node): Any? = when (v) {
            is Double -> jsTrunc(v)
            is Vec2 -> Vec2(jsTrunc(v.x), jsTrunc(v.y))
            is Vec3 -> Vec3(jsTrunc(v.x), jsTrunc(v.y), jsTrunc(v.z))
            is Vec4 -> Vec4(jsTrunc(v.x), jsTrunc(v.y), jsTrunc(v.z), jsTrunc(v.w))
            else -> err("int requires scalar or vector", n)
        }

        private fun floatConvert(v: Any?, n: Node): Any? = when (v) {
            is Double -> v
            is Vec2 -> Vec2(v.x, v.y)
            is Vec3 -> Vec3(v.x, v.y, v.z)
            is Vec4 -> Vec4(v.x, v.y, v.z, v.w)
            else -> err("float requires scalar or vector", n)
        }
    }

    private val CONSTANTS = mapOf(
        "TAU" to 2 * PI,
        "HALF_PI" to PI / 2,
        "QUARTER_PI" to PI / 4,
        "DEG2RAD" to PI / 180,
        "RAD2DEG" to 180 / PI,
        "pi" to PI,
        "e" to E,
    )
}

/** 供 parser 校验保留名使用。 */
object BuiltinRegistry {
    val names: Set<String> = setOf(
        "print", "assert",
        "vec2", "vec3", "vec4", "vec", "mat3", "translate", "scale", "rotate", "lookAt", "rotX", "rotY", "rotZ", "rotAxis",
        "dot", "cross", "len", "len2", "norm", "lerp", "mix", "distance", "angle_between", "project", "reflect",
        "clamp", "map_range", "remap", "int", "float", "bool",
        "sin", "cos", "tan", "asin", "acos", "atan", "atan2", "sqrt", "abs", "sign", "exp", "log", "ln",
        "floor", "ceil", "round", "fract", "pow", "min", "max", "step", "smoothstep", "mod",
        "noise", "fbm", "rand", "random",
        "ease_linear", "ease_in_out", "ease_out_back", "ease_in_elastic",
        "unique", "reverse", "sort",
    )
}