package work.nekow.particledrawing.api.script

import kotlin.reflect.KProperty
import java.util.function.Consumer

/**
 * 脚本表达式节点：内部持有脚本文本与优先级，供运算符组合成最终脚本。
 *
 * Java 用方法调用（`a.plus(b)`），Kotlin 可用运算符（`a + b`）。
 * 所有运算均返回新 [Expr]，原节点不可变。
 */
@Suppress("unused")
class Expr internal constructor(
    private val code: String,
    private val prec: Int,
) {
    internal fun rawCode(): String = code
    internal fun renderAt(minPrec: Int): String = if (prec < minPrec) "($code)" else code

    operator fun plus(o: Expr): Expr = binary("+", o, PREC_ADD)
    operator fun plus(o: Number): Expr = plus(Expr.num(o))
    operator fun minus(o: Expr): Expr = binary("-", o, PREC_ADD)
    operator fun minus(o: Number): Expr = minus(Expr.num(o))
    operator fun times(o: Expr): Expr = binary("*", o, PREC_MUL)
    operator fun times(o: Number): Expr = times(Expr.num(o))
    operator fun div(o: Expr): Expr = binary("/", o, PREC_MUL)
    operator fun div(o: Number): Expr = div(Expr.num(o))
    fun mod(o: Expr): Expr = binary("%", o, PREC_MUL)
    fun mod(o: Number): Expr = mod(Expr.num(o))
    operator fun rem(o: Expr): Expr = mod(o)
    operator fun rem(o: Number): Expr = mod(o)

    fun lt(o: Expr): Expr = binary("<", o, PREC_CMP)
    fun lt(o: Number): Expr = lt(Expr.num(o))
    fun lte(o: Expr): Expr = binary("<=", o, PREC_CMP)
    fun lte(o: Number): Expr = lte(Expr.num(o))
    fun gt(o: Expr): Expr = binary(">", o, PREC_CMP)
    fun gt(o: Number): Expr = gt(Expr.num(o))
    fun gte(o: Expr): Expr = binary(">=", o, PREC_CMP)
    fun gte(o: Number): Expr = gte(Expr.num(o))
    fun eq(o: Expr): Expr = binary("==", o, PREC_EQ)
    fun eq(o: Number): Expr = eq(Expr.num(o))
    fun neq(o: Expr): Expr = binary("!=", o, PREC_EQ)
    fun neq(o: Number): Expr = neq(Expr.num(o))
    fun and(o: Expr): Expr = binary("&&", o, PREC_AND)
    fun or(o: Expr): Expr = binary("||", o, PREC_OR)
    operator fun not(): Expr = Expr("!${renderAt(PREC_UNARY)}", PREC_UNARY)
    operator fun unaryMinus(): Expr = Expr("-${renderAt(PREC_UNARY)}", PREC_UNARY)

    /** 分量访问：`a.comp("x")` → `a.x`（脚本里 `r/g/b/a` 是颜色别名）。 */
    fun comp(name: String): Expr = Expr("$code.$name", PREC_PRIMARY)

    /** 数组下标：`a.index(i)` → `a[i]`。 */
    fun index(i: Expr): Expr = Expr("$code[${i.rawCode()}]", PREC_PRIMARY)
    fun index(i: Number): Expr = index(Expr.num(i))

    /** 数组/对象方法调用：`a.call("push", x)` → `a.push(x)`。 */
    fun call(name: String, args: List<Expr>): Expr =
        Expr("$code.$name(${args.joinToString(", ") { it.rawCode() }})", PREC_PRIMARY)
    fun call(name: String, vararg args: Expr): Expr = call(name, args.toList())

    private fun binary(op: String, o: Expr, p: Int): Expr {
        val left = renderAt(p)
        val right = if (o.prec <= p) "(${o.code})" else o.code
        return Expr("$left $op $right", p)
    }

    override fun toString(): String = code

    companion object {
        const val PREC_PRIMARY = 100
        const val PREC_UNARY = 90
        const val PREC_MUL = 80
        const val PREC_ADD = 70
        const val PREC_CMP = 60
        const val PREC_EQ = 50
        const val PREC_AND = 40
        const val PREC_OR = 30

        @JvmStatic
        fun num(v: Number): Expr {
            val d = v.toDouble()
            val text = if (d.isFinite() && d == kotlin.math.floor(d) && kotlin.math.abs(d) < 1e15) {
                d.toLong().toString()
            } else {
                d.toString()
            }
            return Expr(text, PREC_PRIMARY)
        }

        /** 变量 / 任意标识符引用（如 `this.position`、`pi`、函数对象变量名）。 */
        @JvmStatic
        fun v(name: String): Expr = Expr(name, PREC_PRIMARY)

        @JvmStatic
        fun callGlobal(name: String, args: List<Expr>): Expr =
            Expr("$name(${args.joinToString(", ") { it.rawCode() }})", PREC_PRIMARY)
    }
}

