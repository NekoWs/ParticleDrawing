package work.nekow.particledrawing.animation.script

import kotlin.math.PI
import kotlin.math.E
import kotlin.math.roundToInt

/**
 * script-lang `process` 的纯标量直线代码快路径。
 *
 * 与 Web 端 `compileNativeProcess` 同理：仅当 process 只由「标量赋值/拆包」组成、
 * 且表达式不含向量/矩阵/分量访问/数组索引/三元/比较/用户函数/rand/random 时启用。
 * 产物复用动画表达式模块的 [ScalarProgram]（DoubleArray 寄存器 + DoubleArray 栈），
 * 消除 AST 解释器的 HashMap 作用域查找与 Any 装箱；不支持时返回 null，调用方回退 AST Runtime。
 */
class ScriptScalarProgram internal constructor(
    private val scalar: ScalarProgram,
    private val regCount: Int,
    private val stackSize: Int,
    private val varNames: Array<String>,
    private val varSlots: IntArray,
    private val objectNames: Array<String>,
    private val dtSlot: Int,
    private val uvXSlot: Int,
    private val uvYSlot: Int,
    private val lifeSlot: Int,
) {
    private val objects = arrayOfNulls<Any?>(objectNames.size)

    fun allocRegs() = DoubleArray(regCount)
    fun allocStack() = DoubleArray(stackSize)

    fun eval(objState: ScriptRuntime.ObjectState, ctx: ScriptRuntime.ProcessCtx, out: ScriptRuntime.ScriptOut, regs: DoubleArray, stack: DoubleArray) {
        regs[Reg.I] = ctx.i
        regs[Reg.N] = ctx.n
        regs[Reg.T] = ctx.t
        regs[dtSlot] = ctx.dt
        regs[uvXSlot] = ctx.uv_x
        regs[uvYSlot] = ctx.uv_y
        regs[lifeSlot] = ctx.life
        val vars = ctx.vars
        for (k in varNames.indices) regs[varSlots[k]] = vars[varNames[k]] ?: 0.0
        val globals = objState.globals
        for (k in objectNames.indices) objects[k] = globals[objectNames[k]]
        System.arraycopy(ATTR_INIT, 0, regs, Reg.X, ATTR_INIT.size)
        scalar.exec(regs, stack, null, objects)

        out.pos[0] = regs[Reg.X]
        out.pos[1] = regs[Reg.Y]
        out.pos[2] = regs[Reg.Z]
        out.color[0] = clamp01(regs[Reg.R])
        out.color[1] = clamp01(regs[Reg.G])
        out.color[2] = clamp01(regs[Reg.B])
        out.color[3] = clamp01(regs[Reg.A])
        out.vel[0] = regs[Reg.VX]
        out.vel[1] = regs[Reg.VY]
        out.vel[2] = regs[Reg.VZ]
        out.scale = regs[Reg.SC]
        out.glow = regs[Reg.GLOW] > 0.5
        out.light = regs[Reg.LIGHT].coerceIn(0.0, 15.0).roundToInt().toDouble()
    }

    companion object {
        private val ATTR_INIT = doubleArrayOf(
            0.0, 0.0, 0.0, 1.0, 1.0, 1.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0,
        )

        private val CONSTANTS: Map<String, Double> = mapOf(
            "TAU" to 2 * PI,
            "HALF_PI" to PI / 2,
            "QUARTER_PI" to PI / 4,
            "DEG2RAD" to PI / 180,
            "RAD2DEG" to 180 / PI,
            "pi" to PI,
            "e" to E,
        )

        private const val CTX_NAME = "Context"

        // 分量别名：r/g/b → x/y/z，a → w。
        private val COMP_ALIAS = mapOf("x" to "x", "y" to "y", "z" to "z", "w" to "w", "r" to "x", "g" to "y", "b" to "z", "a" to "w")

        private val FUNC_OPS: Map<String, ScalarOp> = mapOf(
            "sin" to ScalarOp.F_SIN, "cos" to ScalarOp.F_COS, "tan" to ScalarOp.F_TAN,
            "asin" to ScalarOp.F_ASIN, "acos" to ScalarOp.F_ACOS, "atan" to ScalarOp.F_ATAN,
            "atan2" to ScalarOp.F_ATAN2, "sqrt" to ScalarOp.F_SQRT, "abs" to ScalarOp.F_ABS,
            "sign" to ScalarOp.F_SIGN, "exp" to ScalarOp.F_EXP, "log" to ScalarOp.F_LOG,
            "ln" to ScalarOp.F_LOG, "floor" to ScalarOp.F_FLOOR, "ceil" to ScalarOp.F_CEIL,
            "round" to ScalarOp.F_ROUND, "fract" to ScalarOp.F_FRACT, "pow" to ScalarOp.F_POW,
            "min" to ScalarOp.F_MIN, "max" to ScalarOp.F_MAX, "clamp" to ScalarOp.F_CLAMP,
            "lerp" to ScalarOp.F_LERP, "mix" to ScalarOp.F_LERP, "step" to ScalarOp.F_STEP,
            "smoothstep" to ScalarOp.F_SMOOTHSTEP, "mod" to ScalarOp.F_MOD,
        )

        private val CHAR_OPS: Map<Char, ScalarOp> = mapOf(
            '+' to ScalarOp.ADD, '-' to ScalarOp.SUB, '*' to ScalarOp.MUL,
            '/' to ScalarOp.DIV, '%' to ScalarOp.REM, '^' to ScalarOp.POW,
        )

        fun compile(program: ScriptProgram, varNames: List<String>, globalNames: List<String> = emptyList()): ScriptScalarProgram? = try {
            val stmts = program.process
            val ops = ArrayList<ScalarOp>()
            val args = ArrayList<Int>()
            val consts = ArrayList<Double>()
            val constIndex = HashMap<Double, Int>()
            val tempSlots = HashMap<String, Int>()
            val varSlots = HashMap<String, Int>()
            val arraySlots = HashMap<String, Int>()
            val objectNames = ArrayList<String>()
            val globalNamesSet = globalNames.toHashSet()

            fun constIdx(v: Double): Int {
                constIndex[v]?.let { return it }
                val idx = consts.size
                consts.add(v)
                constIndex[v] = idx
                return idx
            }

            // 槽位布局：Reg.I/N/T 固定；dt/uv/life 放在 Reg.VAR_START 之后，变量与临时量紧随。
            val base = Reg.VAR_START
            val dtSlot = base
            val uvXSlot = base + 1
            val uvYSlot = base + 2
            val lifeSlot = base + 3
            var nextSlot = base + 4

            // 先登记 fx.vars 槽位（readSlot 需要它们）。
            val orderedVarNames = ArrayList<String>()
            for (name in varNames) {
                if (varSlots.containsKey(name)) continue
                varSlots[name] = nextSlot++
                orderedVarNames.add(name)
            }

            fun ensureTemp(name: String): Int {
                tempSlots[name]?.let { return it }
                val slot = nextSlot++
                tempSlots[name] = slot
                return slot
            }

            fun readSlot(name: String): Int? {
                varSlots[name]?.let { return it }
                tempSlots[name]?.let { return it }
                return null
            }

            fun writeSlot(name: String): Int? {
                tempSlots[name]?.let { return it }
                if (name == CTX_NAME || varSlots.containsKey(name) || CONSTANTS.containsKey(name)) return null
                return ensureTemp(name)
            }

            fun ensureArraySlot(name: String): Int {
                arraySlots[name]?.let { return it }
                val slot = objectNames.size
                arraySlots[name] = slot
                objectNames.add(name)
                return slot
            }

            fun emitPush(op: ScalarOp, arg: Int) {
                ops.add(op)
                args.add(arg)
            }

            fun emit(op: ScalarOp) {
                ops.add(op)
                args.add(0)
            }

            // Context 标量字段 → 寄存器槽。
            fun ctxFieldReadSlot(field: String): Int? = when (field) {
                "index" -> Reg.I
                "count" -> Reg.N
                "time" -> Reg.T
                "delta" -> dtSlot
                "life" -> lifeSlot
                "scale" -> Reg.SC
                "glow" -> Reg.GLOW
                "light" -> Reg.LIGHT
                else -> null // uv / position / color / velocity 为向量
            }

            fun ctxFieldWriteSlot(field: String): Int? = when (field) {
                "scale" -> Reg.SC
                "glow" -> Reg.GLOW
                "light" -> Reg.LIGHT
                else -> null
            }

            fun ctxCompSlot(field: String, comp: String): Int? {
                val c = COMP_ALIAS[comp] ?: return null
                return when (field) {
                    "uv" -> when (c) { "x" -> uvXSlot; "y" -> uvYSlot; else -> null }
                    "position" -> when (c) { "x" -> Reg.X; "y" -> Reg.Y; "z" -> Reg.Z; else -> null }
                    "velocity" -> when (c) { "x" -> Reg.VX; "y" -> Reg.VY; "z" -> Reg.VZ; else -> null }
                    "color" -> when (c) { "x" -> Reg.R; "y" -> Reg.G; "z" -> Reg.B; "w" -> Reg.A; else -> null }
                    else -> null
                }
            }

            fun compileExpr(node: Node): Boolean {
                when (node) {
                    is NumNode -> { emitPush(ScalarOp.PUSH_CONST, constIdx(node.value)); return true }
                    is VarNode -> {
                        CONSTANTS[node.name]?.let { emitPush(ScalarOp.PUSH_CONST, constIdx(it)); return true }
                        val slot = readSlot(node.name) ?: return false
                        emitPush(ScalarOp.PUSH_REG, slot)
                        return true
                    }
                    is MemberNode -> {
                        if (node.obj !is VarNode || node.obj.name != CTX_NAME) return false
                        val slot = ctxFieldReadSlot(node.field) ?: return false
                        emitPush(ScalarOp.PUSH_REG, slot)
                        return true
                    }
                    is CompNode -> {
                        // 仅支持 Context.<向量字段>.<分量> 的标量读取。
                        val m = node.target as? MemberNode ?: return false
                        if (m.obj !is VarNode || m.obj.name != CTX_NAME) return false
                        val slot = ctxCompSlot(m.field, node.comp) ?: return false
                        emitPush(ScalarOp.PUSH_REG, slot)
                        return true
                    }
                    is UnaryNode -> {
                        if (node.op != "-") return false
                        if (!compileExpr(node.operand)) return false
                        emit(ScalarOp.NEG)
                        return true
                    }
                    is BinaryNode -> {
                        val ch = node.op.firstOrNull() ?: return false
                        val op = CHAR_OPS[ch] ?: return false
                        if (!compileExpr(node.left) || !compileExpr(node.right)) return false
                        emit(op)
                        return true
                    }
                    is CallNode -> {
                        if (node.callee !is VarNode) return false
                        val fn = FUNC_OPS[node.callee.name] ?: return false
                        for (a in node.args) if (!compileExpr(a)) return false
                        emit(fn)
                        return true
                    }
                    is IndexNode -> {
                        // 仅支持「全局数组名 + 标量下标」读取（preset 常见 _gx[Context.index]）。
                        val target = node.target
                        if (target !is VarNode || target.name !in globalNamesSet) return false
                        if (!compileExpr(node.index)) return false
                        emitPush(ScalarOp.READ_ARR, ensureArraySlot(target.name))
                        return true
                    }
                    else -> return false
                }
            }

            for (st in stmts) {
                if (st !is AssignNode) return null
                val value = st.value
                when (val target = st.target) {
                    is VarTarget -> {
                        if (!compileExpr(value)) return null
                        val slot = writeSlot(target.name) ?: return null
                        emitPush(ScalarOp.POP_REG, slot)
                    }
                    is MemberTarget -> {
                        if (target.obj !is VarNode || target.obj.name != CTX_NAME) return null
                        val field = target.field
                        if (field == "position" || field == "velocity") {
                            if (value !is ArrayNode || value.items.size != 3) return null
                            val outSlots = if (field == "position") intArrayOf(Reg.X, Reg.Y, Reg.Z) else intArrayOf(Reg.VX, Reg.VY, Reg.VZ)
                            for (k in 0..2) {
                                if (!compileExpr(value.items[k])) return null
                                emitPush(ScalarOp.POP_REG, outSlots[k])
                            }
                        } else if (field == "color") {
                            if (value !is ArrayNode || (value.items.size != 3 && value.items.size != 4)) return null
                            val outSlots = intArrayOf(Reg.R, Reg.G, Reg.B, Reg.A)
                            for (k in value.items.indices) {
                                if (!compileExpr(value.items[k])) return null
                                emitPush(ScalarOp.POP_REG, outSlots[k])
                            }
                        } else if (field == "scale" || field == "glow" || field == "light") {
                            if (!compileExpr(value)) return null
                            val slot = ctxFieldWriteSlot(field) ?: return null
                            emitPush(ScalarOp.POP_REG, slot)
                        } else {
                            return null // index/count/time/delta/uv/life 只读
                        }
                    }
                    is CompTarget -> {
                        val m = target.target as? MemberNode ?: return null
                        if (m.obj !is VarNode || m.obj.name != CTX_NAME) return null
                        val slot = ctxCompSlot(m.field, target.comp) ?: return null
                        if (!compileExpr(value)) return null
                        emitPush(ScalarOp.POP_REG, slot)
                    }
                    is UnpackTarget -> {
                        if (value !is ArrayNode || value.items.size != target.names.size) return null
                        val tmp = IntArray(target.names.size)
                        for (k in target.names.indices) {
                            if (!compileExpr(value.items[k])) return null
                            val slot = ensureTemp("__unpack$k")
                            tmp[k] = slot
                            emitPush(ScalarOp.POP_REG, slot)
                        }
                        for (k in target.names.indices) {
                            val slot = writeSlot(target.names[k]) ?: return null
                            emitPush(ScalarOp.PUSH_REG, tmp[k])
                            emitPush(ScalarOp.POP_REG, slot)
                        }
                    }
                    else -> return null
                }
            }

            val opsArr = ops.toTypedArray()
            val argsArr = args.toIntArray()
            val constsArr = consts.toDoubleArray()
            val varNamesArr = orderedVarNames.toTypedArray()
            val varSlotsArr = IntArray(orderedVarNames.size) { varSlots[orderedVarNames[it]]!! }
            ScriptScalarProgram(
                ScalarProgram(opsArr, argsArr, constsArr),
                nextSlot,
                computeStackSize(opsArr, argsArr),
                varNamesArr,
                varSlotsArr,
                objectNames.toTypedArray(),
                dtSlot,
                uvXSlot,
                uvYSlot,
                lifeSlot,
            )
        } catch (e: Exception) {
            null
        }

        private fun computeStackSize(ops: Array<ScalarOp>, args: IntArray): Int {
            var depth = 0
            var max = 0
            for (op in ops) {
                when (op) {
                    ScalarOp.PUSH_CONST, ScalarOp.PUSH_REG -> depth++
                    ScalarOp.POP_REG -> depth--
                    ScalarOp.F_RANDOM -> depth++
                    else -> {
                        val pops = op.pops
                        if (pops > 0) depth = depth - pops + 1
                    }
                }
                if (depth < 0) depth = 0
                if (depth > max) max = depth
            }
            return maxOf(1, max)
        }
    }
}