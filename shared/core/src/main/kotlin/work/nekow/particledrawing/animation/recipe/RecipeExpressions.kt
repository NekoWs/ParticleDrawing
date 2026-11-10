package work.nekow.particledrawing.animation.recipe

import work.nekow.particledrawing.animation.script.*
import work.nekow.particledrawing.api.ParticleRecipe

/** 复用脚本数学表达式，限制为无赋值、无循环的纯求值；每个输出分量独立求值。 */
class RecipeExpressions(recipe: ParticleRecipe) {
    private val values = linkedMapOf<String, Double>()
    private val ctx = ScriptRuntime.ProcessCtx(0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 0.0, values)
    private val lets = recipe.lets.map { (name, expr) -> name to compile(expr, false) }
    val birth = recipe.birth.map { (name, expr) -> name to compile(expr, name == "glow") }
    val motion = recipe.motion.map { (name, expr) -> name to compile(expr, name == "glow") }
    private val where = compile(recipe.where, true)
    private val kill = compile(recipe.kill, true)

    init {
        val names = recipe.variables.keys.toList() + recipe.state.keys.toList() + recipe.lets.keys.toList()
        require(names.size == names.toSet().size) { "variable、state 和 let 不能重名" }
        for (name in names) require(NAME.matches(name) && name !in INPUTS && name !in FUNCTIONS && name !in setOf("PI", "E", "this")) {
            "变量名 '$name' 无效或与内建名称冲突"
        }
        require((recipe.variables.values + recipe.state.values).all(Double::isFinite)) { "变量必须是有限数" }
        val outputs = ATTRIBUTES + recipe.state.keys
        for (name in recipe.birth.keys + recipe.motion.keys) require(name in outputs) { "不可写属性 '$name'" }
        val known = (INPUTS + recipe.variables.keys + recipe.state.keys).toMutableSet()
        for ((name, expr) in recipe.lets) { validate(expr, known); known += name }
        for (expr in recipe.birth.values + recipe.motion.values + listOf(recipe.where, recipe.kill)) validate(expr, known)
    }

    fun prepare(inputs: Map<String, Double>, variables: Map<String, Double>) {
        values.clear(); values.putAll(inputs); values.putAll(variables)
        for ((name, runner) in lets) values[name] = finite(runner.eval(ctx))
    }

    fun evaluate(runners: List<Pair<String, ScriptRuntime.ExpressionRunner>>): Map<String, Double> =
        runners.associate { (name, runner) -> name to finite(runner.eval(ctx)) }

    fun accepts() = where.eval(ctx) != 0.0
    fun kills() = kill.eval(ctx) != 0.0

    private fun compile(expr: String, condition: Boolean): ScriptRuntime.ExpressionRunner {
        require(expr.isNotBlank() && expr.length <= ParticleRecipe.MAX_EXPRESSION) { "公式不能为空，最多 ${ParticleRecipe.MAX_EXPRESSION} 字符" }
        return ScriptRuntime.ExpressionRunner(if (condition) "($expr) ? 1 : 0" else expr)
    }

    private fun finite(value: Double): Double {
        require(value.isFinite()) { "粒子公式产生了非有限数" }
        return value
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
        val INPUTS = ATTRIBUTES + setOf("i", "n", "u", "t", "age", "life", "progress", "dt", "dx", "dy", "dz")
        private val NAME = Regex("[A-Za-z_][A-Za-z0-9_]{0,47}")
        private val FUNCTIONS = setOf("sin", "cos", "tan", "asin", "acos", "atan", "atan2", "sqrt", "abs", "sign", "exp", "log", "ln", "floor", "ceil", "round", "fract", "pow", "min", "max", "clamp", "map_range", "remap", "int", "float", "bool", "step", "smoothstep", "mod", "noise", "fbm", "rand", "random", "hash", "vec2", "vec3", "vec4", "vec", "mat3", "mat4", "norm", "ease_linear", "ease_in_out", "ease_out_back", "ease_in_elastic")
        private val METHODS = setOf("len", "len2", "normalize", "dot", "cross", "dist", "angleTo", "project", "reflect", "lerp", "rotateX", "rotateY", "rotateZ", "translate", "scale")
    }
}