/** 字面量参与运算时允许写在左侧：`2 * pi`。 */
operator fun Number.plus(e: Expr): Expr = Expr.num(this).plus(e)
operator fun Number.minus(e: Expr): Expr = Expr.num(this).minus(e)
operator fun Number.times(e: Expr): Expr = Expr.num(this).times(e)
operator fun Number.div(e: Expr): Expr = Expr.num(this).div(e)
fun Number.mod(e: Expr): Expr = Expr.num(this).mod(e)
operator fun Number.rem(e: Expr): Expr = Expr.num(this).mod(e)

val Expr.x: Expr get() = comp("x")
val Expr.y: Expr get() = comp("y")
val Expr.z: Expr get() = comp("z")
val Expr.w: Expr get() = comp("w")
val Expr.r: Expr get() = comp("r")
val Expr.g: Expr get() = comp("g")
val Expr.b: Expr get() = comp("b")
val Expr.a: Expr get() = comp("a")

/**
 * 脚本代码生成器核心：把语句与表达式录制成脚本文本。
 *
 * 控制流以方法形式提供：Kotlin 用 `if_/while_/for_`（接收者 lambda），
 * Java 用 `ifBlock/whileBlock/forBlock`（[Consumer]）。任意 DSL 未覆盖的结构可用 [raw] 内嵌。
 */
@Suppress("unused")
abstract class ScriptBuilder<SELF : ScriptBuilder<SELF>> {
    private val lines = ArrayList<String>()

    protected abstract fun newChild(): SELF

    private fun self(): SELF = this as SELF

    fun raw(code: String): SELF {
        lines += code
        return self()
    }

    fun assign(target: Expr, value: Expr): SELF {
        lines += "${target.rawCode()} = ${value.rawCode()};"
        return self()
    }

    fun assign(target: Expr, value: Number): SELF = assign(target, Expr.num(value))

    fun assign(name: String, value: Expr): SELF = assign(Expr.v(name), value)

    fun assign(name: String, value: Number): SELF = assign(name, Expr.num(value))

    fun global(name: String, value: Expr): SELF {
        lines += "global $name = ${value.rawCode()};"
        return self()
    }

    fun global(name: String, value: Number): SELF = global(name, Expr.num(value))

    fun staticVar(name: String, value: Expr): SELF {
        lines += "static $name = ${value.rawCode()};"
        return self()
    }

    fun staticVar(name: String, value: Number): SELF = staticVar(name, Expr.num(value))

    fun exprStmt(expr: Expr): SELF {
        lines += "${expr.rawCode()};"
        return self()
    }

    fun print(vararg args: Expr): SELF {
        lines += "print(${args.joinToString(", ") { it.rawCode() }});"
        return self()
    }

    fun assert(cond: Expr, msg: String): SELF {
        lines += "assert(${cond.rawCode()}, \"${escape(msg)}\");"
        return self()
    }

    /** 脚本局部变量（Kotlin：`var th by numVar()`）。 */
    fun numVar(): NumVarDelegate = NumVarDelegate(this)

    /* =====================================================================
     * 控制流
     * ===================================================================== */

    fun if_(cond: Expr, body: SELF.() -> Unit): SELF {
        val child = newChild().apply(body)
        lines += "if (${cond.rawCode()}) {\n${indent(child.buildInternal())}\n}"
        return self()
    }

    fun if_(cond: Expr, thenBody: SELF.() -> Unit, elseBody: SELF.() -> Unit): SELF {
        val t = newChild().apply(thenBody).buildInternal()
        val e = newChild().apply(elseBody).buildInternal()
        lines += "if (${cond.rawCode()}) {\n${indent(t)}\n} else {\n${indent(e)}\n}"
        return self()
    }

