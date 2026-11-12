package work.nekow.particledrawing.animation.script

import kotlin.math.*

// 纯标量代码块的栈式字节码编译与执行：执行期只用 DoubleArray 寄存器与栈。
// 含向量/矩阵/分量/拆包的代码块不在快路径内，回退解释器。

/** 寄存器槽布局：0..2 内建 i/n/t，3..15 属性，16.. 变量，之后临时变量。 */
object Reg {
    const val I = 0; const val N = 1; const val T = 2
    const val X = 3; const val Y = 4; const val Z = 5
    const val R = 6; const val G = 7; const val B = 8; const val A = 9
    const val VX = 10; const val VY = 11; const val VZ = 12
    const val SC = 13; const val GLOW = 14; const val LIGHT = 15
    const val ATTR_COUNT = 13
    const val VAR_START = 16

    /** 可写属性名 -> 寄存器槽；快路径与解释器共用这一张表。 */
    val ATTR_SLOTS: Map<String, Int> = linkedMapOf(
        "x" to X, "y" to Y, "z" to Z,
        "r" to R, "g" to G, "b" to B, "a" to A,
        "vx" to VX, "vy" to VY, "vz" to VZ,
        "sc" to SC, "glow" to GLOW, "light" to LIGHT,
    )

    /** 把属性寄存器重置为默认值。 */
    fun resetAttrs(regs: DoubleArray) {
        System.arraycopy(ATTR_INIT, 0, regs, X, ATTR_COUNT)
    }
}

/** 属性寄存器初始值（X,Y,Z,R,G,B,A,VX,VY,VZ,SC,GLOW,LIGHT）。 */
private val ATTR_INIT = doubleArrayOf(0.0, 0.0, 0.0, 1.0, 1.0, 1.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0)

/** 栈式指令集。pops 为该指令从求值栈弹出的操作数个数；数据搬运类恒为 -1。 */
enum class ScalarOp(val pops: Int) {
    /** arg = 常量池下标：压入 consts[arg]。 */
    PUSH_CONST(-1),
    /** arg = 寄存器槽：压入 regs[arg]。 */
    PUSH_REG(-1),
    /** arg = 寄存器槽：弹栈写入 regs[arg]。 */
    POP_REG(-1),
    /** arg = kfTable 下标：按当前 t 插值变量关键帧并压栈。 */
    VAR_KF(-1),

    NEG(1),
    ADD(2), SUB(2), MUL(2), DIV(2), REM(2), POW(2),

    /** 比较与逻辑：结果写成 1.0 / 0.0，和条件求值的口径一致。 */
    LT(2), LE(2), GT(2), GE(2), EQ(2), NE(2),
    AND(2), OR(2), NOT(1),

    F_SIN(1), F_COS(1), F_TAN(1),
    F_ASIN(1), F_ACOS(1), F_ATAN(1),
    F_ATAN2(2), F_SQRT(1), F_ABS(1), F_SIGN(1),
    F_EXP(1), F_LOG(1),
    F_FLOOR(1), F_CEIL(1), F_ROUND(1), F_FRACT(1),
    F_POW(2), F_MIN(2), F_MAX(2),
    F_CLAMP(3), F_STEP(2), F_SMOOTHSTEP(3), F_MOD(2),
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
    "step" to ScalarOp.F_STEP,
    "smoothstep" to ScalarOp.F_SMOOTHSTEP, "mod" to ScalarOp.F_MOD,
    "random" to ScalarOp.F_RANDOM, "rand" to ScalarOp.F_RAND,
)

/** 标量函数参数个数；与 SCALAR_FUNC_OPS 一一对应。 */
private val SCALAR_FUNC_ARITY = mapOf(
    "sin" to 1, "cos" to 1, "tan" to 1,
    "asin" to 1, "acos" to 1, "atan" to 1,
    "atan2" to 2, "sqrt" to 1, "abs" to 1,
    "sign" to 1, "exp" to 1,
    "log" to 1, "ln" to 1,
    "floor" to 1, "ceil" to 1, "round" to 1,
    "fract" to 1, "pow" to 2, "min" to 2,
    "max" to 2, "clamp" to 3,
    "step" to 2,
    "smoothstep" to 3, "mod" to 2,
    "random" to 0, "rand" to 1,
)

/** 运算符 -> 操作码；单字符算术与多字符比较/逻辑共用一张表。 */
private val CHAR_OPS = mapOf(
    '+' to ScalarOp.ADD, '-' to ScalarOp.SUB, '*' to ScalarOp.MUL,
    '/' to ScalarOp.DIV, '%' to ScalarOp.REM, '^' to ScalarOp.POW,
)

