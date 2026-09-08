package work.nekow.particledrawing.animation.script

import kotlin.math.PI
import kotlin.math.E

// —— AST ——

sealed class Node {
    abstract val line: Int
    abstract val col: Int
}

// —— 语句 ——

class BlockNode(val body: List<Node>, override val line: Int, override val col: Int) : Node()
class IfNode(val cond: Node, val then: Node, val els: Node?, override val line: Int, override val col: Int) : Node()
class WhileNode(val cond: Node, val body: Node, override val line: Int, override val col: Int) : Node()
class DoNode(val body: Node, val cond: Node, override val line: Int, override val col: Int) : Node()
class ForNode(val init: Node?, val cond: Node?, val inc: Node?, val body: Node, override val line: Int, override val col: Int) : Node()
class ForOfNode(val name: String, val kind: String, val iter: Node, val body: Node, override val line: Int, override val col: Int) : Node()
class BreakNode(override val line: Int, override val col: Int) : Node()
class ContinueNode(override val line: Int, override val col: Int) : Node()
class ReturnNode(val expr: Node?, override val line: Int, override val col: Int) : Node()
class GlobalNode(val name: String, val init: Node?, override val line: Int, override val col: Int) : Node()
class ExprStmtNode(val expr: Node, override val line: Int, override val col: Int) : Node()
class AssignNode(val target: AssignTarget, val value: Node, override val line: Int, override val col: Int) : Node()
class Declarator(val name: String, val init: Node?, val line: Int, val col: Int)
class DeclareNode(val kind: String, val decls: List<Declarator>, override val line: Int, override val col: Int) : Node()

// —— 表达式 ——

class NumNode(val value: Double, override val line: Int, override val col: Int) : Node()
class StrNode(val value: String, override val line: Int, override val col: Int) : Node()
class BoolNode(val value: Boolean, override val line: Int, override val col: Int) : Node()
class UndefinedNode(override val line: Int, override val col: Int) : Node()
class VarNode(val name: String, override val line: Int, override val col: Int) : Node()
class ArrayNode(val items: List<Node>, override val line: Int, override val col: Int) : Node()
class UnaryNode(val op: String, val operand: Node, override val line: Int, override val col: Int) : Node()
class BinaryNode(val op: String, val left: Node, val right: Node, override val line: Int, override val col: Int) : Node()
class TernaryNode(val cond: Node, val thenExpr: Node, val elseExpr: Node, override val line: Int, override val col: Int) : Node()
class IndexNode(val target: Node, val index: Node, override val line: Int, override val col: Int) : Node()
class CompNode(val target: Node, val comp: String, override val line: Int, override val col: Int) : Node()
class MemberNode(val obj: Node, val field: String, override val line: Int, override val col: Int) : Node()
class CallNode(val callee: Node, val args: List<Node>, override val line: Int, override val col: Int) : Node()
class MethodNode(val obj: Node, val method: String, val args: List<Node>, override val line: Int, override val col: Int) : Node()
class PreIncNode(val op: String, val target: AssignTarget, override val line: Int, override val col: Int) : Node()
class PostIncNode(val op: String, val target: AssignTarget, override val line: Int, override val col: Int) : Node()

// —— 赋值目标 ——

sealed class AssignTarget {
    abstract val line: Int
    abstract val col: Int
}
class VarTarget(val name: String, override val line: Int, override val col: Int) : AssignTarget()
class IndexTarget(val target: Node, val index: Node, override val line: Int, override val col: Int) : AssignTarget()
class CompTarget(val target: Node, val comp: String, override val line: Int, override val col: Int) : AssignTarget()
class MemberTarget(val obj: Node, val field: String, override val line: Int, override val col: Int) : AssignTarget()
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
    val tick: List<Node>,
    val process: List<Node>,
    val functions: Map<String, FunctionNode>,
    val globals: List<DeclareNode> = emptyList(),
)

// —— Tokenizer ——

enum class TokenType { NUM, STR, IDENT, PUNCT, EOF }