    fun while_(cond: Expr, body: SELF.() -> Unit): SELF {
        val child = newChild().apply(body)
        lines += "while (${cond.rawCode()}) {\n${indent(child.buildInternal())}\n}"
        return self()
    }

    fun doWhile_(body: SELF.() -> Unit, cond: Expr): SELF {
        val child = newChild().apply(body)
        lines += "do {\n${indent(child.buildInternal())}\n} while (${cond.rawCode()});"
        return self()
    }

    /** [init]/[step] 为脚本文本片段，如 `"k = 0"` / `"k = k + 1"`。 */
    fun for_(init: String, cond: Expr, step: String, body: SELF.() -> Unit): SELF {
        val child = newChild().apply(body)
        lines += "for ($init; ${cond.rawCode()}; $step) {\n${indent(child.buildInternal())}\n}"
        return self()
    }

    fun breakStmt(): SELF {
        lines += "break;"
        return self()
    }

    fun continueStmt(): SELF {
        lines += "continue;"
        return self()
    }

    /* Java 友好控制流入口（Kotlin 可用 `if_` 接收者写法）。 */

    fun ifBlock(cond: Expr, body: Consumer<SELF>): SELF = if_(cond) { body.accept(self()) }

    fun ifBlock(cond: Expr, thenBody: Consumer<SELF>, elseBody: Consumer<SELF>): SELF =
        if_(cond, { thenBody.accept(self()) }, { elseBody.accept(self()) })

    fun whileBlock(cond: Expr, body: Consumer<SELF>): SELF = while_(cond) { body.accept(self()) }

    fun doWhileBlock(body: Consumer<SELF>, cond: Expr): SELF = doWhile_({ body.accept(self()) }, cond)

    fun forBlock(init: String, cond: Expr, step: String, body: Consumer<SELF>): SELF =
        for_(init, cond, step) { body.accept(self()) }

    internal fun build(): String = buildInternal()

    private fun buildInternal(): String = lines.joinToString("\n")

