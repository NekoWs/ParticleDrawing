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

    /** @param o 右操作数 */
    operator fun plus(o: Expr): Expr = binary("+", o, PREC_ADD)
    /** @param o 右操作数 */
    operator fun plus(o: Number): Expr = plus(Expr.num(o))
    /** @param o 右操作数 */
    operator fun minus(o: Expr): Expr = binary("-", o, PREC_ADD)
    /** @param o 右操作数 */
    operator fun minus(o: Number): Expr = minus(Expr.num(o))
    /** @param o 右操作数 */
    operator fun times(o: Expr): Expr = binary("*", o, PREC_MUL)
    /** @param o 右操作数 */
    operator fun times(o: Number): Expr = times(Expr.num(o))
    /** @param o 右操作数 */
    operator fun div(o: Expr): Expr = binary("/", o, PREC_MUL)
    /** @param o 右操作数 */
    operator fun div(o: Number): Expr = div(Expr.num(o))
    /** @param o 右操作数 */
    fun mod(o: Expr): Expr = binary("%", o, PREC_MUL)
    /** @param o 右操作数 */
    fun mod(o: Number): Expr = mod(Expr.num(o))
    /** @param o 右操作数 */
    operator fun rem(o: Expr): Expr = mod(o)
    /** @param o 右操作数 */
    operator fun rem(o: Number): Expr = mod(o)

    /** @param o 右操作数 */
    fun lt(o: Expr): Expr = binary("<", o, PREC_CMP)
    /** @param o 右操作数 */
    fun lt(o: Number): Expr = lt(Expr.num(o))
    /** @param o 右操作数 */
    fun lte(o: Expr): Expr = binary("<=", o, PREC_CMP)
    /** @param o 右操作数 */
    fun lte(o: Number): Expr = lte(Expr.num(o))
    /** @param o 右操作数 */
    fun gt(o: Expr): Expr = binary(">", o, PREC_CMP)
    /** @param o 右操作数 */
    fun gt(o: Number): Expr = gt(Expr.num(o))
    /** @param o 右操作数 */
    fun gte(o: Expr): Expr = binary(">=", o, PREC_CMP)
    /** @param o 右操作数 */
    fun gte(o: Number): Expr = gte(Expr.num(o))
    /** @param o 右操作数 */
    fun eq(o: Expr): Expr = binary("==", o, PREC_EQ)
    /** @param o 右操作数 */
    fun eq(o: Number): Expr = eq(Expr.num(o))
    /** @param o 右操作数 */
    fun neq(o: Expr): Expr = binary("!=", o, PREC_EQ)
    /** @param o 右操作数 */
    fun neq(o: Number): Expr = neq(Expr.num(o))
    /** @param o 右操作数 */
    fun and(o: Expr): Expr = binary("&&", o, PREC_AND)
    /** @param o 右操作数 */
    fun or(o: Expr): Expr = binary("||", o, PREC_OR)
    /** 逻辑取反 */
    operator fun not(): Expr = Expr("!${renderAt(PREC_UNARY)}", PREC_UNARY)
    /** 取负 */
    operator fun unaryMinus(): Expr = Expr("-${renderAt(PREC_UNARY)}", PREC_UNARY)

    /**
     * 分量访问：`a.comp("x")` → `a.x`（脚本里 `r/g/b/a` 是颜色别名）。
     * @param name 分量名
     */
    fun comp(name: String): Expr = Expr("$code.$name", PREC_PRIMARY)

    /**
     * 数组下标：`a.index(i)` → `a[i]`。
     * @param i 下标
     */
    fun index(i: Expr): Expr = Expr("$code[${i.rawCode()}]", PREC_PRIMARY)
    /** @param i 下标 */
    fun index(i: Number): Expr = index(Expr.num(i))

    /**
     * 数组/对象方法调用：`a.call("push", x)` → `a.push(x)`。
     * @param name 方法名
     * @param args 调用参数
     */
    fun call(name: String, args: List<Expr>): Expr =
        Expr("$code.$name(${args.joinToString(", ") { it.rawCode() }})", PREC_PRIMARY)
    /** @param name 方法名 @param args 调用参数 */
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

        /**
         * 创建数值字面量。
         * @param v 数值
         */
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

        /**
         * 变量 / 任意标识符引用（如 `this.position`、`pi`、函数对象变量名）。
         * @param name 标识符名
         */
        @JvmStatic
        fun v(name: String): Expr = Expr(name, PREC_PRIMARY)

        /**
         * 全局函数调用。
         * @param name 函数名
         * @param args 调用参数
         */
        @JvmStatic
        fun callGlobal(name: String, args: List<Expr>): Expr =
            Expr("$name(${args.joinToString(", ") { it.rawCode() }})", PREC_PRIMARY)
    }
}

