package work.nekow.particledrawing.animation.expr

import kotlin.math.*

/**
 * 纯标量代码块的扁平字节码编译与执行。
 *
 * 网页编辑器端通过 `new Function` 把纯标量代码块编译成原生 JS 达到个位数毫秒；
 * Kotlin/JVM 端无法动态生成原生代码，这里把代码块编译成「栈式 double 指令序列」，
 * 执行期只用 [DoubleArray] 寄存器 + [DoubleArray] 栈，消除 HashMap / 字符串查表 / Any 装箱。
 * 含向量/矩阵/分量访问/拆包的代码块不在快路径内，由 [ExpressionEvaluator] 通用解释器回退处理。
 */

/** 寄存器槽布局：0..2 内建 i/n/t，3..15 属性，16.. 变量，之后临时变量。 */
internal object Reg {
    const val I = 0; const val N = 1; const val T = 2
    const val X = 3; const val Y = 4; const val Z = 5
    const val R = 6; const val G = 7; const val B = 8; const val A = 9
    const val VX = 10; const val VY = 11; const val VZ = 12
    const val SC = 13; const val GLOW = 14; const val LIGHT = 15
    const val ATTR_COUNT = 13
    const val VAR_START = 16
}

/** 属性寄存器初始值（X,Y,Z,R,G,B,A,VX,VY,VZ,SC,GLOW,LIGHT）。 */
private val ATTR_INIT = doubleArrayOf(0.0, 0.0, 0.0, 1.0, 1.0, 1.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0)

private val ATTR_SLOTS = mapOf(
    "x" to Reg.X, "y" to Reg.Y, "z" to Reg.Z,
    "r" to Reg.R, "g" to Reg.G, "b" to Reg.B, "a" to Reg.A,
    "vx" to Reg.VX, "vy" to Reg.VY, "vz" to Reg.VZ,
    "sc" to Reg.SC, "glow" to Reg.GLOW, "light" to Reg.LIGHT,
)

// ---- 操作码 ----
private const val OP_PUSH_CONST = 0
private const val OP_PUSH_REG = 1
private const val OP_POP_REG = 2
private const val OP_NEG = 3
private const val OP_ADD = 4
private const val OP_SUB = 5
private const val OP_MUL = 6
private const val OP_DIV = 7
private const val OP_MOD = 8
private const val OP_POW = 9
private const val FN_SIN = 10
private const val FN_COS = 11
private const val FN_TAN = 12
private const val FN_ASIN = 13
private const val FN_ACOS = 14
private const val FN_ATAN = 15
private const val FN_ATAN2 = 16
private const val FN_SQRT = 17
private const val FN_ABS = 18
private const val FN_SIGN = 19
private const val FN_EXP = 20
private const val FN_LOG = 21
private const val FN_FLOOR = 22
private const val FN_CEIL = 23
private const val FN_ROUND = 24
private const val FN_FRACT = 25
private const val FN_POW = 26
private const val FN_MIN = 27
private const val FN_MAX = 28
private const val FN_CLAMP = 29
private const val FN_LERP = 30
private const val FN_STEP = 31
private const val FN_SMOOTHSTEP = 32
private const val FN_MOD = 33
private const val FN_RANDOM = 34
private const val FN_RAND = 35
private const val OP_VARKF = 36

/** 标量函数名 -> 操作码（与编辑器 easing.js SCALAR_FUNC_GEN 对齐；log/ln 同映射）。 */
private val SCALAR_FUNC_OPS = mapOf(
    "sin" to FN_SIN, "cos" to FN_COS, "tan" to FN_TAN, "asin" to FN_ASIN, "acos" to FN_ACOS, "atan" to FN_ATAN,
    "atan2" to FN_ATAN2, "sqrt" to FN_SQRT, "abs" to FN_ABS, "sign" to FN_SIGN, "exp" to FN_EXP,
    "log" to FN_LOG, "ln" to FN_LOG, "floor" to FN_FLOOR, "ceil" to FN_CEIL, "round" to FN_ROUND,
    "fract" to FN_FRACT, "pow" to FN_POW, "min" to FN_MIN, "max" to FN_MAX, "clamp" to FN_CLAMP,
    "lerp" to FN_LERP, "step" to FN_STEP, "smoothstep" to FN_SMOOTHSTEP, "mod" to FN_MOD,
    "random" to FN_RANDOM, "rand" to FN_RAND,
)