class Token(
    val type: TokenType,
    val text: String,
    val numValue: Double = 0.0,
    val line: Int,
    val col: Int,
) {
    var nl: Boolean = false
}

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
    var nlSeen = false
    val len = src.length

    fun advance(): Char {
        val c = src[i]
        i++
        if (c == '\n') { line++; col = 1; nlSeen = true } else { col++ }
        return c
    }

    // 每个 token 记录「前一个 token 之后是否出现过换行」，供解析器做换行断句判定。
    fun emit(t: Token) {
        t.nl = nlSeen
        nlSeen = false
        tokens.add(t)
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
            emit(Token(TokenType.NUM, text, text.toDouble(), startLine, startCol))
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
            emit(Token(TokenType.STR, out.toString(), line = startLine, col = startCol))
            continue
        }

        if (isIdentStart(c)) {
            val startLine = line
            val startCol = col
            val name = StringBuilder()
            while (i < len && isIdentPart(src[i])) name.append(advance())
            val n = name.toString()
            when (n) {
                "PI" -> emit(Token(TokenType.NUM, n, PI, startLine, startCol))
                "E" -> emit(Token(TokenType.NUM, n, E, startLine, startCol))
                else -> emit(Token(TokenType.IDENT, n, line = startLine, col = startCol))
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
            emit(Token(TokenType.PUNCT, op, line = startLine, col = startCol))
            continue
        }

        if ((c == '&' && i + 1 < len && src[i + 1] == '&') || (c == '|' && i + 1 < len && src[i + 1] == '|')) {
            val startLine = line
            val startCol = col
            val op = if (c == '&') "&&" else "||"
            advance(); advance()
            emit(Token(TokenType.PUNCT, op, line = startLine, col = startCol))
            continue
        }

        if ((c == '+' || c == '-') && i + 1 < len && src[i + 1] == c) {
            val startLine = line
            val startCol = col
            val op = if (c == '+') "++" else "--"
            advance(); advance()
            emit(Token(TokenType.PUNCT, op, line = startLine, col = startCol))
            continue
        }

        // 复合赋值运算符：+= -= *= /= %= ^=
        if (c in "+-*/%^" && i + 1 < len && src[i + 1] == '=') {
            val startLine = line
            val startCol = col
            val op = "$c="
            advance(); advance()
            emit(Token(TokenType.PUNCT, op, line = startLine, col = startCol))
            continue
        }

        if ("+-*/%^!?:=<>()[]{},;.".contains(c)) {
            val startLine = line
            val startCol = col
            advance()
            emit(Token(TokenType.PUNCT, c.toString(), line = startLine, col = startCol))
            continue
        }

        throw ScriptException("unexpected character '$c'", line, col)
    }

    emit(Token(TokenType.EOF, "<eof>", line = line, col = col))
    return tokens
}

// —— Parser ——

private val KEYWORDS = setOf(
    "setup", "process", "tick", "func", "return", "if", "else", "while", "do", "for",
    "of", "const", "let", "undefined", "break", "continue", "true", "false",
)

private val LIFECYCLE_FUNCS = setOf("setup", "tick", "process")
private val CONSTANT_NAMES = setOf("TAU", "HALF_PI", "QUARTER_PI", "DEG2RAD", "RAD2DEG", "PI", "E")
private val COMP_ALIAS = mapOf("x" to "x", "y" to "y", "z" to "z", "w" to "w", "r" to "x", "g" to "y", "b" to "z", "a" to "w")
private val COMP_NAMES = setOf("x", "y", "z", "w", "r", "g", "b", "a")

// 复合赋值运算符 → 对应的二元运算符。
private val COMPOUND_ASSIGN = mapOf("+=" to "+", "-=" to "-", "*=" to "*", "/=" to "/", "%=" to "%", "^=" to "^")

