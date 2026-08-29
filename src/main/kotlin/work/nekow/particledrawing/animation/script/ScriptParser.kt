package work.nekow.particledrawing.animation.script

import kotlin.math.PI
import kotlin.math.E

/* ------------------------------------------------------------------ */
/* AST                                                                */
/* ------------------------------------------------------------------ */

sealed class Node {
    abstract val line: Int
    abstract val col: Int
}

// ---- 语句 ----

class BlockNode(val body: List<Node>, override val line: Int, override val col: Int) : Node()
class IfNode(val cond: Node, val then: Node, val els: Node?, override val line: Int, override val col: Int) : Node()
class WhileNode(val cond: Node, val body: Node, override val line: Int, override val col: Int) : Node()
class DoNode(val body: Node, val cond: Node, override val line: Int, override val col: Int) : Node()
class ForNode(val init: Node?, val cond: Node?, val inc: Node?, val body: Node, override val line: Int, override val col: Int) : Node()
class BreakNode(override val line: Int, override val col: Int) : Node()
class ContinueNode(override val line: Int, override val col: Int) : Node()
class ReturnNode(val expr: Node?, override val line: Int, override val col: Int) : Node()
class GlobalNode(val name: String, val init: Node?, override val line: Int, override val col: Int) : Node()
class StaticNode(val name: String, val init: Node?, override val line: Int, override val col: Int) : Node()
class ExprStmtNode(val expr: Node, override val line: Int, override val col: Int) : Node()
class AssignNode(val target: AssignTarget, val value: Node, override val line: Int, override val col: Int) : Node()

// ---- 表达式 ----

class NumNode(val value: Double, override val line: Int, override val col: Int) : Node()
class StrNode(val value: String, override val line: Int, override val col: Int) : Node()
class BoolNode(val value: Boolean, override val line: Int, override val col: Int) : Node()
class VarNode(val name: String, override val line: Int, override val col: Int) : Node()
class ArrayNode(val items: List<Node>, override val line: Int, override val col: Int) : Node()
class UnaryNode(val op: String, val operand: Node, override val line: Int, override val col: Int) : Node()
class BinaryNode(val op: String, val left: Node, val right: Node, override val line: Int, override val col: Int) : Node()
class TernaryNode(val cond: Node, val thenExpr: Node, val elseExpr: Node, override val line: Int, override val col: Int) : Node()
class IndexNode(val target: Node, val index: Node, override val line: Int, override val col: Int) : Node()
class CompNode(val target: Node, val comp: String, override val line: Int, override val col: Int) : Node()
class CallNode(val callee: Node, val args: List<Node>, override val line: Int, override val col: Int) : Node()
class MethodNode(val obj: Node, val method: String, val args: List<Node>, override val line: Int, override val col: Int) : Node()

// ---- 赋值目标 ----

sealed class AssignTarget {
    abstract val line: Int
    abstract val col: Int
}
class VarTarget(val name: String, override val line: Int, override val col: Int) : AssignTarget()
class IndexTarget(val target: Node, val index: Node, override val line: Int, override val col: Int) : AssignTarget()
class CompTarget(val target: Node, val comp: String, override val line: Int, override val col: Int) : AssignTarget()
class UnpackTarget(val names: List<String>, override val line: Int, override val col: Int) : AssignTarget()

class FunctionNode(
    val name: String,
    val params: List<String>,
    val body: BlockNode,
    val line: Int,
    val col: Int,
)

class ScriptProgram(
    val setup: List<Node>,
    val process: List<Node>,
    val functions: Map<String, FunctionNode>,
)

/* ------------------------------------------------------------------ */
/* Tokenizer                                                          */
/* ------------------------------------------------------------------ */

enum class TokenType { NUM, STR, IDENT, PUNCT, EOF }

class Token(
    val type: TokenType,
    val text: String,
    val numValue: Double = 0.0,
    val line: Int,
    val col: Int,
)