/** 编译后的纯标量指令序列。 */
internal class ScalarProgram(
    private val ops: IntArray,
    private val args: IntArray,
    private val consts: DoubleArray,
) {
    fun exec(regs: DoubleArray, stack: DoubleArray, kfTable: Array<List<Keyframe>>?) {
        val ops = this.ops
        val args = this.args
        val consts = this.consts
        var sp = 0
        for (idx in ops.indices) {
            when (ops[idx]) {
                OP_PUSH_CONST -> stack[sp++] = consts[args[idx]]
                OP_PUSH_REG -> stack[sp++] = regs[args[idx]]
                OP_POP_REG -> { sp--; regs[args[idx]] = stack[sp] }
                OP_NEG -> stack[sp - 1] = -stack[sp - 1]
                OP_ADD -> { sp--; stack[sp - 1] += stack[sp] }
                OP_SUB -> { sp--; stack[sp - 1] -= stack[sp] }
                OP_MUL -> { sp--; stack[sp - 1] *= stack[sp] }
                OP_DIV -> { sp--; stack[sp - 1] /= stack[sp] }
                OP_MOD -> { sp--; stack[sp - 1] %= stack[sp] }
                OP_POW -> { sp--; stack[sp - 1] = stack[sp - 1].pow(stack[sp]) }
                FN_SIN -> stack[sp - 1] = sin(stack[sp - 1])
                FN_COS -> stack[sp - 1] = cos(stack[sp - 1])
                FN_TAN -> stack[sp - 1] = tan(stack[sp - 1])
                FN_ASIN -> stack[sp - 1] = asin(stack[sp - 1])
                FN_ACOS -> stack[sp - 1] = acos(stack[sp - 1])
                FN_ATAN -> stack[sp - 1] = atan(stack[sp - 1])
                FN_ATAN2 -> { sp--; stack[sp - 1] = atan2(stack[sp - 1], stack[sp]) }
                FN_SQRT -> stack[sp - 1] = sqrt(stack[sp - 1])
                FN_ABS -> stack[sp - 1] = abs(stack[sp - 1])
                FN_SIGN -> stack[sp - 1] = sign(stack[sp - 1])
                FN_EXP -> stack[sp - 1] = exp(stack[sp - 1])
                FN_LOG -> stack[sp - 1] = ln(stack[sp - 1])
                FN_FLOOR -> stack[sp - 1] = floor(stack[sp - 1])
                FN_CEIL -> stack[sp - 1] = ceil(stack[sp - 1])
                FN_ROUND -> stack[sp - 1] = round(stack[sp - 1])
                FN_FRACT -> stack[sp - 1] = stack[sp - 1] - floor(stack[sp - 1])
                FN_POW -> { sp--; stack[sp - 1] = stack[sp - 1].pow(stack[sp]) }
                FN_MIN -> { sp--; stack[sp - 1] = min(stack[sp - 1], stack[sp]) }
                FN_MAX -> { sp--; stack[sp - 1] = max(stack[sp - 1], stack[sp]) }
                FN_CLAMP -> { sp -= 2; stack[sp] = stack[sp].coerceIn(stack[sp + 1], stack[sp + 2]) }
                FN_LERP -> { sp -= 2; stack[sp] = stack[sp] + (stack[sp + 1] - stack[sp]) * stack[sp + 2] }
                FN_STEP -> { sp--; stack[sp - 1] = if (stack[sp] >= stack[sp - 1]) 1.0 else 0.0 }
                FN_SMOOTHSTEP -> {
                    sp -= 2
                    val e0 = stack[sp]; val e1 = stack[sp + 1]; val x = stack[sp + 2]
                    val t = ((x - e0) / (e1 - e0)).coerceIn(0.0, 1.0)
                    stack[sp] = t * t * (3 - 2 * t)
                }
                FN_MOD -> { sp--; val a = stack[sp - 1]; val b = stack[sp]; stack[sp - 1] = a - b * floor(a / b) }
                FN_RANDOM -> stack[sp++] = Math.random()
                FN_RAND -> { val x = sin(stack[sp - 1] * 127.1 + 311.7) * 43758.5453; stack[sp - 1] = x - floor(x) }
                OP_VARKF -> {
                    val kf = kfTable!![args[idx]]
                    stack[sp++] = ExpressionEvaluator.varKfValue(kf, regs[Reg.T])
                }
            }
        }
    }
}