private const val CTX_NAME = "this"

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

    // 当前待消费 token 之前是否有换行：行首运算符不续接上一行，语句在换行处结束。
    private fun nlBefore(): Boolean = peek().nl

    // 语句结尾：`;`、换行、`}` 或 EOF 均可结束语句；`;` 用于同行写多条语句。
    private fun statementEnd() {
        val tok = peek()
        if (tok.type == TokenType.EOF || tok.text == ";" || tok.text == "}" || tok.nl) {
            if (tok.text == ";") next()
            return
        }
        throw ScriptException("expected ';' or newline after statement, got '${tok.text}'", tok.line, tok.col)
    }

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

    fun parseBareExpression(): Node {
        val node = parseTernary()
        val extra = peek()
        if (extra.type != TokenType.EOF) {
            throw ScriptException("unexpected '${extra.text}' after expression", extra.line, extra.col)
        }
        return node
    }

    fun parseProgram(): ScriptProgram {
        val setup = ArrayList<Node>()
        val tick = ArrayList<Node>()
        val process = ArrayList<Node>()
        val functions = LinkedHashMap<String, FunctionNode>()
        val globals = ArrayList<DeclareNode>()

        while (!atEnd()) {
            val tok = peek()
            if (tok.type == TokenType.IDENT && (tok.text == "let" || tok.text == "const")) {
                globals.add(parseDeclare(tok, tok.text))
                continue
            }

            expectKw("func")
            val nameTok = expectIdent()
            expect("(")
            val params = parseParamList()
            expect(")")
            phase = if (nameTok.text in LIFECYCLE_FUNCS) nameTok.text else "func"
            val body = parseBlock()
            phase = null

            if (nameTok.text in LIFECYCLE_FUNCS) {
                validateLifecycleSignature(nameTok, params)
                when (nameTok.text) {
                    "setup" -> setup.addAll(body.body)
                    "tick" -> tick.addAll(body.body)
                    "process" -> process.addAll(body.body)
                }
            } else {
                validateFuncName(nameTok)
                if (functions.containsKey(nameTok.text)) {
                    errorAt(nameTok, "duplicate function name '${nameTok.text}'")
                }
                functions[nameTok.text] = FunctionNode(nameTok.text, params, body, nameTok.line, nameTok.col)
            }
        }

        return ScriptProgram(setup, tick, process, functions, globals)
    }

    private fun validateLifecycleSignature(tok: Token, params: List<String>) {
        when (tok.text) {
            "setup", "tick" -> if (params.isNotEmpty()) {
                errorAt(tok, "'${tok.text}' must not take parameters")
            }
            "process" -> if (params.isNotEmpty()) {
                errorAt(tok, "'process' must not take parameters")
            }
        }
    }

    private fun validateFuncName(tok: Token) {
        val name = tok.text
        if (name in KEYWORDS || name in LIFECYCLE_FUNCS || name == CTX_NAME || name in CONSTANT_NAMES || BuiltinRegistry.names.contains(name)) {
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
        if (name in KEYWORDS || name == CTX_NAME || name in CONSTANT_NAMES) {
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
                "let" -> return parseDeclare(tok, "let")
                "const" -> return parseDeclare(tok, "const")
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
        statementEnd()
        return DoNode(body, cond, start.line, start.col)
    }

    private fun parseFor(): Node {
        val start = next()
        expect("(")

        // for-of：for (const x of expr) / for (let x of expr) / for (x of expr)
        val saved = pos
        var ofKind: String? = null
        fun looksLikeOf(): Boolean = peek().type == TokenType.IDENT && peek(1).type == TokenType.IDENT && peek(1).text == "of"
        if (matchKw("const")) {
            ofKind = if (looksLikeOf()) "const" else null
            if (ofKind == null) pos = saved
        } else if (matchKw("let")) {
            ofKind = if (looksLikeOf()) "let" else null
            if (ofKind == null) pos = saved
        } else if (looksLikeOf()) {
            ofKind = "let"
        }
        if (ofKind != null) {
            val nameTok = expectIdent()
            validateForVarName(nameTok)
            expectKw("of")
            val iter = parseTernary()
            expect(")")
            loopDepth++
            val body = parseStatement()
            loopDepth--
            return ForOfNode(nameTok.text, ofKind, iter, body, start.line, start.col)
        }
        pos = saved

        var init: Node? = null
        if (!check(";")) {
            if (check("let")) init = parseDeclare(peek(), "let", true)
            else init = parseAssignExpr()
        }
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

    private fun validateForVarName(tok: Token) {
        if (tok.text in KEYWORDS || tok.text == CTX_NAME || tok.text in CONSTANT_NAMES) {
            errorAt(tok, "reserved name cannot be used as loop variable: '${tok.text}'")
        }
    }

    private fun parseBreak(tok: Token): Node {
        if (loopDepth == 0) errorAt(tok, "'break' outside loop")
        next()
        statementEnd()
        return BreakNode(tok.line, tok.col)
    }

    private fun parseContinue(tok: Token): Node {
        if (loopDepth == 0) errorAt(tok, "'continue' outside loop")
        next()
        statementEnd()
        return ContinueNode(tok.line, tok.col)
    }

    private fun parseReturn(tok: Token): Node {
        if (phase == null) errorAt(tok, "'return' only allowed inside a function")
        next()
        var expr: Node? = null
        if (!check(";") && !atEnd() && !nlBefore()) expr = parseTernary()
        statementEnd()
        return ReturnNode(expr, tok.line, tok.col)
    }

    private fun parseDeclare(tok: Token, kind: String, noStatementEnd: Boolean = false): DeclareNode {
        next() // let / const
        val decls = ArrayList<Declarator>()
        while (true) {
            val nameTok = expectIdent()
            validateDeclName(nameTok)
            var init: Node? = null
            if (!nlBefore() && match("=")) init = parseTernary()
            else if (kind == "const") errorAt(nameTok, "'const' must have an initializer")
            decls.add(Declarator(nameTok.text, init, nameTok.line, nameTok.col))
            if (check(",") && !nlBefore()) { next(); continue }
            break
        }
        if (!noStatementEnd) statementEnd()
        return DeclareNode(kind, decls, tok.line, tok.col)
    }

    private fun validateDeclName(tok: Token) {
        val name = tok.text
        if (name in KEYWORDS || name == CTX_NAME || name in CONSTANT_NAMES) {
            errorAt(tok, "reserved name cannot be declared: '$name'")
        }
    }

    private fun parseAssignOrExprStatement(): Node {
        val start = peek()
        val expr = parseAssignExpr()
        if (expr is AssignNode) {
            statementEnd()
            return expr
        }
        statementEnd()
        if (expr !is CallNode && expr !is MethodNode && expr !is PreIncNode && expr !is PostIncNode) {
            errorAt(start, "expression statement must be a function call")
        }
        return ExprStmtNode(expr, start.line, start.col)
    }

    private fun parseAssignExpr(): Node {
        val start = peek()
        val left = parseTernary()
        if (!nlBefore()) {
            val opTok = peek()
            val binOp = COMPOUND_ASSIGN[opTok.text]
            if (binOp != null) {
                next()
                val target = toLValue(left, start)
                val value = parseTernary()
                return AssignNode(
                    target,
                    BinaryNode(binOp, left, value, opTok.line, opTok.col),
                    start.line,
                    start.col,
                )
            }
            if (opTok.text == "=") {
                next()
                val target = toLValue(left, start)
                val value = parseAssignExpr()
                return AssignNode(target, value, start.line, start.col)
            }
        }
        return left
    }

    private fun toLValue(expr: Node, tok: Token): AssignTarget = when (expr) {
        is VarNode -> VarTarget(expr.name, expr.line, expr.col)
        is IndexNode -> IndexTarget(expr.target, expr.index, expr.line, expr.col)
        is CompNode -> CompTarget(expr.target, expr.comp, expr.line, expr.col)
        is MemberNode -> MemberTarget(expr.obj, expr.field, expr.line, expr.col)
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
        while (!nlBefore() && match("||")) {
            val opTok = tokens[pos - 1]
            val right = parseAnd()
            left = BinaryNode("||", left, right, opTok.line, opTok.col)
        }
        return left
    }

    private fun parseAnd(): Node {
        var left = parseEquality()
        while (!nlBefore() && match("&&")) {
            val opTok = tokens[pos - 1]
            val right = parseEquality()
            left = BinaryNode("&&", left, right, opTok.line, opTok.col)
        }
        return left
    }

    private fun parseEquality(): Node {
        var left = parseComparison()
        while (!nlBefore() && (check("==") || check("!="))) {
            val opTok = next()
            val right = parseComparison()
            left = BinaryNode(opTok.text, left, right, opTok.line, opTok.col)
        }
        return left
    }

    private fun parseComparison(): Node {
        var left = parseAdditive()
        while (!nlBefore() && (check("<") || check("<=") || check(">") || check(">="))) {
            val opTok = next()
            val right = parseAdditive()
            left = BinaryNode(opTok.text, left, right, opTok.line, opTok.col)
        }
        return left
    }

    private fun parseAdditive(): Node {
        var left = parseMultiplicative()
        while (!nlBefore() && (check("+") || check("-"))) {
            val opTok = next()
            val right = parseMultiplicative()
            left = BinaryNode(opTok.text, left, right, opTok.line, opTok.col)
        }
        return left
    }

    private fun parseMultiplicative(): Node {
        var left = parsePower()
        while (!nlBefore() && (check("*") || check("/") || check("%"))) {
            val opTok = next()
            val right = parsePower()
            left = BinaryNode(opTok.text, left, right, opTok.line, opTok.col)
        }
        return left
    }

    private fun parsePower(): Node {
        var left = parseUnary()
        while (!nlBefore() && match("^")) {
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
        if (check("++") || check("--")) {
            val opTok = next()
            val target = toLValue(parseUnary(), opTok)
            return PreIncNode(opTok.text, target, opTok.line, opTok.col)
        }
        return parsePostfix()
    }

    private fun parsePostfix(): Node {
        var expr = parsePrimary()
        while (true) {
            when {
                !nlBefore() && match("(") -> {
                    val args = parseArgs()
                    expr = CallNode(expr, args, expr.line, expr.col)
                }
                !nlBefore() && match("[") -> {
                    val idx = parseTernary()
                    expect("]")
                    expr = IndexNode(expr, idx, expr.line, expr.col)
                }
                !nlBefore() && match(".") -> {
                    val nameTok = expectIdent()
                    if (match("(")) {
                        val args = parseArgs()
                        expr = MethodNode(expr, nameTok.text, args, expr.line, expr.col)
                    } else if (nameTok.text in COMP_NAMES) {
                        expr = CompNode(expr, nameTok.text, expr.line, expr.col)
                    } else {
                        expr = MemberNode(expr, nameTok.text, expr.line, expr.col)
                    }
                }
                !nlBefore() && (check("++") || check("--")) -> {
                    val opTok = next()
                    val target = toLValue(expr, opTok)
                    expr = PostIncNode(opTok.text, target, opTok.line, opTok.col)
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
            if (tok.text == "undefined") {
                return UndefinedNode(tok.line, tok.col)
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

/** 解析裸表达式（UV 字段表达式等，无 setup/process 包装）。 */
fun parseExpression(source: String): Node = ScriptParser(source).parseBareExpression()