/** 多字符运算符 -> 操作码。 */
private val WORD_OPS = mapOf(
    "<" to ScalarOp.LT, "<=" to ScalarOp.LE, ">" to ScalarOp.GT, ">=" to ScalarOp.GE,
    "==" to ScalarOp.EQ, "!=" to ScalarOp.NE,
    "&&" to ScalarOp.AND, "||" to ScalarOp.OR,
)

/**
 * 结果表示真假的运算。
 *
 * 解释器只在 `where` / `kill` / `glow` 里把表达式包成 `(expr) ? 1 : 0`，别的位置写出布尔值会报类型错；
 * 编译路径同样只允许它们出现在这些条件位置，否则回退，避免出现「原来报错、现在能跑」的分叉。
 */
private val CONDITION_OPS = setOf(
    ScalarOp.LT, ScalarOp.LE, ScalarOp.GT, ScalarOp.GE, ScalarOp.EQ, ScalarOp.NE,
    ScalarOp.AND, ScalarOp.OR, ScalarOp.NOT,
)

/** 编译后的纯标量指令序列。 */
class ScalarProgram(
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
                ScalarOp.LT -> { sp--; stack[sp - 1] = if (stack[sp - 1] < stack[sp]) 1.0 else 0.0 }
                ScalarOp.LE -> { sp--; stack[sp - 1] = if (stack[sp - 1] <= stack[sp]) 1.0 else 0.0 }
                ScalarOp.GT -> { sp--; stack[sp - 1] = if (stack[sp - 1] > stack[sp]) 1.0 else 0.0 }
                ScalarOp.GE -> { sp--; stack[sp - 1] = if (stack[sp - 1] >= stack[sp]) 1.0 else 0.0 }
                ScalarOp.EQ -> { sp--; stack[sp - 1] = if (stack[sp - 1] == stack[sp]) 1.0 else 0.0 }
                ScalarOp.NE -> { sp--; stack[sp - 1] = if (stack[sp - 1] != stack[sp]) 1.0 else 0.0 }
                ScalarOp.AND -> { sp--; stack[sp - 1] = if (stack[sp - 1] != 0.0 && stack[sp] != 0.0) 1.0 else 0.0 }
                ScalarOp.OR -> { sp--; stack[sp - 1] = if (stack[sp - 1] != 0.0 || stack[sp] != 0.0) 1.0 else 0.0 }
                ScalarOp.NOT -> stack[sp - 1] = if (stack[sp - 1] == 0.0) 1.0 else 0.0
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
                ScalarOp.F_ROUND -> stack[sp - 1] = jsRound(stack[sp - 1])
                ScalarOp.F_FRACT -> stack[sp - 1] = stack[sp - 1] - floor(stack[sp - 1])
                ScalarOp.F_POW -> { sp--; stack[sp - 1] = stack[sp - 1].pow(stack[sp]) }
                ScalarOp.F_MIN -> { sp--; stack[sp - 1] = min(stack[sp - 1], stack[sp]) }
                ScalarOp.F_MAX -> { sp--; stack[sp - 1] = max(stack[sp - 1], stack[sp]) }
                ScalarOp.F_CLAMP -> { sp -= 2; stack[sp - 1] = stack[sp - 1].coerceIn(stack[sp], stack[sp + 1]) }
                ScalarOp.F_STEP -> { sp--; stack[sp - 1] = if (stack[sp] >= stack[sp - 1]) 1.0 else 0.0 }
                ScalarOp.F_SMOOTHSTEP -> {
                    sp -= 2
                    val e0 = stack[sp - 1]; val e1 = stack[sp]; val x = stack[sp + 1]
                    val t = ((x - e0) / (e1 - e0)).coerceIn(0.0, 1.0)
                    stack[sp - 1] = t * t * (3 - 2 * t)
                }
                ScalarOp.F_MOD -> { sp--; val a = stack[sp - 1]; val b = stack[sp]; stack[sp - 1] = a - b * floor(a / b) }
                ScalarOp.F_RANDOM -> stack[sp++] = kotlin.random.Random.nextDouble()
                ScalarOp.F_RAND -> { val x = toInt32(stack[sp - 1]); stack[sp - 1] = RandState(x).next() }
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
                    stack[sp++] = varKfValue(kf, regs[Reg.T])
                }
            }
        }
    }
}