/** 函数对象的完整编译产物（纯标量快路径）。 */
internal class CompiledFunction(
    val varCount: Int,
    private val varConsts: DoubleArray?,
    private val varProgs: Array<ScalarProgram>?,
    private val varOrder: IntArray,
    private val kfTable: Array<List<Keyframe>>,
    private val scalar: ScalarProgram,
    val regCount: Int,
    val stackSize: Int,
) {
    fun allocRegs() = DoubleArray(regCount)
    fun allocStack() = DoubleArray(stackSize)

    /** 求值单个粒子：写满属性寄存器（Reg.X..Reg.LIGHT），调用方读取。 */
    fun eval(i: Double, n: Double, t: Double, regs: DoubleArray, stack: DoubleArray) {
        regs[Reg.I] = i
        regs[Reg.N] = n
        regs[Reg.T] = t
        System.arraycopy(ATTR_INIT, 0, regs, Reg.X, Reg.ATTR_COUNT)
        val consts = varConsts
        if (consts != null) {
            System.arraycopy(consts, 0, regs, Reg.VAR_START, varCount)
        } else {
            val progs = varProgs!!
            for (vi in varOrder) progs[vi].exec(regs, stack, kfTable)
        }
        scalar.exec(regs, stack, kfTable)
    }
}

/** 变量编译输入。 */
internal class VarDef(val name: String, val expr: String, val kf: List<Keyframe>)

/**
 * 编译函数对象代码块 + 变量为纯标量快路径；任何非纯标量因素返回 null（回退通用解释器）。
 */
internal fun compileFunctionObject(code: String, varDefs: List<VarDef>): CompiledFunction? {
    val varCount = varDefs.size
    val nameToSlot = HashMap<String, Int>(varCount * 2)
    for (k in varDefs.indices) nameToSlot[varDefs[k].name] = Reg.VAR_START + k

    val varConsts = tryFoldConsts(varDefs)
    val varsResult = if (varConsts == null) compileVarPrograms(varDefs, nameToSlot) ?: return null else null

    val codeResult = compileScalarCode(code, nameToSlot, varCount) ?: return null

    val regCount = Reg.VAR_START + varCount + codeResult.tempCount
    val stackSize = maxOf(
        codeResult.stackSize,
        varsResult?.stackSize ?: 0,
    )

    return CompiledFunction(
        varCount = varCount,
        varConsts = varConsts,
        varProgs = varsResult?.progs,
        varOrder = varsResult?.order ?: IntArray(0),
        kfTable = varsResult?.kfTable ?: emptyArray(),
        scalar = codeResult.program,
        regCount = regCount,
        stackSize = stackSize,
    )
}

/** 常量折叠：所有变量无关键帧且表达式不含任何变量引用时，预计算一次；否则 null。 */
private fun tryFoldConsts(varDefs: List<VarDef>): DoubleArray? {
    val vals = DoubleArray(varDefs.size)
    for (k in varDefs.indices) {
        val v = varDefs[k]
        if (v.kf.isNotEmpty()) return null
        val rpn = ExpressionEvaluator.compile(v.expr)
        for (o in rpn) if (o is ExpressionEvaluator.Token.Var) return null
        val result = ExpressionEvaluator.evaluate(v.expr, emptyMap())
        if (result !is Double) return null
        vals[k] = result
    }
    return vals
}