    private fun escape(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

    private fun indent(code: String): String = code.lineSequence().joinToString("\n") { "  $it" }

    /* =====================================================================
     * 表达式工厂
     * ===================================================================== */

    fun num(v: Number): Expr = Expr.num(v)
    fun v(name: String): Expr = Expr.v(name)
    val pi: Expr get() = Expr.v("pi")

    /* Java 友好的表达式组合入口（Kotlin 可直接用运算符）。 */
    fun add(a: Expr, b: Expr): Expr = a.plus(b)
    fun sub(a: Expr, b: Expr): Expr = a.minus(b)
    fun mul(a: Expr, b: Expr): Expr = a.times(b)
    fun div(a: Expr, b: Expr): Expr = a.div(b)
    fun lt(a: Expr, b: Expr): Expr = a.lt(b)
    fun lte(a: Expr, b: Expr): Expr = a.lte(b)
    fun gt(a: Expr, b: Expr): Expr = a.gt(b)
    fun gte(a: Expr, b: Expr): Expr = a.gte(b)
    fun eq(a: Expr, b: Expr): Expr = a.eq(b)
    fun neq(a: Expr, b: Expr): Expr = a.neq(b)
    fun and(a: Expr, b: Expr): Expr = a.and(b)
    fun or(a: Expr, b: Expr): Expr = a.or(b)
    fun not(a: Expr): Expr = a.not()
    fun neg(a: Expr): Expr = a.unaryMinus()

    fun vec2(x: Any, y: Any): Expr = call("vec2", x, y)
    fun vec3(x: Any, y: Any, z: Any): Expr = call("vec3", x, y, z)
    fun vec4(x: Any, y: Any, z: Any, w: Any): Expr = call("vec4", x, y, z, w)
    fun mat3(r0: Any, r1: Any, r2: Any): Expr = call("mat3", r0, r1, r2)
    fun mat4(r0: Any, r1: Any, r2: Any, r3: Any): Expr = call("mat4", r0, r1, r2, r3)

    fun array(): Expr = Expr("[]", Expr.PREC_PRIMARY)
    fun array(vararg elements: Any): Expr =
        Expr("[${elements.joinToString(", ") { coerce(it).rawCode() }}]", Expr.PREC_PRIMARY)

    fun translate(v: Any): Expr = call("translate", v)
    fun scale(v: Any): Expr = call("scale", v)
    fun rotate(v: Any, angle: Any): Expr = call("rotate", v, angle)
    fun lookAt(eye: Any, center: Any, up: Any): Expr = call("lookAt", eye, center, up)
    fun rotX(angle: Any): Expr = call("rotX", angle)
    fun rotY(angle: Any): Expr = call("rotY", angle)
    fun rotZ(angle: Any): Expr = call("rotZ", angle)
    fun rotAxis(axis: Any, angle: Any): Expr = call("rotAxis", axis, angle)

    fun dot(a: Any, b: Any): Expr = call("dot", a, b)
    fun cross(a: Any, b: Any): Expr = call("cross", a, b)
    fun len(v: Any): Expr = call("len", v)
    fun len2(v: Any): Expr = call("len2", v)
    fun norm(v: Any): Expr = call("norm", v)
    fun lerp(a: Any, b: Any, t: Any): Expr = call("lerp", a, b, t)
    fun mix(a: Any, b: Any, t: Any): Expr = call("mix", a, b, t)
    fun distance(a: Any, b: Any): Expr = call("distance", a, b)
    fun angleBetween(a: Any, b: Any): Expr = call("angle_between", a, b)
    fun project(v: Any, onto: Any): Expr = call("project", v, onto)
    fun reflect(v: Any, n: Any): Expr = call("reflect", v, n)

    fun sin(x: Any): Expr = call("sin", x)
    fun cos(x: Any): Expr = call("cos", x)
    fun tan(x: Any): Expr = call("tan", x)
    fun asin(x: Any): Expr = call("asin", x)
    fun acos(x: Any): Expr = call("acos", x)
    fun atan(x: Any): Expr = call("atan", x)
    fun atan2(y: Any, x: Any): Expr = call("atan2", y, x)
    fun sqrt(x: Any): Expr = call("sqrt", x)
    fun abs(x: Any): Expr = call("abs", x)
    fun sign(x: Any): Expr = call("sign", x)
    fun exp(x: Any): Expr = call("exp", x)
    fun log(x: Any): Expr = call("log", x)
    fun ln(x: Any): Expr = call("ln", x)
    fun floor(x: Any): Expr = call("floor", x)
    fun ceil(x: Any): Expr = call("ceil", x)
    fun round(x: Any): Expr = call("round", x)
    fun fract(x: Any): Expr = call("fract", x)
    fun pow(x: Any, y: Any): Expr = call("pow", x, y)
    fun min(a: Any, b: Any): Expr = call("min", a, b)
    fun max(a: Any, b: Any): Expr = call("max", a, b)
    fun clamp(x: Any, lo: Any, hi: Any): Expr = call("clamp", x, lo, hi)
    fun step(edge: Any, x: Any): Expr = call("step", edge, x)
    fun smoothstep(edge0: Any, edge1: Any, x: Any): Expr = call("smoothstep", edge0, edge1, x)
    fun mod(x: Any, y: Any): Expr = call("mod", x, y)
    fun mapRange(x: Any, inMin: Any, inMax: Any, outMin: Any, outMax: Any): Expr =
        call("map_range", x, inMin, inMax, outMin, outMax)
    fun remap(x: Any, inMin: Any, inMax: Any, outMin: Any, outMax: Any): Expr =
        call("remap", x, inMin, inMax, outMin, outMax)
    fun int(x: Any): Expr = call("int", x)
    fun float(x: Any): Expr = call("float", x)
    fun bool(x: Any): Expr = call("bool", x)

    fun noise(x: Any): Expr = call("noise", x)
    fun noise(x: Any, y: Any): Expr = call("noise", x, y)
    fun noise(x: Any, y: Any, z: Any): Expr = call("noise", x, y, z)
    fun fbm(x: Any): Expr = call("fbm", x)
    fun fbm(x: Any, y: Any): Expr = call("fbm", x, y)
    fun fbm(x: Any, y: Any, z: Any): Expr = call("fbm", x, y, z)
    fun rand(): Expr = call("rand")
    fun random(): Expr = call("random")

    fun easeLinear(x: Any): Expr = call("ease_linear", x)
    fun easeInOut(x: Any): Expr = call("ease_in_out", x)
    fun easeOutBack(x: Any): Expr = call("ease_out_back", x)
    fun easeInElastic(x: Any): Expr = call("ease_in_elastic", x)

    fun unique(v: Any): Expr = call("unique", v)
    fun reverse(v: Any): Expr = call("reverse", v)
    fun sort(v: Any): Expr = call("sort", v)

    private fun coerce(v: Any): Expr = when (v) {
        is Expr -> v
        is Number -> Expr.num(v)
        else -> throw IllegalArgumentException("脚本表达式参数类型不支持: ${v::class.qualifiedName}")
    }

    private fun call(name: String, vararg args: Any): Expr =
        Expr.callGlobal(name, args.map { coerce(it) })
}

/** `var th by numVar()`：声明脚本局部变量并支持 Kotlin 原生赋值/读取。 */
class NumVarDelegate internal constructor(
    private val sb: ScriptBuilder<*>,
) {
    operator fun getValue(thisRef: Any?, property: KProperty<*>): Expr = Expr.v(property.name)
    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Expr) {
        sb.assign(property.name, value)
    }
}