/** 函数对象的完整编译产物（纯标量快路径）。 */
class CompiledFunction(
    val varCount: Int,
    private val extCount: Int,
    private val varConsts: DoubleArray?,
    private val varProgs: Array<ScalarProgram>?,
    private val varOrder: IntArray,
    private val kfTable: Array<List<Keyframe>>,
    private val scalar: ScalarProgram,
    override val regCount: Int,
    val stackSize: Int,
) : CompiledExpression {
    override fun allocRegs() = DoubleArray(regCount)
    override fun allocStack() = DoubleArray(stackSize)

    override fun eval(
        i: Double, n: Double, t: Double, regs: DoubleArray, stack: DoubleArray, external: DoubleArray?,
    ) {
        run(i, n, t, regs, stack, external)
    }

    /** 无外部输入通道时的入口。 */
    fun eval(i: Double, n: Double, t: Double, regs: DoubleArray, stack: DoubleArray) {
        run(i, n, t, regs, stack, null)
    }

    /**
     * 求值单个粒子，写满属性寄存器 Reg.X..Reg.LIGHT。
     *
     * [external] 是外部输入通道值，顺序与编译期登记的 extNames 一致，长度为 [extCount]；
     * 在变量程序求值之前注入寄存器，派生变量因此可引用实体坐标等运行时输入。
     */
    private fun run(
        i: Double, n: Double, t: Double, regs: DoubleArray, stack: DoubleArray,
        external: DoubleArray?,
    ) {
        regs[Reg.I] = i
        regs[Reg.N] = n
        regs[Reg.T] = t
        Reg.resetAttrs(regs)
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
        scalar.exec(regs, stack, kfTable)
    }
}

/** 变量编译输入：数值基值与关键帧。 */
class VarDef(val name: String, val base: Double, val kf: List<Keyframe>)

/**
 * 编译函数对象代码块与变量为纯标量快路径；含非纯标量因素时返回 null，由调用方回退通用解释器。
 *
 * @param extNames 外部输入通道变量名（如实体坐标 e_x/e_y/e_z），只登记槽位、不生成求值程序
 */