private class VarProgramsResult(
    val progs: Array<ScalarProgram>,
    val order: IntArray,
    val kfTable: Array<List<Keyframe>>,
    val stackSize: Int,
)

/** 编译变量指令程序（非常量变量），按拓扑序求值，检测循环引用；失败返回 null。 */
private fun compileVarPrograms(varDefs: List<VarDef>, nameToSlot: Map<String, Int>): VarProgramsResult? {
    val n = varDefs.size
    val deps = Array(n) { BooleanArray(n) }
    for (k in 0 until n) {
        val v = varDefs[k]
        if (v.kf.isNotEmpty()) continue
        val rpn = ExpressionEvaluator.compile(v.expr)
        for (o in rpn) {
            if (o is ExpressionEvaluator.Token.Var) {
                val name = o.name
                if (name == "i" || name == "n" || name == "t") continue
                val dep = nameToSlot[name] ?: return null
                deps[k][dep - Reg.VAR_START] = true
            }
        }
    }
    val order = ArrayList<Int>(n)
    val state = IntArray(n)
    fun dfs(x: Int): Boolean {
        if (state[x] == 1) return false
        if (state[x] == 2) return true
        state[x] = 1
        for (j in 0 until n) if (deps[x][j] && !dfs(j)) return false
        state[x] = 2
        order.add(x)
        return true
    }
    for (k in 0 until n) if (!dfs(k)) return null

    val progs = arrayOfNulls<ScalarProgram>(n)
    val kfList = ArrayList<List<Keyframe>>()
    var stackSize = 0
    for (k in 0 until n) {
        val v = varDefs[k]
        val ops = ArrayList<Int>()
        val args = ArrayList<Int>()
        val consts = ArrayList<Double>()
        val maxDepth = intArrayOf(0)
        if (v.kf.isNotEmpty()) {
            val kfIdx = kfList.size
            kfList.add(v.kf)
            ops.add(OP_VARKF); args.add(kfIdx)
            maxDepth[0] = 1
        } else {
            val rpn = ExpressionEvaluator.compile(v.expr)
            val slotOf: (String) -> Int? = { name ->
                when (name) {
                    "i" -> Reg.I; "n" -> Reg.N; "t" -> Reg.T
                    else -> nameToSlot[name]
                }
            }
            if (!emitRpn(rpn, ops, args, consts, slotOf, maxDepth)) return null
        }
        ops.add(OP_POP_REG); args.add(Reg.VAR_START + k)
        progs[k] = ScalarProgram(ops.toIntArray(), args.toIntArray(), consts.toDoubleArray())
        if (maxDepth[0] > stackSize) stackSize = maxDepth[0]
    }
    return VarProgramsResult(progs.requireNoNulls(), order.toIntArray(), kfList.toTypedArray(), stackSize)
}

private class ScalarCodeResult(val program: ScalarProgram, val tempCount: Int, val stackSize: Int)

