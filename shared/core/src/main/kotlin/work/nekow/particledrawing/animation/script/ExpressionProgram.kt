package work.nekow.particledrawing.animation.script

/** 程序表达式执行后端：快路径字节码与完整解释器共用同一入口。 */
interface CompiledExpression {
    val regCount: Int

    fun allocRegs(): DoubleArray
    fun allocStack(): DoubleArray
    fun eval(i: Double, n: Double, t: Double, regs: DoubleArray, stack: DoubleArray, external: DoubleArray? = null)
}

/**
 * 编译一段程序表达式代码块。纯标量先走字节码快路径；快路径表达不了的
 * （向量、矩阵、分量访问、高级函数等）交给完整表达式解释器。
 */
fun compileExpressionBlock(code: String, extNames: List<String> = emptyList()): CompiledExpression? =
    compileFunctionObject(code, emptyList(), extNames) ?: InterpretedExpression.parse(code, extNames)

private sealed interface Step
private class ScalarStep(val name: String, val runner: ScriptRuntime.ExpressionRunner) : Step
private class ListStep(val names: List<String>, val runners: List<ScriptRuntime.ExpressionRunner>) : Step
private class VectorStep(val names: List<String>, val runner: ScriptRuntime.ExpressionRunner) : Step

/** 按语句顺序逐条求值的解释器；只在快路径编不出时使用，允许向量/矩阵和完整内建函数。 */
private class InterpretedExpression private constructor(
    private val steps: List<Step>,
    private val extNames: List<String>,
) : CompiledExpression {

    // 逐粒子逐帧求值，变量表与上下文复用同一份，不清空重建。
    // 代价是这个实例不能跨线程同时用；与 ClientAnimationProgramManager 的单线程约定一致。
    private val vars = HashMap<String, Any?>()
    private val ctx = ScriptRuntime.ProcessCtx(0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 0.0, vars)

    override val regCount: Int get() = Reg.VAR_START + extNames.size
    override fun allocRegs(): DoubleArray = DoubleArray(regCount)
    override fun allocStack(): DoubleArray = DoubleArray(0)

    override fun eval(
        i: Double, n: Double, t: Double, regs: DoubleArray, stack: DoubleArray, external: DoubleArray?,
    ) {
        Reg.resetAttrs(regs)
        vars.clear()
        vars["i"] = i; vars["n"] = n; vars["t"] = t
        for ((name, slot) in Reg.ATTR_SLOTS) vars[name] = regs[slot]
        if (external != null) {
            for (idx in extNames.indices) {
                val value = external.getOrElse(idx) { 0.0 }
                vars[extNames[idx]] = value
                regs[Reg.VAR_START + idx] = value
            }
        }
        ctx.i = i; ctx.n = n; ctx.t = t
        for (step in steps) {
            when (step) {
                is ScalarStep -> write(step.name, step.runner.evalAny(ctx), regs)
                is ListStep -> for (idx in step.names.indices) {
                    write(step.names[idx], step.runners[idx].evalAny(ctx), regs)
                }
                is VectorStep -> {
                    val value = step.runner.evalAny(ctx)
                    for (idx in step.names.indices) write(step.names[idx], component(value, idx), regs)
                }
            }
        }
    }

    private fun write(name: String, value: Any?, regs: DoubleArray) {
        val scalar = when (value) {
            is Double -> value
            is Boolean -> if (value) 1.0 else 0.0
            else -> throw IllegalArgumentException("表达式输出 '$name' 不是标量")
        }
        vars[name] = scalar
        when (name) {
            "i" -> regs[Reg.I] = scalar
            "n" -> regs[Reg.N] = scalar
            "t" -> regs[Reg.T] = scalar
        }
        Reg.ATTR_SLOTS[name]?.let { regs[it] = scalar }
    }

    private fun component(value: Any?, index: Int): Any = when (value) {
        is Vec2 -> when (index) { 0 -> value.x; 1 -> value.y; else -> error("向量分量不足") }
        is Vec3 -> when (index) { 0 -> value.x; 1 -> value.y; 2 -> value.z; else -> error("向量分量不足") }
        is Vec4 -> when (index) { 0 -> value.x; 1 -> value.y; 2 -> value.z; 3 -> value.w; else -> error("向量分量不足") }
        is List<*> -> value.getOrNull(index) ?: throw IllegalArgumentException("数组分量不足")
        else -> throw IllegalArgumentException("解包右侧不是向量或数组")
    }

    companion object {
        fun parse(code: String, extNames: List<String>): InterpretedExpression? {
            val statements = try {
                parseStatements(code)
            } catch (_: Exception) {
                return null
            }
            val steps = ArrayList<Step>()
            for (stmt in statements) {
                val assign = stmt as? AssignNode ?: return null
                when (val target = assign.target) {
                    is VarTarget -> {
                        if (assign.value is ArrayNode) return null
                        steps += ScalarStep(target.name, ScriptRuntime.ExpressionRunner(assign.value))
                    }
                    is UnpackTarget -> {
                        if (target.names.isEmpty()) return null
                        if (assign.value is ArrayNode) {
                            if (assign.value.items.size != target.names.size) return null
                            steps += ListStep(target.names, assign.value.items.map { ScriptRuntime.ExpressionRunner(it) })
                        } else {
                            steps += VectorStep(target.names, ScriptRuntime.ExpressionRunner(assign.value))
                        }
                    }
                    else -> return null
                }
            }
            return InterpretedExpression(steps, extNames)
        }
    }
}