fun compileFunctionObject(code: String, varDefs: List<VarDef>, extNames: List<String> = emptyList()): CompiledFunction? {
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

/** 一段直接操作寄存器的纯标量代码：调用方自己负责分配寄存器与栈。 */
class RegisterCode(val program: ScalarProgram, val tempCount: Int, val stackSize: Int)

/**
 * 编译一串「目标名 = 表达式」的赋值，直接写调用方给定的寄存器槽。
 *
 * 与 [compileFunctionObject] 的区别：不重置属性、不注入外部输入、不做变量关键帧，
 * 完全由调用方决定寄存器含义。逐粒子逐 tick 的配方求值走这条路，槽位在编译期固定。
 *
 * @param tempStart 临时槽起点；寄存器总数 = tempStart + 返回的 tempCount
 * @param conditionTargets 这些目标名的表达式整体处于条件位置（结果按真假用），允许写比较与逻辑运算
 */
fun compileRegisterCode(
    assigns: List<Pair<String, Node>>,
    nameToSlot: Map<String, Int>,
    tempStart: Int,
    conditionTargets: Set<String> = emptySet(),
): RegisterCode? {
    val ops = ArrayList<ScalarOp>()
    val args = ArrayList<Int>()
    val consts = ArrayList<Double>()
    val tempSlots = HashMap<String, Int>()
    var tempCount = 0
    var stackSize = 0

    // 读：只认已登记的槽；未知名字说明含非标量成分，交给调用方回退
    val readSlot: (String) -> Int? = { name ->
        when (name) {
            "i" -> Reg.I; "n" -> Reg.N; "t" -> Reg.T
            else -> Reg.ATTR_SLOTS[name] ?: nameToSlot[name] ?: tempSlots[name]
        }
    }
    // 写：首次赋值就地分配临时槽，按声明顺序可见
    val writeSlot: (String) -> Int? = { name ->
        when (name) {
            "i" -> Reg.I; "n" -> Reg.N; "t" -> Reg.T
            else -> Reg.ATTR_SLOTS[name] ?: nameToSlot[name] ?: tempSlots.getOrPut(name) {
                val s = tempStart + tempCount; tempCount++; s
            }
        }
    }

    for ((target, value) in assigns) {
        val slot = writeSlot(target) ?: return null
        val depth = intArrayOf(0)
        val maxDepth = intArrayOf(0)
        if (!emitExpr(value, ops, args, consts, readSlot, depth, maxDepth, target in conditionTargets)) return null
        if (depth[0] != 1) return null
        ops.add(ScalarOp.POP_REG); args.add(slot)
        if (maxDepth[0] > stackSize) stackSize = maxDepth[0]
    }

    return RegisterCode(
        ScalarProgram(ops.toTypedArray(), args.toIntArray(), consts.toDoubleArray()),
        tempCount,
        stackSize,
    )
}

/** 把一条表达式解析成 AST；语法错误或空表达式返回 null。 */
fun parseExpressionNode(expr: String): Node? = try {
    (parseStatements("__pd_tmp = ($expr)").singleOrNull() as? AssignNode)?.value
} catch (_: Exception) {
    null
}

/** 编译代码块为纯标量指令序列；含向量/矩阵/分量访问/拆包/未知符号时返回 null。 */
private fun compileScalarCode(code: String, nameToSlot: Map<String, Int>, varCount: Int): ScalarCodeResult? {
    val statements = try {
        parseStatements(code)
    } catch (_: Exception) {
        return null
    }

    // 把解包写法摊平成逐名赋值，核心编译只认「一个目标 + 一个表达式」
    val assigns = ArrayList<Pair<String, Node>>()
    for (stmt in statements) {
        val assign = stmt as? AssignNode ?: return null
        val targets = when (val target = assign.target) {
            is VarTarget -> listOf(target.name)
            is UnpackTarget -> target.names
            else -> return null
        }
        val values = when (val value = assign.value) {
            is ArrayNode -> {
                if (value.items.size != targets.size) return null
                value.items
            }
            else -> {
                if (targets.size != 1) return null
                listOf(value)
            }
        }
        for (k in targets.indices) assigns += targets[k] to values[k]
    }

    val compiled = compileRegisterCode(assigns, nameToSlot, Reg.VAR_START + varCount) ?: return null
    return ScalarCodeResult(compiled.program, compiled.tempCount, compiled.stackSize)
}

/** AST -> 指令序列；返回 false 表示含非标量元素（向量/矩阵/分量访问/未知符号）。 */
private fun emitExpr(
    node: Node,
    ops: ArrayList<ScalarOp>,
    args: ArrayList<Int>,
    consts: ArrayList<Double>,
    slotOf: (String) -> Int?,
    depth: IntArray,
    maxDepth: IntArray,
    allowCond: Boolean,
): Boolean {
    fun push(op: ScalarOp, arg: Int, delta: Int) {
        ops.add(op); args.add(arg)
        depth[0] += delta
        if (depth[0] > maxDepth[0]) maxDepth[0] = depth[0]
    }

    fun apply(op: ScalarOp): Boolean {
        push(op, 0, 1 - op.pops)
        return depth[0] >= 0
    }

    when (node) {
        is NumNode -> {
            push(ScalarOp.PUSH_CONST, consts.size, 1)
            consts.add(node.value)
            return true
        }
        is BoolNode -> {
            push(ScalarOp.PUSH_CONST, consts.size, 1)
            consts.add(if (node.value) 1.0 else 0.0)
            return true
        }
        is VarNode -> {
            val slot = slotOf(node.name) ?: return false
            push(ScalarOp.PUSH_REG, slot, 1)
            return true
        }
        is UnaryNode -> {
            when (node.op) {
                "-" -> {
                    if (!emitExpr(node.operand, ops, args, consts, slotOf, depth, maxDepth, false)) return false
                    return apply(ScalarOp.NEG)
                }
                "!" -> {
                    if (!allowCond) return false
                    // 取反的入参仍是条件
                    if (!emitExpr(node.operand, ops, args, consts, slotOf, depth, maxDepth, true)) return false
                    return apply(ScalarOp.NOT)
                }
                else -> return false
            }
        }
        is BinaryNode -> {
            val op = node.op.singleOrNull()?.let { CHAR_OPS[it] } ?: WORD_OPS[node.op] ?: return false
            // 比较/逻辑的结果是布尔：只有条件位置才允许
            if (op in CONDITION_OPS && !allowCond) return false
            // && 和 || 的两侧都是条件，比较的两侧和算术的两侧都是数值
            val operandCond = op == ScalarOp.AND || op == ScalarOp.OR
            if (!emitExpr(node.left, ops, args, consts, slotOf, depth, maxDepth, operandCond)) return false
            if (!emitExpr(node.right, ops, args, consts, slotOf, depth, maxDepth, operandCond)) return false
            return apply(op)
        }
        is CallNode -> {
            val callee = node.callee as? VarNode ?: return false
            val op = SCALAR_FUNC_OPS[callee.name] ?: return false
            val arity = SCALAR_FUNC_ARITY[callee.name] ?: return false
            if (node.args.size != arity) return false
            for (arg in node.args) {
                if (!emitExpr(arg, ops, args, consts, slotOf, depth, maxDepth, false)) return false
            }
            return apply(op)
        }
        else -> return false
    }
}