/** 编译代码块为纯标量指令序列；含向量/矩阵/分量访问/拆包/未知符号时返回 null。 */
private fun compileScalarCode(code: String, nameToSlot: Map<String, Int>, varCount: Int): ScalarCodeResult? {
    val ops = ArrayList<Int>()
    val args = ArrayList<Int>()
    val consts = ArrayList<Double>()
    val tempSlots = HashMap<String, Int>()
    val tempStart = Reg.VAR_START + varCount
    var tempCount = 0
    var stackSize = 0

    fun lhsSlot(name: String): Int? = when (name) {
        "i" -> Reg.I; "n" -> Reg.N; "t" -> Reg.T
        else -> ATTR_SLOTS[name] ?: nameToSlot[name] ?: tempSlots.getOrPut(name) {
            val s = tempStart + tempCount; tempCount++; s
        }
    }

    for (raw in code.split(';')) {
        val stmt = raw.trim()
        if (stmt.isEmpty()) continue
        val eq = stmt.indexOf('=')
        if (eq < 0) return null
        val lhs = stmt.substring(0, eq).trim()
        val rhs = stmt.substring(eq + 1).trim()
        val names = if (lhs.startsWith("[")) parseNameList(lhs) else listOf(lhs)
        val isListRhs = rhs.startsWith("[")
        val exprs = if (isListRhs) parseExprList(rhs) else listOf(rhs)

        if (!isListRhs && names.size != 1) return null
        if (isListRhs && names.size != exprs.size) return null

        for (k in names.indices) {
            val target = lhsSlot(names[k]) ?: return null
            val rpn = ExpressionEvaluator.compile(exprs[k])
            val maxDepth = intArrayOf(0)
            val slotOf: (String) -> Int? = { name ->
                when (name) {
                    "i" -> Reg.I; "n" -> Reg.N; "t" -> Reg.T
                    else -> ATTR_SLOTS[name] ?: nameToSlot[name] ?: tempSlots[name]
                }
            }
            if (!emitRpn(rpn, ops, args, consts, slotOf, maxDepth)) return null
            if (maxDepth[0] > stackSize) stackSize = maxDepth[0]
            ops.add(OP_POP_REG); args.add(target)
        }
    }

    return ScalarCodeResult(
        ScalarProgram(ops.toIntArray(), args.toIntArray(), consts.toDoubleArray()),
        tempCount,
        stackSize,
    )
}

/** RPN -> 指令序列；返回 false 表示含非标量元素（向量/矩阵函数、分量访问、未知符号）。 */
private fun emitRpn(
    rpn: List<Any>,
    ops: ArrayList<Int>,
    args: ArrayList<Int>,
    consts: ArrayList<Double>,
    slotOf: (String) -> Int?,
    maxDepth: IntArray,
): Boolean {
    var depth = 0
    for (o in rpn) {
        when (o) {
            is Double -> { ops.add(OP_PUSH_CONST); args.add(consts.size); consts.add(o); depth++ }
            is ExpressionEvaluator.Token.Var -> {
                val slot = slotOf(o.name) ?: return false
                ops.add(OP_PUSH_REG); args.add(slot); depth++
            }
            is ExpressionEvaluator.Token.Comp -> return false
            "neg" -> { ops.add(OP_NEG); args.add(0) }
            is String -> {
                val op = SCALAR_FUNC_OPS[o] ?: return false
                ops.add(op); args.add(0)
                depth -= (FUNCS[o]!! - 1)
            }
            is Char -> {
                val op = when (o) {
                    '+' -> OP_ADD; '-' -> OP_SUB; '*' -> OP_MUL; '/' -> OP_DIV; '%' -> OP_MOD; '^' -> OP_POW
                    else -> return false
                }
                ops.add(op); args.add(0); depth--
            }
            else -> return false
        }
        if (depth > maxDepth[0]) maxDepth[0] = depth
        if (depth < 0) return false
    }
    return depth == 1
}

private fun parseNameList(s: String): List<String> =
    s.trim().removePrefix("[").removeSuffix("]").split(',').map { it.trim() }.filter { it.isNotEmpty() }

private fun parseExprList(s: String): List<String> {
    val inner = s.trim()
    if (!inner.startsWith("[") || !inner.endsWith("]")) return listOf(inner)
    val body = inner.substring(1, inner.length - 1)
    val parts = mutableListOf<String>()
    var depth = 0
    val cur = StringBuilder()
    for (c in body) {
        if (c == '(') depth++
        else if (c == ')') depth--
        if (c == ',' && depth == 0) { parts.add(cur.toString().trim()); cur.setLength(0) }
        else cur.append(c)
    }
    if (cur.isNotEmpty()) parts.add(cur.toString().trim())
    return parts
}