/** 字面量参与运算时允许写在左侧：`2 * pi`。 @param e 右操作数 */
operator fun Number.plus(e: Expr): Expr = Expr.num(this).plus(e)
/** @param e 右操作数 */
operator fun Number.minus(e: Expr): Expr = Expr.num(this).minus(e)
/** @param e 右操作数 */
operator fun Number.times(e: Expr): Expr = Expr.num(this).times(e)
/** @param e 右操作数 */
operator fun Number.div(e: Expr): Expr = Expr.num(this).div(e)
/** @param e 右操作数 */
fun Number.mod(e: Expr): Expr = Expr.num(this).mod(e)
/** @param e 右操作数 */
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

    /** @param code 内嵌脚本文本 */
    fun raw(code: String): SELF {
        lines += code
        return self()
    }

    /**
     * @param target 赋值目标
     * @param value 赋值来源
     */
    fun assign(target: Expr, value: Expr): SELF {
        lines += "${target.rawCode()} = ${value.rawCode()};"
        return self()
    }

    /** @param target 赋值目标 @param value 数值 */
    fun assign(target: Expr, value: Number): SELF = assign(target, Expr.num(value))

    /** @param name 变量名 @param value 赋值来源 */
    fun assign(name: String, value: Expr): SELF = assign(Expr.v(name), value)

    /** @param name 变量名 @param value 数值 */
    fun assign(name: String, value: Number): SELF = assign(name, Expr.num(value))

    /** @param name 全局变量名 @param value 初始值 */
    fun global(name: String, value: Expr): SELF {
        lines += "global $name = ${value.rawCode()};"
        return self()
    }

    /** @param name 全局变量名 @param value 初始数值 */
    fun global(name: String, value: Number): SELF = global(name, Expr.num(value))

    /** @param name 静态变量名 @param value 初始值 */
    fun staticVar(name: String, value: Expr): SELF {
        lines += "static $name = ${value.rawCode()};"
        return self()
    }

    /** @param name 静态变量名 @param value 初始数值 */
    fun staticVar(name: String, value: Number): SELF = staticVar(name, Expr.num(value))

    /** @param expr 表达式语句 */
    fun exprStmt(expr: Expr): SELF {
        lines += "${expr.rawCode()};"
        return self()
    }

    /** @param args 打印参数 */
    fun print(vararg args: Expr): SELF {
        lines += "print(${args.joinToString(", ") { it.rawCode() }});"
        return self()
    }

    /**
     * @param cond 断言条件
     * @param msg 失败消息
     */
    fun assert(cond: Expr, msg: String): SELF {
        lines += "assert(${cond.rawCode()}, \"${escape(msg)}\");"
        return self()
    }

    /** 脚本局部变量（Kotlin：`var th by numVar()`）。 */
    fun numVar(): NumVarDelegate = NumVarDelegate(this)

    /* =====================================================================
     * 控制流
     * ===================================================================== */

    /** @param cond 条件 @param body 分支体 */
    fun if_(cond: Expr, body: SELF.() -> Unit): SELF {
        val child = newChild().apply(body)
        lines += "if (${cond.rawCode()}) {\n${indent(child.buildInternal())}\n}"
        return self()
    }

    /** @param cond 条件 @param thenBody 真分支体 @param elseBody 假分支体 */
    fun if_(cond: Expr, thenBody: SELF.() -> Unit, elseBody: SELF.() -> Unit): SELF {
        val t = newChild().apply(thenBody).buildInternal()
        val e = newChild().apply(elseBody).buildInternal()
        lines += "if (${cond.rawCode()}) {\n${indent(t)}\n} else {\n${indent(e)}\n}"
        return self()
    }

    /** @param cond 循环条件 @param body 循环体 */
    fun while_(cond: Expr, body: SELF.() -> Unit): SELF {
        val child = newChild().apply(body)
        lines += "while (${cond.rawCode()}) {\n${indent(child.buildInternal())}\n}"
        return self()
    }

    /** @param body 循环体 @param cond 循环条件 */
    fun doWhile_(body: SELF.() -> Unit, cond: Expr): SELF {
        val child = newChild().apply(body)
        lines += "do {\n${indent(child.buildInternal())}\n} while (${cond.rawCode()});"
        return self()
    }

    /**
     * [init]/[step] 为脚本文本片段，如 `"k = 0"` / `"k = k + 1"`。
     * @param init 初始化语句
     * @param cond 循环条件
     * @param step 步进语句
     * @param body 循环体
     */
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

    /** @param cond 条件 @param body 分支体 */
    fun ifBlock(cond: Expr, body: Consumer<SELF>): SELF = if_(cond) { body.accept(self()) }

    /** @param cond 条件 @param thenBody 真分支体 @param elseBody 假分支体 */
    fun ifBlock(cond: Expr, thenBody: Consumer<SELF>, elseBody: Consumer<SELF>): SELF =
        if_(cond, { thenBody.accept(self()) }, { elseBody.accept(self()) })

    /** @param cond 循环条件 @param body 循环体 */
    fun whileBlock(cond: Expr, body: Consumer<SELF>): SELF = while_(cond) { body.accept(self()) }

    /** @param body 循环体 @param cond 循环条件 */
    fun doWhileBlock(body: Consumer<SELF>, cond: Expr): SELF = doWhile_({ body.accept(self()) }, cond)

    /** @param init 初始化语句 @param cond 循环条件 @param step 步进语句 @param body 循环体 */
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

    /** @param v 数值 */
    fun num(v: Number): Expr = Expr.num(v)
    /** @param name 标识符名 */
    fun v(name: String): Expr = Expr.v(name)
    /** 圆周率常量 */
    val pi: Expr get() = Expr.v("pi")

    /* Java 友好的表达式组合入口（Kotlin 可直接用运算符）。 */
    /** @param a 左操作数 @param b 右操作数 */
    fun add(a: Expr, b: Expr): Expr = a.plus(b)
    /** @param a 左操作数 @param b 右操作数 */
    fun sub(a: Expr, b: Expr): Expr = a.minus(b)
    /** @param a 左操作数 @param b 右操作数 */
    fun mul(a: Expr, b: Expr): Expr = a.times(b)
    /** @param a 左操作数 @param b 右操作数 */
    fun div(a: Expr, b: Expr): Expr = a.div(b)
    /** @param a 左操作数 @param b 右操作数 */
    fun lt(a: Expr, b: Expr): Expr = a.lt(b)
    /** @param a 左操作数 @param b 右操作数 */
    fun lte(a: Expr, b: Expr): Expr = a.lte(b)
    /** @param a 左操作数 @param b 右操作数 */
    fun gt(a: Expr, b: Expr): Expr = a.gt(b)
    /** @param a 左操作数 @param b 右操作数 */
    fun gte(a: Expr, b: Expr): Expr = a.gte(b)
    /** @param a 左操作数 @param b 右操作数 */
    fun eq(a: Expr, b: Expr): Expr = a.eq(b)
    /** @param a 左操作数 @param b 右操作数 */
    fun neq(a: Expr, b: Expr): Expr = a.neq(b)
    /** @param a 左操作数 @param b 右操作数 */
    fun and(a: Expr, b: Expr): Expr = a.and(b)
    /** @param a 左操作数 @param b 右操作数 */
    fun or(a: Expr, b: Expr): Expr = a.or(b)
    /** @param a 操作数 */
    fun not(a: Expr): Expr = a.not()
    /** @param a 操作数 */
    fun neg(a: Expr): Expr = a.unaryMinus()

    /** @param x 分量一 @param y 分量二 */
    fun vec2(x: Any, y: Any): Expr = call("vec2", x, y)
    /** @param x 分量一 @param y 分量二 @param z 分量三 */
    fun vec3(x: Any, y: Any, z: Any): Expr = call("vec3", x, y, z)
    /** @param x 分量一 @param y 分量二 @param z 分量三 @param w 分量四 */
    fun vec4(x: Any, y: Any, z: Any, w: Any): Expr = call("vec4", x, y, z, w)
    /** @param r0 第零行 @param r1 第一行 @param r2 第二行 */
    fun mat3(r0: Any, r1: Any, r2: Any): Expr = call("mat3", r0, r1, r2)
    /** @param r0 第零行 @param r1 第一行 @param r2 第二行 @param r3 第三行 */
    fun mat4(r0: Any, r1: Any, r2: Any, r3: Any): Expr = call("mat4", r0, r1, r2, r3)

    /** 空数组 */
    fun array(): Expr = Expr("[]", Expr.PREC_PRIMARY)
    /** @param elements 数组元素 */
    fun array(vararg elements: Any): Expr =
        Expr("[${elements.joinToString(", ") { coerce(it).rawCode() }}]", Expr.PREC_PRIMARY)

    /** @param v 输入向量 */
    fun translate(v: Any): Expr = call("translate", v)
    /** @param v 输入向量 */
    fun scale(v: Any): Expr = call("scale", v)
    /** @param v 输入向量 @param angle 旋转角 */
    fun rotate(v: Any, angle: Any): Expr = call("rotate", v, angle)
    /** @param eye 视点 @param center 目标点 @param up 上方向 */
    fun lookAt(eye: Any, center: Any, up: Any): Expr = call("lookAt", eye, center, up)
    /** @param angle 旋转角 */
    fun rotX(angle: Any): Expr = call("rotX", angle)
    /** @param angle 旋转角 */
    fun rotY(angle: Any): Expr = call("rotY", angle)
    /** @param angle 旋转角 */
    fun rotZ(angle: Any): Expr = call("rotZ", angle)
    /** @param axis 旋转轴 @param angle 旋转角 */
    fun rotAxis(axis: Any, angle: Any): Expr = call("rotAxis", axis, angle)

    /** @param a 向量一 @param b 向量二 */
    fun dot(a: Any, b: Any): Expr = call("dot", a, b)
    /** @param a 向量一 @param b 向量二 */
    fun cross(a: Any, b: Any): Expr = call("cross", a, b)
    /** @param v 输入向量 */
    fun len(v: Any): Expr = call("len", v)
    /** @param v 输入向量 */
    fun len2(v: Any): Expr = call("len2", v)
    /** @param v 输入向量 */
    fun norm(v: Any): Expr = call("norm", v)
    /** @param a 起点 @param b 终点 @param t 插值进度 */
    fun lerp(a: Any, b: Any, t: Any): Expr = call("lerp", a, b, t)
    /** @param a 起点 @param b 终点 @param t 插值进度 */
    fun mix(a: Any, b: Any, t: Any): Expr = call("mix", a, b, t)
    /** @param a 向量一 @param b 向量二 */
    fun distance(a: Any, b: Any): Expr = call("distance", a, b)
    /** @param a 向量一 @param b 向量二 */
    fun angleBetween(a: Any, b: Any): Expr = call("angle_between", a, b)
    /** @param v 输入向量 @param onto 投影目标 */
    fun project(v: Any, onto: Any): Expr = call("project", v, onto)
    /** @param v 输入向量 @param n 法线 */
    fun reflect(v: Any, n: Any): Expr = call("reflect", v, n)

    /** @param x 输入 */
    fun sin(x: Any): Expr = call("sin", x)
    /** @param x 输入 */
    fun cos(x: Any): Expr = call("cos", x)
    /** @param x 输入 */
    fun tan(x: Any): Expr = call("tan", x)
    /** @param x 输入 */
    fun asin(x: Any): Expr = call("asin", x)
    /** @param x 输入 */
    fun acos(x: Any): Expr = call("acos", x)
    /** @param x 输入 */
    fun atan(x: Any): Expr = call("atan", x)
    /** @param y 纵坐标 @param x 横坐标 */
    fun atan2(y: Any, x: Any): Expr = call("atan2", y, x)
    /** @param x 输入 */
    fun sqrt(x: Any): Expr = call("sqrt", x)
    /** @param x 输入 */
    fun abs(x: Any): Expr = call("abs", x)
    /** @param x 输入 */
    fun sign(x: Any): Expr = call("sign", x)
    /** @param x 输入 */
    fun exp(x: Any): Expr = call("exp", x)
    /** @param x 输入 */
    fun log(x: Any): Expr = call("log", x)
    /** @param x 输入 */
    fun ln(x: Any): Expr = call("ln", x)
    /** @param x 输入 */
    fun floor(x: Any): Expr = call("floor", x)
    /** @param x 输入 */
    fun ceil(x: Any): Expr = call("ceil", x)
    /** @param x 输入 */
    fun round(x: Any): Expr = call("round", x)
    /** @param x 输入 */
    fun fract(x: Any): Expr = call("fract", x)
    /** @param x 底数 @param y 指数 */
    fun pow(x: Any, y: Any): Expr = call("pow", x, y)
    /** @param a 数值一 @param b 数值二 */
    fun min(a: Any, b: Any): Expr = call("min", a, b)
    /** @param a 数值一 @param b 数值二 */
    fun max(a: Any, b: Any): Expr = call("max", a, b)
    /** @param x 输入 @param lo 下限 @param hi 上限 */
    fun clamp(x: Any, lo: Any, hi: Any): Expr = call("clamp", x, lo, hi)
    /** @param edge 阈值 @param x 输入 */
    fun step(edge: Any, x: Any): Expr = call("step", edge, x)
    /** @param edge0 下阈值 @param edge1 上阈值 @param x 输入 */
    fun smoothstep(edge0: Any, edge1: Any, x: Any): Expr = call("smoothstep", edge0, edge1, x)
    /** @param x 被除数 @param y 除数 */
    fun mod(x: Any, y: Any): Expr = call("mod", x, y)
    /** @param x 输入 @param inMin 输入下限 @param inMax 输入上限 @param outMin 输出下限 @param outMax 输出上限 */
    fun mapRange(x: Any, inMin: Any, inMax: Any, outMin: Any, outMax: Any): Expr =
        call("map_range", x, inMin, inMax, outMin, outMax)
    /** @param x 输入 @param inMin 输入下限 @param inMax 输入上限 @param outMin 输出下限 @param outMax 输出上限 */
    fun remap(x: Any, inMin: Any, inMax: Any, outMin: Any, outMax: Any): Expr =
        call("remap", x, inMin, inMax, outMin, outMax)
    /** @param x 输入 */
    fun int(x: Any): Expr = call("int", x)
    /** @param x 输入 */
    fun float(x: Any): Expr = call("float", x)
    /** @param x 输入 */
    fun bool(x: Any): Expr = call("bool", x)

    /** @param x 坐标 */
    fun noise(x: Any): Expr = call("noise", x)
    /** @param x 坐标一 @param y 坐标二 */
    fun noise(x: Any, y: Any): Expr = call("noise", x, y)
    /** @param x 坐标一 @param y 坐标二 @param z 坐标三 */
    fun noise(x: Any, y: Any, z: Any): Expr = call("noise", x, y, z)
    /** @param x 坐标 */
    fun fbm(x: Any): Expr = call("fbm", x)
    /** @param x 坐标一 @param y 坐标二 */
    fun fbm(x: Any, y: Any): Expr = call("fbm", x, y)
    /** @param x 坐标一 @param y 坐标二 @param z 坐标三 */
    fun fbm(x: Any, y: Any, z: Any): Expr = call("fbm", x, y, z)
    /** 确定性随机 */
    fun rand(): Expr = call("rand")
    /** 非确定随机 */
    fun random(): Expr = call("random")

    /** @param x 进度 */
    fun easeLinear(x: Any): Expr = call("ease_linear", x)
    /** @param x 进度 */
    fun easeInOut(x: Any): Expr = call("ease_in_out", x)
    /** @param x 进度 */
    fun easeOutBack(x: Any): Expr = call("ease_out_back", x)
    /** @param x 进度 */
    fun easeInElastic(x: Any): Expr = call("ease_in_elastic", x)

    /** @param v 输入数组 */
    fun unique(v: Any): Expr = call("unique", v)
    /** @param v 输入数组 */
    fun reverse(v: Any): Expr = call("reverse", v)
    /** @param v 输入数组 */
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
    /**
     * @param name 函数名
     * @param params 参数名列表
     * @param body 函数体
     */
    fun func(name: String, params: List<String>, body: FuncBodyScope.() -> Unit): FuncsScope {
        val b = FuncBodyScope().apply(body)
        raw("func $name(${params.joinToString(", ")}) {\n${indent(b.build())}\n}")
        return this
    }

    /** @param name 函数名 @param body 函数体 */
    fun func(name: String, body: FuncBodyScope.() -> Unit): FuncsScope =
        func(name, emptyList(), body)

    override fun newChild(): FuncsScope = FuncsScope()

    private fun indent(code: String): String = code.lineSequence().joinToString("\n") { "  $it" }
}

/** 函数体作用域。 */
@Suppress("unused")
open class FuncBodyScope : ScriptBuilder<FuncBodyScope>() {
    /** @param value 返回值 */
    fun return_(value: Expr): FuncBodyScope {
        raw("return ${value.rawCode()};")
        return this
    }

    /** @param value 返回数值 */
    fun return_(value: Number): FuncBodyScope = return_(Expr.num(value))

    override fun newChild(): FuncBodyScope = FuncBodyScope()
}