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

/** 寄存器槽布局：0..2 内建 i/n/t，3..16 属性（含 maxAge），17.. 变量，之后临时变量。 */
internal object Reg {
    const val I = 0; const val N = 1; const val T = 2
    const val X = 3; const val Y = 4; const val Z = 5
    const val R = 6; const val G = 7; const val B = 8; const val A = 9
    const val VX = 10; const val VY = 11; const val VZ = 12
    const val SC = 13; const val GLOW = 14; const val LIGHT = 15
    /** 函数对象寿命输出：代码里 `maxAge = ...`（tick；<0=无限）。仅表达式模式消费。 */
    const val MAXAGE = 16
    const val ATTR_COUNT = 14
    const val VAR_START = 17
}

/** 属性寄存器初始值（X,Y,Z,R,G,B,A,VX,VY,VZ,SC,GLOW,LIGHT,MAXAGE）；maxAge 缺省 -1=无限。 */
private val ATTR_INIT = doubleArrayOf(0.0, 0.0, 0.0, 1.0, 1.0, 1.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, -1.0)

private val ATTR_SLOTS = mapOf(
    "x" to Reg.X, "y" to Reg.Y, "z" to Reg.Z,
    "r" to Reg.R, "g" to Reg.G, "b" to Reg.B, "a" to Reg.A,
    "vx" to Reg.VX, "vy" to Reg.VY, "vz" to Reg.VZ,
    "sc" to Reg.SC, "glow" to Reg.GLOW, "light" to Reg.LIGHT,
    "maxAge" to Reg.MAXAGE,
)

/**
 * 栈式指令集。
 *
 * @param pops 该表达式指令从求值栈弹出的操作数个数；编译期据此计算栈深。
 *             数据搬运类指令（PUSH_CONST / PUSH_REG / POP_REG / VAR_KF）不参与表达式深度计算，恒为 -1。
 */
internal enum class ScalarOp(val pops: Int) {
    // —— 数据搬运 ——
    /** arg = 常量池下标：压入 consts[arg]。 */
    PUSH_CONST(-1),
    /** arg = 寄存器槽：压入 regs[arg]。 */
    PUSH_REG(-1),
    /** arg = 寄存器槽：弹栈写入 regs[arg]。 */
    POP_REG(-1),
    /** arg = kfTable 下标：按当前 t 插值变量关键帧并压栈。 */
    VAR_KF(-1),

    // —— 运算符（弹出 2 压回 1）——
    NEG(1),
    ADD(2), SUB(2), MUL(2), DIV(2), REM(2), POW(2),

    // —— 标量函数（与编辑器 easing.js SCALAR_FUNC_GEN 对齐）——
    F_SIN(1), F_COS(1), F_TAN(1),
    F_ASIN(1), F_ACOS(1), F_ATAN(1),
    F_ATAN2(2), F_SQRT(1), F_ABS(1), F_SIGN(1),
    F_EXP(1), F_LOG(1),
    F_FLOOR(1), F_CEIL(1), F_ROUND(1), F_FRACT(1),
    F_POW(2), F_MIN(2), F_MAX(2),
    F_CLAMP(3), F_LERP(3), F_STEP(2), F_SMOOTHSTEP(3), F_MOD(2),
    /** 纯随机源：只压栈。 */
    F_RANDOM(0),
    /** 固定种子伪随机（同一种子恒返回相同 [0,1) 值）。 */
    F_RAND(1),
    /** 对象数组下标读取：弹出 double 下标，从 objects[arg] 读取并压回 double。 */
    READ_ARR(1),
}

/** 标量函数名 -> 操作码（log/ln 同映射）。 */
private val SCALAR_FUNC_OPS = mapOf(
    "sin" to ScalarOp.F_SIN, "cos" to ScalarOp.F_COS, "tan" to ScalarOp.F_TAN,
    "asin" to ScalarOp.F_ASIN, "acos" to ScalarOp.F_ACOS, "atan" to ScalarOp.F_ATAN,
    "atan2" to ScalarOp.F_ATAN2, "sqrt" to ScalarOp.F_SQRT, "abs" to ScalarOp.F_ABS,
    "sign" to ScalarOp.F_SIGN, "exp" to ScalarOp.F_EXP,
    "log" to ScalarOp.F_LOG, "ln" to ScalarOp.F_LOG,
    "floor" to ScalarOp.F_FLOOR, "ceil" to ScalarOp.F_CEIL, "round" to ScalarOp.F_ROUND,
    "fract" to ScalarOp.F_FRACT, "pow" to ScalarOp.F_POW, "min" to ScalarOp.F_MIN,
    "max" to ScalarOp.F_MAX, "clamp" to ScalarOp.F_CLAMP,
    "lerp" to ScalarOp.F_LERP, "step" to ScalarOp.F_STEP,
    "smoothstep" to ScalarOp.F_SMOOTHSTEP, "mod" to ScalarOp.F_MOD,
    "random" to ScalarOp.F_RANDOM, "rand" to ScalarOp.F_RAND,
)