/** setup 段作用域（对象级，只读 `this.count/time/duration` 与函数变量）。 */
@Suppress("unused")
open class SetupScope : ScriptBuilder<SetupScope>() {
    val count: Expr get() = Expr.v("this.count")
    val time: Expr get() = Expr.v("this.time")
    val duration: Expr get() = Expr.v("this.duration")

    override fun newChild(): SetupScope = SetupScope()
}

/** process 段作用域（粒子级，可读写输出字段）。 */
@Suppress("unused")
open class ProcessScope : ScriptBuilder<ProcessScope>() {
    val index: Expr get() = Expr.v("this.index")
    val count: Expr get() = Expr.v("this.count")
    val time: Expr get() = Expr.v("this.time")
    val delta: Expr get() = Expr.v("this.delta")
    val duration: Expr get() = Expr.v("this.duration")
    val uv: Expr get() = Expr.v("this.uv")

    var position: Expr
        get() = Expr.v("this.position")
        set(value) { assign(Expr.v("this.position"), value) }
    var color: Expr
        get() = Expr.v("this.color")
        set(value) { assign(Expr.v("this.color"), value) }
    var velocity: Expr
        get() = Expr.v("this.velocity")
        set(value) { assign(Expr.v("this.velocity"), value) }
    var scale: Expr
        get() = Expr.v("this.scale")
        set(value) { assign(Expr.v("this.scale"), value) }
    var glow: Expr
        get() = Expr.v("this.glow")
        set(value) { assign(Expr.v("this.glow"), value) }
    var light: Expr
        get() = Expr.v("this.light")
        set(value) { assign(Expr.v("this.light"), value) }
    var life: Expr
        get() = Expr.v("this.life")
        set(value) { assign(Expr.v("this.life"), value) }

    override fun newChild(): ProcessScope = ProcessScope()
}

/** 顶层函数定义段：`func("name", listOf("p")) { ... }`。 */
@Suppress("unused")
class FuncsScope : ScriptBuilder<FuncsScope>() {
    fun func(name: String, params: List<String>, body: FuncBodyScope.() -> Unit): FuncsScope {
        val b = FuncBodyScope().apply(body)
        raw("func $name(${params.joinToString(", ")}) {\n${indent(b.build())}\n}")
        return this
    }

    fun func(name: String, body: FuncBodyScope.() -> Unit): FuncsScope =
        func(name, emptyList(), body)

    override fun newChild(): FuncsScope = FuncsScope()

    private fun indent(code: String): String = code.lineSequence().joinToString("\n") { "  $it" }
}

/** 函数体作用域。 */
@Suppress("unused")
open class FuncBodyScope : ScriptBuilder<FuncBodyScope>() {
    fun return_(value: Expr): FuncBodyScope {
        raw("return ${value.rawCode()};")
        return this
    }

    fun return_(value: Number): FuncBodyScope = return_(Expr.num(value))

    override fun newChild(): FuncBodyScope = FuncBodyScope()
}