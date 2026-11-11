package work.nekow.particledrawing.animation.recipe

import work.nekow.particledrawing.animation.script.*
import work.nekow.particledrawing.api.ParticleRecipe

/** 复用脚本数学表达式，限制为无赋值、无循环的纯求值；每个输出分量独立求值。 */
class RecipeExpressions(recipe: ParticleRecipe) {
    private val values = linkedMapOf<String, Any?>()
    private val ctx = ScriptRuntime.ProcessCtx(0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 0.0, values)
    private val lets = recipe.lets.map { (name, expr) -> name to compile(expr, false) }
    val birth = compileOutputs(recipe.birth, "birth")
    val motion = compileOutputs(recipe.motion, "motion")
    private val where = compile(recipe.where, true)
    private val kill = compile(recipe.kill, true)

    init {
        val names = recipe.variables.keys.toList() + recipe.state.keys.toList() + recipe.lets.keys.toList()
        require(names.size == names.toSet().size) { "variable、state 和 let 不能重名" }
        for (name in names) require(NAME.matches(name) && name !in INPUTS && name !in FUNCTIONS &&
            name !in VECTOR_OUTPUTS && name !in setOf("PI", "E", "this")
        ) {
            "变量名 '$name' 无效或与内建名称冲突"
        }
        require((recipe.variables.values + recipe.state.values).all(Double::isFinite)) { "变量必须是有限数" }
        val outputs = ATTRIBUTES + VECTOR_OUTPUTS.keys + recipe.state.keys
        for (name in recipe.birth.keys + recipe.motion.keys) require(name in outputs) { "不可写属性 '$name'" }
        val known = (INPUTS + recipe.variables.keys + recipe.state.keys).toMutableSet()
        for ((name, expr) in recipe.lets) { validate(expr, known); known += name }
        for (expr in recipe.birth.values + recipe.motion.values + listOf(recipe.where, recipe.kill)) validate(expr, known)
    }

    fun prepare(inputs: Map<String, Double>, variables: Map<String, Double>) {
        values.clear(); values.putAll(inputs); values.putAll(variables)
        for ((name, runner) in lets) values[name] = finiteLet(name, runner.evalAny(ctx))
    }

    fun evaluate(runners: Map<String, ScriptRuntime.ExpressionRunner>): Map<String, Double> =
        runners.mapValues { (_, runner) -> finite(runner.eval(ctx)) }

    fun accepts() = where.eval(ctx) != 0.0
    fun kills() = kill.eval(ctx) != 0.0

    /** 向量写法拆成三个分量；表达式只写一次，分量取值靠 .x/.y/.z。 */
    private fun compileOutputs(entries: Map<String, String>, stage: String): Map<String, ScriptRuntime.ExpressionRunner> {
        val out = linkedMapOf<String, ScriptRuntime.ExpressionRunner>()
        for ((name, expr) in entries) {
            val components = VECTOR_OUTPUTS[name]
            if (components == null) {
                out[name] = compile(expr, name == "glow")
            } else {
                for ((component, accessor) in components) {
                    require(component !in entries) { "$stage 不能同时写 '$name' 和 '$component'" }
                    out[component] = compile("($expr).$accessor", false)
                }
            }
        }
        return out
    }

    private fun compile(expr: String, condition: Boolean): ScriptRuntime.ExpressionRunner {
        require(expr.isNotBlank() && expr.length <= ParticleRecipe.MAX_EXPRESSION) { "公式不能为空，最多 ${ParticleRecipe.MAX_EXPRESSION} 字符" }
        return ScriptRuntime.ExpressionRunner(if (condition) "($expr) ? 1 : 0" else expr)
    }

    private fun finite(value: Double): Double {
        require(value.isFinite()) { "粒子公式产生了非有限数" }
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
        require(ok) { "let '$name' 只能是有限数值、布尔、向量或矩阵" }
        return value as Any
    }

    private fun validate(expr: String, known: Set<String>) {
        fun visit(node: Node) {
            when (node) {
                is NumNode, is BoolNode -> Unit
                is VarNode -> require(node.name in known) { "未知变量 '${node.name}'" }
                is UnaryNode -> visit(node.operand)
                is BinaryNode -> { visit(node.left); visit(node.right) }
                is TernaryNode -> { visit(node.cond); visit(node.thenExpr); visit(node.elseExpr) }
                is CompNode -> visit(node.target)
                is MemberNode -> visit(node.obj)
                is IndexNode -> { visit(node.target); visit(node.index) }
                is ArrayNode -> node.items.forEach(::visit)
                is CallNode -> {
                    val callee = node.callee as? VarNode
                    require(callee != null && callee.name in FUNCTIONS) { "这里只支持数学函数调用" }
                    node.args.forEach(::visit)
                }
                is MethodNode -> { require(node.method in METHODS) { "不支持方法 '${node.method}'" }; visit(node.obj); node.args.forEach(::visit) }
                else -> throw IllegalArgumentException("粒子公式只接受数学表达式")
            }
        }
        visit(parseExpression(expr))
    }

    companion object {
        val ATTRIBUTES = setOf("x", "y", "z", "vx", "vy", "vz", "ax", "ay", "az", "r", "g", "b", "a", "sc", "glow", "light")

        /** 一次写三个分量的写法；键不能和同名标量属性同时出现。 */
        val VECTOR_OUTPUTS = linkedMapOf(
            "position" to listOf("x" to "x", "y" to "y", "z" to "z"),
            "velocity" to listOf("vx" to "x", "vy" to "y", "vz" to "z"),
            "acceleration" to listOf("ax" to "x", "ay" to "y", "az" to "z"),
        )

        /** 可写字段全集，命令补全用。 */
        val OUTPUTS: Set<String> = ATTRIBUTES + VECTOR_OUTPUTS.keys

        val INPUTS = ATTRIBUTES + setOf("i", "n", "u", "t", "age", "life", "progress", "dt", "dx", "dy", "dz", "sx", "sy", "sz")
        private val NAME = Regex("[A-Za-z_][A-Za-z0-9_]{0,47}")

        /** 命令公式允许调用的函数；必须都是运行时内建里真有的（测试钉着，别只改一边）。 */
        val FUNCTIONS: Set<String> = setOf("sin", "cos", "tan", "asin", "acos", "atan", "atan2", "sqrt", "abs", "sign", "exp", "log", "ln", "floor", "ceil", "round", "fract", "pow", "min", "max", "clamp", "map_range", "remap", "int", "float", "bool", "step", "smoothstep", "mod", "noise", "fbm", "rand", "random", "hash", "vec2", "vec3", "vec4", "vec", "mat3", "mat4", "norm", "ease_linear", "ease_in_out", "ease_out_back", "ease_in_elastic")

        /** 命令公式允许的向量/矩阵方法。 */
        val METHODS: Set<String> = setOf("len", "len2", "normalize", "dot", "cross", "dist", "angleTo", "project", "reflect", "lerp", "rotateX", "rotateY", "rotateZ", "translate", "scale")
    }
}