/** 运算符字符 -> 操作码。 */
private val CHAR_OPS = mapOf(
    '+' to ScalarOp.ADD, '-' to ScalarOp.SUB, '*' to ScalarOp.MUL,
    '/' to ScalarOp.DIV, '%' to ScalarOp.REM, '^' to ScalarOp.POW,
)

/** 编译后的纯标量指令序列。 */
internal class ScalarProgram(
    private val ops: Array<ScalarOp>,
    private val args: IntArray,
    private val consts: DoubleArray,
) {
    fun exec(regs: DoubleArray, stack: DoubleArray, kfTable: Array<List<Keyframe>>?, objects: Array<Any?>? = null) {
        val ops = this.ops
        val args = this.args
        val consts = this.consts
        var sp = 0
        for (idx in ops.indices) {
            when (ops[idx]) {
                ScalarOp.PUSH_CONST -> stack[sp++] = consts[args[idx]]
                ScalarOp.PUSH_REG -> stack[sp++] = regs[args[idx]]
                ScalarOp.POP_REG -> { sp--; regs[args[idx]] = stack[sp] }
                ScalarOp.NEG -> stack[sp - 1] = -stack[sp - 1]
                ScalarOp.ADD -> { sp--; stack[sp - 1] += stack[sp] }
                ScalarOp.SUB -> { sp--; stack[sp - 1] -= stack[sp] }
                ScalarOp.MUL -> { sp--; stack[sp - 1] *= stack[sp] }
                ScalarOp.DIV -> { sp--; stack[sp - 1] /= stack[sp] }
                ScalarOp.REM -> { sp--; stack[sp - 1] %= stack[sp] }
                ScalarOp.POW -> { sp--; stack[sp - 1] = stack[sp - 1].pow(stack[sp]) }
                ScalarOp.F_SIN -> stack[sp - 1] = sin(stack[sp - 1])
                ScalarOp.F_COS -> stack[sp - 1] = cos(stack[sp - 1])
                ScalarOp.F_TAN -> stack[sp - 1] = tan(stack[sp - 1])
                ScalarOp.F_ASIN -> stack[sp - 1] = asin(stack[sp - 1])
                ScalarOp.F_ACOS -> stack[sp - 1] = acos(stack[sp - 1])
                ScalarOp.F_ATAN -> stack[sp - 1] = atan(stack[sp - 1])
                ScalarOp.F_ATAN2 -> { sp--; stack[sp - 1] = atan2(stack[sp - 1], stack[sp]) }
                ScalarOp.F_SQRT -> stack[sp - 1] = sqrt(stack[sp - 1])
                ScalarOp.F_ABS -> stack[sp - 1] = abs(stack[sp - 1])
                ScalarOp.F_SIGN -> stack[sp - 1] = sign(stack[sp - 1])
                ScalarOp.F_EXP -> stack[sp - 1] = exp(stack[sp - 1])
                ScalarOp.F_LOG -> stack[sp - 1] = ln(stack[sp - 1])
                ScalarOp.F_FLOOR -> stack[sp - 1] = floor(stack[sp - 1])
                ScalarOp.F_CEIL -> stack[sp - 1] = ceil(stack[sp - 1])
                ScalarOp.F_ROUND -> stack[sp - 1] = round(stack[sp - 1])
                ScalarOp.F_FRACT -> stack[sp - 1] = stack[sp - 1] - floor(stack[sp - 1])
                ScalarOp.F_POW -> { sp--; stack[sp - 1] = stack[sp - 1].pow(stack[sp]) }
                ScalarOp.F_MIN -> { sp--; stack[sp - 1] = min(stack[sp - 1], stack[sp]) }
                ScalarOp.F_MAX -> { sp--; stack[sp - 1] = max(stack[sp - 1], stack[sp]) }
                ScalarOp.F_CLAMP -> { sp -= 2; stack[sp] = stack[sp].coerceIn(stack[sp + 1], stack[sp + 2]) }
                ScalarOp.F_LERP -> { sp -= 2; stack[sp] = stack[sp] + (stack[sp + 1] - stack[sp]) * stack[sp + 2] }
                ScalarOp.F_STEP -> { sp--; stack[sp - 1] = if (stack[sp] >= stack[sp - 1]) 1.0 else 0.0 }
                ScalarOp.F_SMOOTHSTEP -> {
                    sp -= 2
                    val e0 = stack[sp]; val e1 = stack[sp + 1]; val x = stack[sp + 2]
                    val t = ((x - e0) / (e1 - e0)).coerceIn(0.0, 1.0)
                    stack[sp] = t * t * (3 - 2 * t)
                }
                ScalarOp.F_MOD -> { sp--; val a = stack[sp - 1]; val b = stack[sp]; stack[sp - 1] = a - b * floor(a / b) }
                ScalarOp.F_RANDOM -> stack[sp++] = Math.random()
                ScalarOp.F_RAND -> { val x = sin(stack[sp - 1] * 127.1 + 311.7) * 43758.5453; stack[sp - 1] = x - floor(x) }
                ScalarOp.READ_ARR -> {
                    val arr = objects!![args[idx]]
                    if (arr !is List<*>) throw IllegalArgumentException("array index requires an array")
                    val d = stack[sp - 1]
                    if (d % 1.0 != 0.0) throw IllegalArgumentException("array index requires an integer")
                    val n = d.toInt()
                    if (n < 0 || n >= arr.size) throw IllegalArgumentException("array index $n out of bounds (size ${arr.size})")
                    val v = arr[n]
                    stack[sp - 1] = if (v is Number) v.toDouble() else throw IllegalArgumentException("array element is not a number")
                }
                ScalarOp.VAR_KF -> {
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
    private val extCount: Int,
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

    /**
     * 求值单个粒子：写满属性寄存器（Reg.X..Reg.LIGHT），调用方读取。
     * [external] 为外部输入通道值（顺序与编译期登记的 extNames 一致，长度 = [extCount]），
     * 在变量程序求值**之前**注入寄存器——派生变量因此可引用实体坐标等运行时输入。
     */
    fun eval(
        i: Double, n: Double, t: Double, regs: DoubleArray, stack: DoubleArray,
        external: DoubleArray? = null,
        kfTableOverride: Array<List<Keyframe>>? = null,
    ) {
        regs[Reg.I] = i
        regs[Reg.N] = n
        regs[Reg.T] = t
        System.arraycopy(ATTR_INIT, 0, regs, Reg.X, Reg.ATTR_COUNT)
        if (external != null && extCount > 0) {
            System.arraycopy(external, 0, regs, Reg.VAR_START, extCount.coerceAtMost(external.size))
        }
        val consts = varConsts
        if (consts != null) {
            System.arraycopy(consts, 0, regs, Reg.VAR_START + extCount, varCount)
        } else {
            val progs = varProgs!!
            for (vi in varOrder) progs[vi].exec(regs, stack, kfTable)
        }
        scalar.exec(regs, stack, kfTableOverride ?: kfTable)
    }
}

/** 变量编译输入（数值基值 + 关键帧；编辑器变量不再使用表达式）。 */
internal class VarDef(val name: String, val base: Double, val kf: List<Keyframe>)

/**
 * 编译函数对象代码块 + 变量为纯标量快路径；任何非纯标量因素返回 null（回退通用解释器）。
 *
 * @param extNames 外部输入通道变量名（如实体坐标 e_x/e_y/e_z）：仅登记槽位、
 *   不生成求值程序，运行时经 [CompiledFunction.eval] 的 external 参数预注入。
 */
internal fun compileFunctionObject(code: String, varDefs: List<VarDef>, extNames: List<String> = emptyList()): CompiledFunction? {
    val extCount = extNames.size
    val varCount = varDefs.size
    val nameToSlot = HashMap<String, Int>((varCount + extCount) * 2)
    for ((k, name) in extNames.withIndex()) nameToSlot[name] = Reg.VAR_START + k
    for (k in varDefs.indices) nameToSlot[varDefs[k].name] = Reg.VAR_START + extCount + k

    val varConsts = tryFoldConsts(varDefs)
    val varsResult = if (varConsts == null) compileVarPrograms(varDefs, Reg.VAR_START + extCount) else null

    val codeResult = compileScalarCode(code, nameToSlot, extCount + varCount) ?: return null

    val regCount = Reg.VAR_START + extCount + varCount + codeResult.tempCount
    val stackSize = maxOf(
        codeResult.stackSize,
        varsResult?.stackSize ?: 0,
    )

    return CompiledFunction(
        varCount = varCount,
        extCount = extCount,
        varConsts = varConsts,
        varProgs = varsResult?.progs,
        varOrder = varsResult?.order ?: IntArray(0),
        kfTable = varsResult?.kfTable ?: emptyArray(),
        scalar = codeResult.program,
        regCount = regCount,
        stackSize = stackSize,
    )
}

/** 常量折叠：所有变量都无关键帧时，直接取数值基值；否则 null。 */
private fun tryFoldConsts(varDefs: List<VarDef>): DoubleArray? {
    for (v in varDefs) if (v.kf.isNotEmpty()) return null
    val vals = DoubleArray(varDefs.size)
    for (k in varDefs.indices) vals[k] = varDefs[k].base
    return vals
}

private class VarProgramsResult(
    val progs: Array<ScalarProgram>,
    val order: IntArray,
    val kfTable: Array<List<Keyframe>>,
    val stackSize: Int,
)

/** 编译变量指令程序（含关键帧时使用；无关键帧的变量压入数值基值）。[varBase] 为变量寄存器起始槽位。 */
private fun compileVarPrograms(varDefs: List<VarDef>, varBase: Int): VarProgramsResult {
    val n = varDefs.size
    val progs = arrayOfNulls<ScalarProgram>(n)
    val kfList = ArrayList<List<Keyframe>>()
    for (k in 0 until n) {
        val v = varDefs[k]
        val ops = ArrayList<ScalarOp>()
        val args = ArrayList<Int>()
        val consts = ArrayList<Double>()
        if (v.kf.isNotEmpty()) {
            val kfIdx = kfList.size
            kfList.add(v.kf)
            ops.add(ScalarOp.VAR_KF); args.add(kfIdx)
        } else {
            ops.add(ScalarOp.PUSH_CONST); args.add(consts.size); consts.add(v.base)
        }
        ops.add(ScalarOp.POP_REG); args.add(varBase + k)
        progs[k] = ScalarProgram(ops.toTypedArray(), args.toIntArray(), consts.toDoubleArray())
    }
    val order = IntArray(n) { it }
    return VarProgramsResult(progs.requireNoNulls(), order, kfList.toTypedArray(), 1)
}

private class ScalarCodeResult(val program: ScalarProgram, val tempCount: Int, val stackSize: Int)

/** 编译代码块为纯标量指令序列；含向量/矩阵/分量访问/拆包/未知符号时返回 null。 */
private fun compileScalarCode(code: String, nameToSlot: Map<String, Int>, varCount: Int): ScalarCodeResult? {
    val ops = ArrayList<ScalarOp>()
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
            ops.add(ScalarOp.POP_REG); args.add(target)
        }
    }

    return ScalarCodeResult(
        ScalarProgram(ops.toTypedArray(), args.toIntArray(), consts.toDoubleArray()),
        tempCount,
        stackSize,
    )
}

/** RPN -> 指令序列；返回 false 表示含非标量元素（向量/矩阵函数、分量访问、未知符号）。 */
private fun emitRpn(
    rpn: List<Any>,
    ops: ArrayList<ScalarOp>,
    args: ArrayList<Int>,
    consts: ArrayList<Double>,
    slotOf: (String) -> Int?,
    maxDepth: IntArray,
): Boolean {
    var depth = 0
    for (o in rpn) {
        when (o) {
            is Double -> { ops.add(ScalarOp.PUSH_CONST); args.add(consts.size); consts.add(o); depth++ }
            is ExpressionEvaluator.Token.Var -> {
                val slot = slotOf(o.name) ?: return false
                ops.add(ScalarOp.PUSH_REG); args.add(slot); depth++
            }
            is ExpressionEvaluator.Token.Comp -> return false
            "neg" -> { ops.add(ScalarOp.NEG); args.add(0) } // 一元取负：弹出 1 压回 1，深度不变
            is String -> {
                val op = SCALAR_FUNC_OPS[o] ?: return false
                ops.add(op); args.add(0)
                depth += 1 - op.pops
            }
            is Char -> {
                val op = CHAR_OPS[o] ?: return false
                ops.add(op); args.add(0)
                depth += 1 - op.pops
            }
            else -> return false
        }
        if (depth > maxDepth[0]) maxDepth[0] = depth
        if (depth < 0) return false
    }
    return depth == 1
}

/** 解析赋值左侧的名字列表：`[x, y, z]` → ["x","y","z"]；单名直接返回。 */
private fun parseNameList(s: String): List<String> =
    s.trim().removePrefix("[").removeSuffix("]").split(',').map { it.trim() }.filter { it.isNotEmpty() }

/** 解析赋值右侧的表达式列表：`[a, b, c]` → ["a","b","c"]（忽略括号内逗号）；非列表包装为单项。 */
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