private fun isDigit(c: Char) = c in '0'..'9'
private fun isIdentStart(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c == '_'
private fun isIdentPart(c: Char) = isIdentStart(c) || isDigit(c)

private val NUMBER_REGEX = Regex("""(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?""")

fun tokenize(sourceIn: String?): List<Token> {
    val src = sourceIn ?: ""
    val tokens = ArrayList<Token>()
    var i = 0
    var line = 1
    var col = 1
    val len = src.length

    fun advance(): Char {
        val c = src[i]
        i++
        if (c == '\n') { line++; col = 1 } else { col++ }
        return c
    }

    while (i < len) {
        val c = src[i]

        if (c == ' ' || c == '\t' || c == '\r' || c == '\n') { advance(); continue }

        if (c == '/' && i + 1 < len && src[i + 1] == '/') {
            while (i < len && src[i] != '\n') advance()
            continue
        }

        if (c == '/' && i + 1 < len && src[i + 1] == '*') {
            val startLine = line
            val startCol = col
            advance(); advance()
            var closed = false
            while (i < len) {
                if (src[i] == '*' && i + 1 < len && src[i + 1] == '/') { advance(); advance(); closed = true; break }
                advance()
            }
            if (!closed) throw ScriptException("unterminated block comment", startLine, startCol)
            continue
        }

        if (isDigit(c) || (c == '.' && i + 1 < len && isDigit(src[i + 1]))) {
            val startLine = line
            val startCol = col
            val m = NUMBER_REGEX.matchAt(src, i)
                ?: throw ScriptException("invalid number", startLine, startCol)
            val text = m.value
            repeat(text.length) { advance() }
            tokens.add(Token(TokenType.NUM, text, text.toDouble(), startLine, startCol))
            continue
        }

        if (c == '"') {
            val startLine = line
            val startCol = col
            advance()
            val out = StringBuilder()
            var closed = false
            while (i < len) {
                val ch = src[i]
                if (ch == '"') { advance(); closed = true; break }
                if (ch == '\\') {
                    advance()
                    if (i >= len) break
                    val esc = src[i]
                    when (esc) {
                        'n' -> { out.append('\n'); advance() }
                        'r' -> { out.append('\r'); advance() }
                        't' -> { out.append('\t'); advance() }
                        '"' -> { out.append('"'); advance() }
                        '\\' -> { out.append('\\'); advance() }
                        else -> { out.append(esc); advance() }
                    }
                    continue
                }
                out.append(ch)
                advance()
            }
            if (!closed) throw ScriptException("unterminated string literal", startLine, startCol)
            tokens.add(Token(TokenType.STR, out.toString(), line = startLine, col = startCol))
            continue
        }

        if (isIdentStart(c)) {
            val startLine = line
            val startCol = col
            val name = StringBuilder()
            while (i < len && isIdentPart(src[i])) name.append(advance())
            val n = name.toString()
            when (n) {
                "pi" -> tokens.add(Token(TokenType.NUM, n, PI, startLine, startCol))
                "e" -> tokens.add(Token(TokenType.NUM, n, E, startLine, startCol))
                else -> tokens.add(Token(TokenType.IDENT, n, line = startLine, col = startCol))
            }
            continue
        }

        if ((c == '=' || c == '!' || c == '<' || c == '>') && i + 1 < len && src[i + 1] == '=') {
            val startLine = line
            val startCol = col
            val op = when (c) {
                '=' -> "=="
                '!' -> "!="
                '<' -> "<="
                else -> ">="
            }
            advance(); advance()
            tokens.add(Token(TokenType.PUNCT, op, line = startLine, col = startCol))
            continue
        }

        if ((c == '&' && i + 1 < len && src[i + 1] == '&') || (c == '|' && i + 1 < len && src[i + 1] == '|')) {
            val startLine = line
            val startCol = col
            val op = if (c == '&') "&&" else "||"
            advance(); advance()
            tokens.add(Token(TokenType.PUNCT, op, line = startLine, col = startCol))
            continue
        }

        if ("+-*/%^!?:=<>()[]{},;.".contains(c)) {
            val startLine = line
            val startCol = col
            advance()
            tokens.add(Token(TokenType.PUNCT, c.toString(), line = startLine, col = startCol))
            continue
        }

        throw ScriptException("unexpected character '$c'", line, col)
    }

    tokens.add(Token(TokenType.EOF, "<eof>", line = line, col = col))
    return tokens
}

/* ------------------------------------------------------------------ */
/* Parser                                                             */
/* ------------------------------------------------------------------ */

private val KEYWORDS = setOf(
    "setup", "process", "func", "return", "if", "else", "while", "do", "for",
    "break", "continue", "global", "static", "true", "false",
)

private val ATTR_NAMES = listOf("x", "y", "z", "r", "g", "b", "a", "vx", "vy", "vz", "sc", "glow", "light")
private val ATTR_SET = ATTR_NAMES.toSet()
private val BUILTIN_NAMES = setOf("i", "idx", "n", "t", "dt", "uv_x", "uv_y", "life")
private val CONSTANT_NAMES = setOf("TAU", "HALF_PI", "QUARTER_PI", "DEG2RAD", "RAD2DEG", "pi", "e")
private val COMP_ALIAS = mapOf("x" to "x", "y" to "y", "z" to "z", "r" to "x", "g" to "y", "b" to "z")
private val COMP_NAMES = setOf("x", "y", "z", "r", "g", "b")

class ScriptParser(private val source: String) {
    private val tokens = tokenize(source)
    private var pos = 0
    private var phase: String? = null
    private var loopDepth = 0

    private fun peek(offset: Int = 0): Token = tokens[minOf(pos + offset, tokens.size - 1)]
    private fun next(): Token {
        val tok = tokens[pos]
        if (tok.type != TokenType.EOF) pos++
        return tok
    }
    private fun check(value: String): Boolean = peek().text == value
    private fun match(value: String): Boolean {
        if (check(value)) { next(); return true }
        return false
    }
    private fun matchKw(kw: String): Boolean {
        val tok = peek()
        if (tok.type == TokenType.IDENT && tok.text == kw) { next(); return true }
        return false
    }
    private fun atEnd(): Boolean = peek().type == TokenType.EOF

    private fun errorAt(tok: Token, msg: String): Nothing =
        throw ScriptException(msg, tok.line, tok.col)

    private fun expect(value: String, what: String? = null): Token {
        val tok = peek()
        if (tok.text != value) {
            throw ScriptException("expected '$value'${if (what != null) " $what" else ""}, got '${tok.text}'", tok.line, tok.col)
        }
        return next()
    }

    private fun expectIdent(): Token {
        val tok = peek()
        if (tok.type != TokenType.IDENT) {
            throw ScriptException("expected identifier, got '${tok.text}'", tok.line, tok.col)
        }
        return next()
    }

    private fun expectKw(kw: String): Token {
        val tok = peek()
        if (tok.type != TokenType.IDENT || tok.text != kw) {
            throw ScriptException("expected '$kw', got '${tok.text}'", tok.line, tok.col)
        }
        return next()
    }

    fun parseProgram(): ScriptProgram {
        val setup = ArrayList<Node>()
        val process = ArrayList<Node>()
        val functions = LinkedHashMap<String, FunctionNode>()

        while (!atEnd()) {
            when {
                matchKw("setup") -> {
                    expect("{")
                    phase = "setup"
                    while (!check("}") && !atEnd()) setup.add(parseStatement())
                    expect("}")
                    phase = null
                }
                matchKw("process") -> {
                    expect("{")
                    phase = "process"
                    while (!check("}") && !atEnd()) process.add(parseStatement())
                    expect("}")
                    phase = null
                }
                matchKw("func") -> {
                    val nameTok = expectIdent()
                    validateFuncName(nameTok)
                    expect("(")
                    val params = parseParamList()
                    expect(")")
                    phase = "func"
                    val body = parseBlock()
                    phase = null
                    if (functions.containsKey(nameTok.text)) {
                        errorAt(nameTok, "duplicate function name '${nameTok.text}'")
                    }
                    functions[nameTok.text] = FunctionNode(nameTok.text, params, body, nameTok.line, nameTok.col)
                }
                else -> {
                    val tok = peek()
                    errorAt(tok, "expected 'setup', 'process' or 'func', got '${tok.text}'")
                }
            }
        }

        return ScriptProgram(setup, process, functions)
    }

    private fun validateFuncName(tok: Token) {
        val name = tok.text
        if (name in KEYWORDS || name in ATTR_SET || name in BUILTIN_NAMES ||
            name in CONSTANT_NAMES || BuiltinRegistry.names.contains(name)
        ) {
            errorAt(tok, "reserved name cannot be used as function name: '$name'")
        }
    }

    private fun parseParamList(): List<String> {
        val params = ArrayList<String>()
        if (!check(")")) {
            val tok = expectIdent()
            validateParamName(tok)
            params.add(tok.text)
            while (match(",")) {
                val t2 = expectIdent()
                validateParamName(t2)
                params.add(t2.text)
            }
        }
        return params
    }

    private fun validateParamName(tok: Token) {
        val name = tok.text
        if (name in KEYWORDS || name in ATTR_SET || name in BUILTIN_NAMES) {
            errorAt(tok, "reserved name cannot be used as parameter: '$name'")
        }
    }

    private fun parseBlock(): BlockNode {
        val open = expect("{")
        val body = ArrayList<Node>()
        while (!check("}") && !atEnd()) body.add(parseStatement())
        expect("}")
        return BlockNode(body, open.line, open.col)
    }

    private fun parseStatement(): Node {
        val tok = peek()

        if (tok.type == TokenType.PUNCT && tok.text == "{") return parseBlock()

        if (tok.type == TokenType.IDENT) {
            when (tok.text) {
                "if" -> return parseIf()
                "while" -> return parseWhile()
                "do" -> return parseDoWhile()
                "for" -> return parseFor()
                "break" -> return parseBreak(tok)
                "continue" -> return parseContinue(tok)
                "return" -> return parseReturn(tok)
                "global" -> return parseGlobal(tok)
                "static" -> return parseStatic(tok)
            }
        }

        return parseAssignOrExprStatement()
    }

    private fun parseIf(): Node {
        val start = next()
        expect("(")
        val cond = parseTernary()
        expect(")")
        val then = parseStatement()
        var els: Node? = null
        if (matchKw("else")) els = parseStatement()
        return IfNode(cond, then, els, start.line, start.col)
    }

    private fun parseWhile(): Node {
        val start = next()
        expect("(")
        val cond = parseTernary()
        expect(")")
        loopDepth++
        val body = parseStatement()
        loopDepth--
        return WhileNode(cond, body, start.line, start.col)
    }

    private fun parseDoWhile(): Node {
        val start = next()
        loopDepth++
        val body = parseStatement()
        loopDepth--
        expectKw("while")
        expect("(")
        val cond = parseTernary()
        expect(")")
        expect(";")
        return DoNode(body, cond, start.line, start.col)
    }

    private fun parseFor(): Node {
        val start = next()
        expect("(")
        var init: Node? = null
        if (!check(";")) init = parseAssignExpr()
        expect(";")
        var cond: Node? = null
        if (!check(";")) cond = parseTernary()
        expect(";")
        var inc: Node? = null
        if (!check(")")) inc = parseAssignExpr()
        expect(")")
        loopDepth++
        val body = parseStatement()
        loopDepth--
        return ForNode(init, cond, inc, body, start.line, start.col)
    }

    private fun parseBreak(tok: Token): Node {
        if (loopDepth == 0) errorAt(tok, "'break' outside loop")
        next()
        expect(";")
        return BreakNode(tok.line, tok.col)
    }

    private fun parseContinue(tok: Token): Node {
        if (loopDepth == 0) errorAt(tok, "'continue' outside loop")
        next()
        expect(";")
        return ContinueNode(tok.line, tok.col)
    }

    private fun parseReturn(tok: Token): Node {
        if (phase != "func") errorAt(tok, "'return' only allowed inside a function")
        next()
        var expr: Node? = null
        if (!check(";")) expr = parseTernary()
        expect(";")
        return ReturnNode(expr, tok.line, tok.col)
    }

    private fun parseGlobal(tok: Token): Node {
        if (phase != "setup") errorAt(tok, "'global' only allowed inside setup")
        next()
        val nameTok = expectIdent()
        validateGlobalStaticName(nameTok)
        var init: Node? = null
        if (match("=")) init = parseTernary()
        expect(";")
        return GlobalNode(nameTok.text, init, tok.line, tok.col)
    }

    private fun parseStatic(tok: Token): Node {
        if (phase != "process") errorAt(tok, "'static' only allowed inside process")
        next()
        val nameTok = expectIdent()
        validateGlobalStaticName(nameTok)
        var init: Node? = null
        if (match("=")) init = parseTernary()
        expect(";")
        return StaticNode(nameTok.text, init, tok.line, tok.col)
    }

    private fun validateGlobalStaticName(tok: Token) {
        val name = tok.text
        if (name in KEYWORDS || name in CONSTANT_NAMES) {
            errorAt(tok, "reserved name cannot be declared: '$name'")
            return
        }
        if (phase == "setup") {
            // setup 只保留 n/t 只读内置量；粒子属性与其余内置名都允许作为变量。
            if (name == "n" || name == "t") {
                errorAt(tok, "reserved name cannot be declared: '$name'")
            }
        } else if (phase == "process") {
            if (name in ATTR_SET || name in BUILTIN_NAMES) {
                errorAt(tok, "reserved name cannot be declared: '$name'")
            }
        }
    }

    private fun parseAssignOrExprStatement(): Node {
        val start = peek()
        val expr = parseAssignExpr()
        if (expr is AssignNode) {
            expect(";")
            return expr
        }
        expect(";")
        if (expr !is CallNode && expr !is MethodNode) {
            errorAt(start, "expression statement must be a function call")
        }
        return ExprStmtNode(expr, start.line, start.col)
    }

    private fun parseAssignExpr(): Node {
        val start = peek()
        val left = parseTernary()
        if (match("=")) {
            val target = toLValue(left, start)
            val value = parseAssignExpr()
            return AssignNode(target, value, start.line, start.col)
        }
        return left
    }

    private fun toLValue(expr: Node, tok: Token): AssignTarget = when (expr) {
        is VarNode -> VarTarget(expr.name, expr.line, expr.col)
        is IndexNode -> IndexTarget(expr.target, expr.index, expr.line, expr.col)
        is CompNode -> CompTarget(expr.target, expr.comp, expr.line, expr.col)
        is ArrayNode -> {
            val names = ArrayList<String>()
            for (item in expr.items) {
                if (item !is VarNode) {
                    throw ScriptException("destructuring assignment names must be identifiers", item.line, item.col)
                }
                names.add(item.name)
            }
            UnpackTarget(names, expr.line, expr.col)
        }
        else -> throw ScriptException("invalid assignment target", tok.line, tok.col)
    }

    private fun parseTernary(): Node {
        val cond = parseOr()
        if (match("?")) {
            val qTok = tokens[pos - 1]
            val thenExpr = parseTernary()
            expect(":")
            val elseExpr = parseTernary()
            return TernaryNode(cond, thenExpr, elseExpr, qTok.line, qTok.col)
        }
        return cond
    }

    private fun parseOr(): Node {
        var left = parseAnd()
        while (match("||")) {
            val opTok = tokens[pos - 1]
            val right = parseAnd()
            left = BinaryNode("||", left, right, opTok.line, opTok.col)
        }
        return left
    }

    private fun parseAnd(): Node {
        var left = parseEquality()
        while (match("&&")) {
            val opTok = tokens[pos - 1]
            val right = parseEquality()
            left = BinaryNode("&&", left, right, opTok.line, opTok.col)
        }
        return left
    }

    private fun parseEquality(): Node {
        var left = parseComparison()
        while (check("==") || check("!=")) {
            val opTok = next()
            val right = parseComparison()
            left = BinaryNode(opTok.text, left, right, opTok.line, opTok.col)
        }
        return left
    }

    private fun parseComparison(): Node {
        var left = parseAdditive()
        while (check("<") || check("<=") || check(">") || check(">=")) {
            val opTok = next()
            val right = parseAdditive()
            left = BinaryNode(opTok.text, left, right, opTok.line, opTok.col)
        }
        return left
    }

    private fun parseAdditive(): Node {
        var left = parseMultiplicative()
        while (check("+") || check("-")) {
            val opTok = next()
            val right = parseMultiplicative()
            left = BinaryNode(opTok.text, left, right, opTok.line, opTok.col)
        }
        return left
    }

    private fun parseMultiplicative(): Node {
        var left = parsePower()
        while (check("*") || check("/") || check("%")) {
            val opTok = next()
            val right = parsePower()
            left = BinaryNode(opTok.text, left, right, opTok.line, opTok.col)
        }
        return left
    }

    private fun parsePower(): Node {
        var left = parseUnary()
        while (match("^")) {
            val opTok = tokens[pos - 1]
            val right = parsePower()
            left = BinaryNode("^", left, right, opTok.line, opTok.col)
        }
        return left
    }

    private fun parseUnary(): Node {
        if (check("-") || check("!")) {
            val opTok = next()
            val operand = parseUnary()
            return UnaryNode(opTok.text, operand, opTok.line, opTok.col)
        }
        return parsePostfix()
    }

    private fun parsePostfix(): Node {
        var expr = parsePrimary()
        while (true) {
            when {
                match("(") -> {
                    val args = parseArgs()
                    expr = CallNode(expr, args, expr.line, expr.col)
                }
                match("[") -> {
                    val idx = parseTernary()
                    expect("]")
                    expr = IndexNode(expr, idx, expr.line, expr.col)
                }
                match(".") -> {
                    val nameTok = expectIdent()
                    if (match("(")) {
                        val args = parseArgs()
                        expr = MethodNode(expr, nameTok.text, args, expr.line, expr.col)
                    } else {
                        if (nameTok.text !in COMP_NAMES) {
                            errorAt(nameTok, "invalid component or method name '.${nameTok.text}'")
                        }
                        expr = CompNode(expr, nameTok.text, expr.line, expr.col)
                    }
                }
                else -> break
            }
        }
        return expr
    }

    private fun parseArgs(): List<Node> {
        val args = ArrayList<Node>()
        if (!check(")")) {
            args.add(parseTernary())
            while (match(",")) args.add(parseTernary())
        }
        expect(")")
        return args
    }

    private fun parsePrimary(): Node {
        val tok = peek()

        if (tok.type == TokenType.NUM) { next(); return NumNode(tok.numValue, tok.line, tok.col) }
        if (tok.type == TokenType.STR) { next(); return StrNode(tok.text, tok.line, tok.col) }

        if (tok.type == TokenType.IDENT) {
            next()
            if (tok.text == "true" || tok.text == "false") {
                return BoolNode(tok.text == "true", tok.line, tok.col)
            }
            return VarNode(tok.text, tok.line, tok.col)
        }

        if (tok.type == TokenType.PUNCT && tok.text == "(") {
            next()
            val expr = parseTernary()
            expect(")")
            return expr
        }

        if (tok.type == TokenType.PUNCT && tok.text == "[") {
            next()
            val items = ArrayList<Node>()
            if (!check("]")) {
                items.add(parseTernary())
                while (match(",")) items.add(parseTernary())
            }
            expect("]")
            return ArrayNode(items, tok.line, tok.col)
        }

        errorAt(tok, "unexpected token '${tok.text}'")
    }
}

/** 解析脚本源码（等价 JS parseProgram）。 */
fun parseProgram(source: String): ScriptProgram = ScriptParser(source).parseProgram()
