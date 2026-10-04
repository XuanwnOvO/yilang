package com.yilang.transpiler

/**
 * 蚁语言 AST 与递归下降语法分析器（v0.6）
 *
 * 语句：
 *   设|常量 类型 名 (= 表达式)?              → 声明（常量 → const）
 *   名 =|+=|-=|*=|/= 表达式                  → 赋值 / 复合赋值
 *   名 ++ | 名 --                            → 自增 / 自减
 *   如果 (表达式) 块 (否则 块|如果)?          → if/else
 *   对于 (初始; 条件; 更新) 块                → for
 *   对于 (元素 于 集合) 块                    → for-each
 *   当 (表达式) 块                           → while
 *   做 块 当 (表达式)                        → do-while
 *   选择 (表达式) { 情况 值: … 默认: … }      → switch/case
 *   跳出 / 继续                              → break / continue
 *   返回 表达式?                             → return
 *   输出 (表达式(,表达式)*)                  → printf
 *   函数 空|类型 名 (参数) 块                 → 函数定义
 *
 * 类型：整数 浮点 小数 字符 布尔/逻辑 长整 短整 字符串 自动 无符号(+整数/长整) 空(仅返回)
 *       字典<值类型> / 字典<键类型, 值类型>（std::map）；数组后缀 T[][]（二维及以上）
 *
 * 表达式：三元 条件 ? a : b 或 若 条件 则 a 否则 b；位运算 & | ^ ~ << >>；字符字面量 'A'
 */

// ---------- AST ----------

sealed class Expr {
    abstract val line: Int

    data class Num(val value: String, val isFloat: Boolean, override val line: Int) : Expr()
    data class Str(val value: String, override val line: Int) : Expr()
    data class CharLit(val value: String, override val line: Int) : Expr()
    data class BoolLit(val value: Boolean, override val line: Int) : Expr()
    data class Nullptr(override val line: Int) : Expr()
    data class Var(val name: String, override val line: Int) : Expr()
    data class Binary(val op: String, val left: Expr, val right: Expr, override val line: Int) : Expr()
    data class Unary(val op: String, val operand: Expr, override val line: Int) : Expr()

    /** 三元：条件 ? a : b（中文形式 若 条件 则 a 否则 b 归一化到此节点） */
    data class Ternary(val cond: Expr, val thenExpr: Expr, val elseExpr: Expr, override val line: Int) : Expr()

    /** 名 ++ / 名 --（indices 非空 = 数组元素自增自减：a[i]++；suffix = ".成员"） */
    data class IncDec(
        val op: String,
        val name: String,
        val indices: List<Expr> = emptyList(),
        val suffix: String = "",
        override val line: Int,
    ) : Expr()

    /** 复合赋值表达式（用于 for 更新段）：名 op= 值（支持数组元素：a[i] += 1） */
    data class AssignOp(
        val op: String,
        val name: String,
        val indices: List<Expr> = emptyList(),
        val suffix: String = "",
        val value: Expr,
        override val line: Int,
    ) : Expr()

    data class Call(val callee: String, val args: List<Expr>, override val line: Int) : Expr()

    /** 数组/字典索引：a[i]、m["键"]、a[i][j]；suffix 为索引后的成员访问（a[i].x / a[i].长度） */
    data class Index(
        val base: String,
        val indices: List<Expr>,
        val suffix: String = "",
        override val line: Int,
    ) : Expr()

    /** 列表字面量：{1, 2, 3}（数组初始化用） */
    data class ListLit(val items: List<Expr>, override val line: Int) : Expr()
}

sealed class Stmt {
    abstract val line: Int

    /** 变量声明：dims = 数组维度（0 = 非数组，1 = vector，2 = 二维 vector<vector>…） */
    data class ValDecl(
        val cppType: String,
        val name: String,
        val init: Expr?,
        val isConst: Boolean = false,
        val dims: Int = 0,
        override val line: Int,
    ) : Stmt()

    data class Assign(
        val name: String,
        val op: String,          // = / += / -= / *= / /=
        val indices: List<Expr> = emptyList(), // 非空 = 数组元素赋值：a[i] = 5
        val suffix: String = "",               // 索引后成员：学生[0].名字 = "张三"
        val value: Expr,
        override val line: Int,
    ) : Stmt()

    /** 输入 类型 变量 → std::cin >> 变量（终端交互输入） */
    data class Input(
        val cppType: String,
        val name: String,
        override val line: Int,
    ) : Stmt()

    data class If(
        val cond: Expr,
        val thenBody: List<Stmt>,
        val elseBody: List<Stmt>?,   // null = 无 else
        override val line: Int,
    ) : Stmt()

    data class While(val cond: Expr, val body: List<Stmt>, override val line: Int) : Stmt()

    data class For(
        val init: Stmt?,         // 声明 / 赋值 / 自增自减，null = 空段
        val cond: Expr?,         // null = 空段
        val update: Expr?,       // IncDec / AssignOp，null = 空段
        val body: List<Stmt>,
        override val line: Int,
    ) : Stmt()

    data class DoWhile(val body: List<Stmt>, val cond: Expr, override val line: Int) : Stmt()

    /** for-each：对于 (元素 于 集合) { … } → for (auto &&元素 : 集合) */
    data class ForEach(
        val elem: String,
        val iterable: Expr,
        val body: List<Stmt>,
        override val line: Int,
    ) : Stmt()

    data class SwitchCase(
        val isDefault: Boolean,
        val test: Expr?,         // 「默认」分支为 null
        val body: List<Stmt>,    // 允许空体（C++ 贯穿语义）
        override val line: Int,
    ) : Stmt()

    data class Switch(val subject: Expr, val cases: List<SwitchCase>, override val line: Int) : Stmt()

    data class Break(override val line: Int) : Stmt()
    data class Continue(override val line: Int) : Stmt()

    data class Return(val value: Expr?, override val line: Int) : Stmt()

    data class Output(val args: List<Expr>, override val line: Int) : Stmt()

    data class ExprStmt(val expr: Expr, override val line: Int) : Stmt()

    /** 函数参数：cppType + 名 + 数组维度 */
    data class YiParam(val cppType: String, val name: String, val dims: Int = 0, val byRef: Boolean = false)

    data class FuncDef(
        val cppReturnType: String,
        val name: String,
        val params: List<YiParam>,
        val body: List<Stmt>,
        override val line: Int,
    ) : Stmt()

    /** 结构体定义（成员复用 ValDecl：类型 + 名 + 可选默认值） */
    data class StructDef(
        val name: String,
        val members: List<ValDecl>,
        override val line: Int,
    ) : Stmt()

    /** 枚举定义：成员名 + 可选数值字面量 */
    data class EnumDef(
        val name: String,
        val members: List<Pair<String, String?>>,
        override val line: Int,
    ) : Stmt()
}

/** 语法错误：中文信息 + 行号 */
class ParseException(val line: Int, message: String) : Exception("第 $line 行：$message")

class Parser(private val tokens: List<Token>) {

    private var pos = 0

    // 已定义的复合类型（用于「设 类型名 变量」的类型校验，要求先定义后使用）
    private val structNames = mutableSetOf<String>()
    private val enumNames = mutableSetOf<String>()

    // 已定义的函数名 → 首次定义行（重复定义检查）
    private val funcNames = mutableMapOf<String, Int>()

    /** 令牌流的可变副本：泛型闭合「>>」需要拆成两个「>」 */
    private val toks: MutableList<Token> = tokens.toMutableList()

    private fun peek(): Token = toks[pos]
    private fun next(): Token = toks[pos++]

    /** 向前看第 n 个 token（不消费；越过 EOF 时返回 EOF） */
    private fun peekAhead(n: Int): Token = toks.getOrNull(pos + n) ?: toks.last()

    private fun eof() = peek().type == TokenType.EOF

    private fun expect(t: TokenType, what: String): Token {
        val tk = peek()
        if (tk.type != t) throw ParseException(tk.line, "应为$what，但发现「${display(tk)}」")
        return next()
    }

    private fun display(tk: Token) = when (tk.type) {
        TokenType.NEWLINE -> "换行"
        TokenType.EOF -> "文件结尾"
        else -> tk.text
    }

    /** 跳过换行与注释 */
    private fun skipSeparators() {
        while (peek().type == TokenType.NEWLINE || peek().type == TokenType.COMMENT) next()
    }

    // ---------- 程序 ----------

    fun parseProgram(): List<Stmt> {
        val stmts = mutableListOf<Stmt>()
        skipSeparators()
        while (!eof()) {
            stmts.add(parseStatement())
            skipSeparators()
        }
        return stmts
    }

    private fun parseBlock(): List<Stmt> {
        expect(TokenType.LBRACE, "「{」")
        val stmts = mutableListOf<Stmt>()
        skipSeparators()
        while (peek().type != TokenType.RBRACE) {
            if (eof()) throw ParseException(peek().line, "缺少「}」，代码块未闭合")
            stmts.add(parseStatement())
            skipSeparators()
        }
        next() // consume }
        return stmts
    }

    // ---------- 语句 ----------

    private fun parseStatement(): Stmt {
        val tk = peek()
        return when (tk.type) {
            TokenType.KW_SET -> parseVarDecl()
            TokenType.KW_CONST -> parseVarDecl(isConst = true)
            TokenType.KW_IF -> parseIf()
            TokenType.KW_WHILE -> parseWhile()
            TokenType.KW_FOR -> parseFor()
            TokenType.KW_DO -> parseDoWhile()
            TokenType.KW_SWITCH -> parseSwitch()
            TokenType.KW_BREAK -> parseBreak()
            TokenType.KW_CONTINUE -> parseContinue()
            TokenType.KW_RETURN -> parseReturn()
            TokenType.KW_OUTPUT -> parseOutput()
            TokenType.KW_FUNC -> parseFuncDef()
            TokenType.KW_STRUCT -> parseStruct()
            TokenType.KW_ENUM -> parseEnum()
            TokenType.KW_INT, TokenType.KW_FLOAT, TokenType.KW_DOUBLE, TokenType.KW_CHAR,
            TokenType.KW_BOOL, TokenType.KW_LONG, TokenType.KW_SHORT, TokenType.KW_STRING,
            TokenType.KW_UNSIGNED, TokenType.KW_AUTO, TokenType.KW_VOID ->
                throw ParseException(tk.line, "类型「${tk.text}」需配合「设」「常量」或「函数」使用")
            TokenType.IDENT -> {
                // 「输入 类型 变量」语句（输入(x) 调用形式不受影响，仍走表达式）
                if (tk.text == "输入" && peekAhead(1).type in INPUTABLE_TYPES) return parseInput()
                parseAssignOrExpr()
            }
            else -> throw ParseException(tk.line, "无法识别的语句起始「${display(tk)}」")
        }
    }

    /** 输入语句可用的基本类型 */
    private val INPUTABLE_TYPES = setOf(
        TokenType.KW_INT, TokenType.KW_FLOAT, TokenType.KW_DOUBLE, TokenType.KW_CHAR,
        TokenType.KW_BOOL, TokenType.KW_LONG, TokenType.KW_SHORT, TokenType.KW_STRING,
    )

    /** 输入 整数 x / 输入 字符串 名字 → std::cin >> x */
    private fun parseInput(): Stmt {
        val inTk = next() // 输入
        val typeTk = next()
        if (typeTk.type !in INPUTABLE_TYPES)
            throw ParseException(typeTk.line, "「输入」后应为基本类型（整数/小数/字符串等），但发现「${typeTk.text}」")
        val nameTk = expect(TokenType.IDENT, "变量名")
        endOfStatement(inTk)
        val cppType = cppTypeOf(typeTk, "「输入」")
        return Stmt.Input(cppType, nameTk.text, inTk.line)
    }

    // ---------- 类型 ----------

    /** 解析结果：C++ 基础类型 + 数组维度（0 = 非数组） */
    private data class YiType(val cpp: String, val dims: Int)

    private fun cppTypeOf(tk: Token, context: String): String = when (tk.type) {
        TokenType.KW_INT -> "int"
        TokenType.KW_FLOAT -> "float"
        TokenType.KW_DOUBLE -> "double"
        TokenType.KW_CHAR -> "char"
        TokenType.KW_BOOL -> "bool"
        TokenType.KW_LONG -> "long"
        TokenType.KW_SHORT -> "short"
        TokenType.KW_STRING -> "std::string"
        TokenType.KW_AUTO -> "auto"
        TokenType.IDENT -> throw ParseException(tk.line, "未知类型「${tk.text}」（需先用「结构体」或「枚举」定义）")
        else -> throw ParseException(tk.line, "${context}后应为类型，但发现「${tk.text}」")
    }

    /** 允许作为字典键的 C++ 类型（需要可比较大小） */
    private val MAP_KEY_TYPES = setOf(
        "int", "long", "short", "unsigned int", "unsigned long",
        "char", "bool", "float", "double", "std::string",
    )

    /** 基础类型 token → C++ 类型串（结构体/枚举名可用；用于字典泛型参数） */
    private fun parseBasicTypeToken(context: String): String {
        val tk = peek()
        if (tk.type == TokenType.KW_UNSIGNED) {
            next()
            val b = next()
            return when (b.type) {
                TokenType.KW_INT -> "unsigned int"
                TokenType.KW_LONG -> "unsigned long"
                else -> throw ParseException(b.line, "「无符号」后应为「整数」或「长整」，但发现「${b.text}」")
            }
        }
        next()
        if (tk.type == TokenType.IDENT && (tk.text in structNames || tk.text in enumNames)) return tk.text
        return cppTypeOf(tk, context)
    }

    /**
     * 字典泛型：字典<值类型> 或 字典<键类型, 值类型> → std::map<键, 值>
     * 递归支持嵌套：字典<字符串, 字典<整数>>
     */
    private fun parseMapType(context: String): String {
        expect(TokenType.OP_LT, "「<」")
        val first = parseScalarOrMap(context)
        val key: String
        val value: String
        if (peek().type == TokenType.COMMA) {
            next()
            key = first
            value = parseScalarOrMap(context)
        } else {
            key = "std::string"
            value = first
        }
        expectCloseGt()
        if (key !in MAP_KEY_TYPES)
            throw ParseException(peek().line, "字典键类型「$key」不支持（可用：字符串/整数/长整/短整/字符/布尔等基本类型）")
        return "std::map<$key, $value>"
    }

    private fun parseScalarOrMap(context: String): String =
        if (peek().type == TokenType.KW_MAP) parseMapType(context) else parseBasicTypeToken(context)

    /** 消费一个「>」；遇到「>>」拆成两个（嵌套泛型 字典<字典<整数>>） */
    private fun expectCloseGt() {
        val tk = peek()
        when (tk.type) {
            TokenType.OP_GT -> next()
            TokenType.OP_SHR -> {
                toks[pos] = tk.copy(type = TokenType.OP_GT, text = ">")
                toks.add(pos + 1, tk.copy(type = TokenType.OP_GT, text = ">"))
                next()
            }
            else -> throw ParseException(tk.line, "泛型应为「>」闭合，但发现「${display(tk)}」")
        }
    }

    /** 解析变量/参数类型：无符号组合、字典、自定义类型、数组后缀 T[] / T[][]；「空」不可作变量类型 */
    private fun parseCppType(context: String): YiType {
        val tk = next()
        if (tk.type == TokenType.KW_VOID)
            throw ParseException(tk.line, "「空」不能作为变量类型（仅函数返回类型可用）")
        val base: String = when {
            tk.type == TokenType.KW_UNSIGNED -> {
                val b = next()
                when (b.type) {
                    TokenType.KW_INT -> "unsigned int"
                    TokenType.KW_LONG -> "unsigned long"
                    else -> throw ParseException(b.line, "「无符号」后应为「整数」或「长整」，但发现「${b.text}」")
                }
            }
            tk.type == TokenType.KW_MAP -> parseMapType(context)
            tk.type == TokenType.IDENT && (tk.text in structNames || tk.text in enumNames) -> tk.text
            else -> cppTypeOf(tk, context)
        }
        // 数组后缀：整数[] / 整数[][]（多维）
        var dims = 0
        while (peek().type == TokenType.LBRACKET) {
            next()
            expect(TokenType.RBRACKET, "「]」")
            dims++
        }
        if (dims > 0 && base == "auto") throw ParseException(tk.line, "「自动」不能声明数组（无法推断元素类型）")
        if (dims > 0 && base.startsWith("std::map")) throw ParseException(tk.line, "字典不能作为数组元素（可嵌套：字典<字符串, 字典<整数>>）")
        return YiType(base, dims)
    }

    /** 解析函数返回类型：「空」或任意变量类型（含无符号组合）；不支持返回数组 */
    private fun parseReturnType(): String {
        val tk = next()
        if (tk.type == TokenType.KW_VOID) return "void"
        if (tk.type == TokenType.KW_UNSIGNED) {
            val base = next()
            return when (base.type) {
                TokenType.KW_INT -> "unsigned int"
                TokenType.KW_LONG -> "unsigned long"
                else -> throw ParseException(base.line, "「无符号」后应为「整数」或「长整」，但发现「${base.text}」")
            }
        }
        if (tk.type == TokenType.KW_MAP) {
            if (peek().type == TokenType.LBRACKET)
                throw ParseException(peek().line, "函数不能返回数组")
            return parseMapType("「函数」")
        }
        if (tk.type == TokenType.IDENT && (tk.text in structNames || tk.text in enumNames)) {
            if (peek().type == TokenType.LBRACKET)
                throw ParseException(peek().line, "函数不能返回数组")
            return tk.text
        }
        val t = cppTypeOf(tk, "「函数」")
        if (peek().type == TokenType.LBRACKET)
            throw ParseException(peek().line, "函数不能返回数组")
        return t
    }

    // ---------- 声明 / 赋值 ----------

    private fun parseVarDecl(isConst: Boolean = false, requireEnd: Boolean = true): Stmt.ValDecl {
        val startTk = if (isConst) expect(TokenType.KW_CONST, "「常量」")
                      else expect(TokenType.KW_SET, "「设」")
        val t = parseCppType("「${startTk.text}」")
        val nameTk = expect(TokenType.IDENT, "变量名")
        var init: Expr? = null
        if (peek().type == TokenType.OP_ASSIGN) {
            next()
            init = parseExpr()
        }
        if (requireEnd) endOfStatement(startTk)
        return Stmt.ValDecl(t.cpp, nameTk.text, init, isConst, t.dims, startTk.line)
    }

    private fun parseAssignOrExpr(): Stmt {
        val startTk = peek()
        val lv = parseLValue()
        // 函数调用语句：名(参数)（带下标时不是调用）
        if (lv.indices.isEmpty() && lv.suffix.isEmpty() && peek().type == TokenType.LPAREN) {
            val call = parseCallTail(lv.path, startTk.line)
            endOfStatement(startTk)
            return Stmt.ExprStmt(call, startTk.line)
        }
        // 自增 / 自减（含数组元素、成员：a[i]++、学生[0].年龄++）
        if (peek().type == TokenType.OP_INC || peek().type == TokenType.OP_DEC) {
            val op = next()
            val e = Expr.IncDec(op.text, lv.path, lv.indices, lv.suffix, startTk.line)
            endOfStatement(startTk)
            return Stmt.ExprStmt(e, startTk.line)
        }
        // 赋值 / 复合赋值（含数组元素：a[i] = 5；索引后成员：学生[0].名字 = "张三"）
        val opTk = peek()
        if (opTk.type == TokenType.OP_ASSIGN || opTk.type == TokenType.OP_PLUS_ASSIGN ||
            opTk.type == TokenType.OP_MINUS_ASSIGN || opTk.type == TokenType.OP_STAR_ASSIGN ||
            opTk.type == TokenType.OP_SLASH_ASSIGN
        ) {
            next()
            val value = parseExpr()
            endOfStatement(startTk)
            return Stmt.Assign(lv.path, opTk.text, lv.indices, lv.suffix, value, startTk.line)
        }
        throw ParseException(
            opTk.line,
            "变量「${lv.path}」后应为「=」「+=」「-=」「*=」「/=」「++」「--」或「(」，但发现「${display(opTk)}」"
        )
    }

    /** 左值：点路径 + 数组/字典下标 + 可选索引后成员（a[i]、p.x[j]、学生[0].名字） */
    private data class LValue(val path: String, val indices: List<Expr>, val suffix: String = "")

    private fun parseLValue(): LValue {
        val path = parseNamePath()
        val indices = mutableListOf<Expr>()
        while (peek().type == TokenType.LBRACKET) {
            next()
            indices.add(parseExpr())
            expect(TokenType.RBRACKET, "「]」")
        }
        var suffix = ""
        while (peek().type == TokenType.DOT) {
            next()
            val mTk = expect(TokenType.IDENT, "成员名")
            suffix += "." + mTk.text
        }
        return LValue(path, indices, suffix)
    }

    /** 名 / p.x / a.b.c —— 点路径（成员访问） */
    private fun parseNamePath(): String {
        val nameTk = expect(TokenType.IDENT, "变量名")
        var path = nameTk.text
        while (peek().type == TokenType.DOT) {
            next()
            val mTk = expect(TokenType.IDENT, "成员名")
            path += "." + mTk.text
        }
        return path
    }

    // ---------- 控制流 ----------

    private fun parseIf(): Stmt {
        val ifTk = expect(TokenType.KW_IF, "「如果」")
        expect(TokenType.LPAREN, "「(」")
        val cond = parseExpr()
        expect(TokenType.RPAREN, "「)」")
        val thenBody = parseBlock()
        var elseBody: List<Stmt>? = null
        skipSeparators()
        if (peek().type == TokenType.KW_ELSE) {
            next()
            skipSeparators()
            if (peek().type == TokenType.KW_IF) {
                // 否则 如果 → else { if ... }，包一层保持结构简单
                val nested = parseIf()
                elseBody = listOf(nested)
            } else {
                elseBody = parseBlock()
            }
        }
        return Stmt.If(cond, thenBody, elseBody, ifTk.line)
    }

    private fun parseWhile(): Stmt {
        val whileTk = expect(TokenType.KW_WHILE, "「当」")
        expect(TokenType.LPAREN, "「(」")
        val cond = parseExpr()
        expect(TokenType.RPAREN, "「)」")
        val body = parseBlock()
        return Stmt.While(cond, body, whileTk.line)
    }

    /** 对于 (初始; 条件; 更新) { }；对于 (元素 于 集合) { } → for-each */
    private fun parseFor(): Stmt {
        val forTk = expect(TokenType.KW_FOR, "「对于」")
        expect(TokenType.LPAREN, "「(」")
        skipSeparators()
        // for-each 形式：「元素 于 集合」或「设 元素 于 集合」
        val isForeach = (peek().type == TokenType.IDENT && peekAhead(1).type == TokenType.KW_IN) ||
            (peek().type == TokenType.KW_SET && peekAhead(1).type == TokenType.IDENT && peekAhead(2).type == TokenType.KW_IN)
        if (isForeach) {
            if (peek().type == TokenType.KW_SET) next()
            val elemTk = expect(TokenType.IDENT, "循环元素名")
            expect(TokenType.KW_IN, "「于」")
            val iterable = parseExpr()
            expect(TokenType.RPAREN, "「)」")
            val body = parseBlock()
            return Stmt.ForEach(elemTk.text, iterable, body, forTk.line)
        }
        val init = parseForInit()
        skipSeparators()
        expect(TokenType.SEMICOLON, "「;」")
        skipSeparators()
        var cond: Expr? = null
        if (peek().type != TokenType.SEMICOLON) cond = parseExpr()
        skipSeparators()
        expect(TokenType.SEMICOLON, "「;」")
        skipSeparators()
        var update: Expr? = null
        if (peek().type != TokenType.RPAREN) update = parseUpdateLike()
        skipSeparators()
        expect(TokenType.RPAREN, "「)」")
        val body = parseBlock()
        return Stmt.For(init, cond, update, body, forTk.line)
    }

    /** for 循环第一段：声明 / 赋值 / 自增自减 / 空 */
    private fun parseForInit(): Stmt? {
        if (peek().type == TokenType.SEMICOLON) return null
        return when (peek().type) {
            TokenType.KW_SET -> parseVarDecl(requireEnd = false)
            TokenType.KW_CONST -> parseVarDecl(isConst = true, requireEnd = false)
            TokenType.IDENT -> {
                val startTk = peek()
                val lv = parseLValue()
                val opTk = peek()
                when (opTk.type) {
                    TokenType.OP_ASSIGN, TokenType.OP_PLUS_ASSIGN, TokenType.OP_MINUS_ASSIGN,
                    TokenType.OP_STAR_ASSIGN, TokenType.OP_SLASH_ASSIGN -> {
                        next()
                        Stmt.Assign(lv.path, opTk.text, lv.indices, lv.suffix, parseExpr(), startTk.line)
                    }
                    TokenType.OP_INC, TokenType.OP_DEC -> {
                        val op = next()
                        Stmt.ExprStmt(Expr.IncDec(op.text, lv.path, lv.indices, lv.suffix, startTk.line), startTk.line)
                    }
                    else -> throw ParseException(
                        opTk.line,
                        "循环初始段中变量「${lv.path}」后应为「=」「+=」「-=」「*=」「/=」「++」「--」，但发现「${display(opTk)}」"
                    )
                }
            }
            else -> throw ParseException(peek().line, "循环初始段应为声明、赋值或空，但发现「${display(peek())}」")
        }
    }

    /** for 循环第三段：更新表达式（自增/自减/赋值/复合赋值，支持成员路径与数组下标） */
    private fun parseUpdateLike(): Expr {
        val startTk = peek()
        val lv = parseLValue()
        return when (peek().type) {
            TokenType.OP_INC, TokenType.OP_DEC -> {
                val op = next()
                Expr.IncDec(op.text, lv.path, lv.indices, lv.suffix, startTk.line)
            }
            TokenType.OP_ASSIGN, TokenType.OP_PLUS_ASSIGN, TokenType.OP_MINUS_ASSIGN,
            TokenType.OP_STAR_ASSIGN, TokenType.OP_SLASH_ASSIGN -> {
                val opTk = next()
                Expr.AssignOp(opTk.text, lv.path, lv.indices, lv.suffix, parseExpr(), startTk.line)
            }
            else -> throw ParseException(
                peek().line,
                "循环更新段应为「++」「--」「=」「+=」「-=」「*=」「/=」，但发现「${display(peek())}」"
            )
        }
    }

    /** 做 { } 当 (条件) */
    private fun parseDoWhile(): Stmt {
        val doTk = expect(TokenType.KW_DO, "「做」")
        val body = parseBlock()
        skipSeparators()
        expect(TokenType.KW_WHILE, "「当」")
        expect(TokenType.LPAREN, "「(」")
        val cond = parseExpr()
        expect(TokenType.RPAREN, "「)」")
        endOfStatement(doTk)
        return Stmt.DoWhile(body, cond, doTk.line)
    }

    /** 选择 (表达式) { 情况 值: … 默认: … } */
    private fun parseSwitch(): Stmt {
        val swTk = expect(TokenType.KW_SWITCH, "「选择」")
        expect(TokenType.LPAREN, "「(」")
        val subject = parseExpr()
        expect(TokenType.RPAREN, "「)」")
        expect(TokenType.LBRACE, "「{」")
        val cases = mutableListOf<Stmt.SwitchCase>()
        skipSeparators()
        while (peek().type != TokenType.RBRACE) {
            if (eof()) throw ParseException(peek().line, "选择块缺少「}」")
            when (peek().type) {
                TokenType.KW_CASE -> {
                    val caseTk = next()
                    val test = parseExpr()
                    expect(TokenType.COLON, "「:」")
                    cases.add(Stmt.SwitchCase(false, test, parseCaseBody(), caseTk.line))
                }
                TokenType.KW_DEFAULT -> {
                    val defTk = next()
                    expect(TokenType.COLON, "「:」")
                    cases.add(Stmt.SwitchCase(true, null, parseCaseBody(), defTk.line))
                }
                else -> throw ParseException(peek().line, "选择块内应为「情况」或「默认」，但发现「${display(peek())}」")
            }
            skipSeparators()
        }
        next() // consume }
        if (cases.isEmpty()) throw ParseException(swTk.line, "选择块至少需要一个「情况」或「默认」")
        return Stmt.Switch(subject, cases, swTk.line)
    }

    /** case 体：累积语句直到下一个「情况」「默认」或「}」（允许空体，支持贯穿） */
    private fun parseCaseBody(): List<Stmt> {
        val stmts = mutableListOf<Stmt>()
        skipSeparators()
        while (peek().type != TokenType.KW_CASE && peek().type != TokenType.KW_DEFAULT &&
            peek().type != TokenType.RBRACE
        ) {
            if (eof()) throw ParseException(peek().line, "选择块缺少「}」")
            stmts.add(parseStatement())
            skipSeparators()
        }
        return stmts
    }

    private fun parseBreak(): Stmt {
        val tk = expect(TokenType.KW_BREAK, "「跳出」")
        endOfStatement(tk)
        return Stmt.Break(tk.line)
    }

    private fun parseContinue(): Stmt {
        val tk = expect(TokenType.KW_CONTINUE, "「继续」")
        endOfStatement(tk)
        return Stmt.Continue(tk.line)
    }

    private fun parseReturn(): Stmt {
        val retTk = expect(TokenType.KW_RETURN, "「返回」")
        var value: Expr? = null
        if (peek().type !in listOf(TokenType.NEWLINE, TokenType.SEMICOLON, TokenType.EOF, TokenType.RBRACE, TokenType.COMMENT))
            value = parseExpr()
        endOfStatement(retTk)
        return Stmt.Return(value, retTk.line)
    }

    private fun parseOutput(): Stmt {
        val outTk = expect(TokenType.KW_OUTPUT, "「输出」")
        expect(TokenType.LPAREN, "「(」")
        val args = mutableListOf<Expr>()
        if (peek().type != TokenType.RPAREN) {
            args.add(parseExpr())
            while (peek().type == TokenType.COMMA) {
                next()
                args.add(parseExpr())
            }
        }
        expect(TokenType.RPAREN, "「)」")
        endOfStatement(outTk)
        return Stmt.Output(args, outTk.line)
    }

    private fun parseFuncDef(): Stmt {
        val funcTk = expect(TokenType.KW_FUNC, "「函数」")
        val retType = parseReturnType()
        val nameTk = expect(TokenType.IDENT, "函数名")
        if (nameTk.text in funcNames)
            throw ParseException(nameTk.line, "函数「${nameTk.text}」重复定义（首次定义在第 ${funcNames[nameTk.text]} 行）")
        funcNames[nameTk.text] = nameTk.line
        expect(TokenType.LPAREN, "「(」")
        val params = mutableListOf<Stmt.YiParam>()
        if (peek().type != TokenType.RPAREN) {
            val t = parseCppType("参数")
            var byRef = false
            if (peek().type == TokenType.KW_REF) {
                next()
                byRef = true
            }
            val nTk = expect(TokenType.IDENT, "参数名")
            params.add(Stmt.YiParam(t.cpp, nTk.text, t.dims, byRef))
            while (peek().type == TokenType.COMMA) {
                next()
                val p = parseCppType("参数")
                var pRef = false
                if (peek().type == TokenType.KW_REF) {
                    next()
                    pRef = true
                }
                val n = expect(TokenType.IDENT, "参数名")
                params.add(Stmt.YiParam(p.cpp, n.text, p.dims, pRef))
            }
        }
        expect(TokenType.RPAREN, "「)」")
        val body = parseBlock()
        return Stmt.FuncDef(retType, nameTk.text, params, body, funcTk.line)
    }

    /** 结构体 名 { 设 类型 名 (= 表达式)? … } */
    private fun parseStruct(): Stmt {
        val sTk = expect(TokenType.KW_STRUCT, "「结构体」")
        val nameTk = expect(TokenType.IDENT, "结构体名")
        if (nameTk.text in structNames || nameTk.text in enumNames)
            throw ParseException(nameTk.line, "类型「${nameTk.text}」已定义")
        expect(TokenType.LBRACE, "「{」")
        val members = mutableListOf<Stmt.ValDecl>()
        skipSeparators()
        while (peek().type != TokenType.RBRACE) {
            if (eof()) throw ParseException(peek().line, "结构体缺少「}」")
            if (peek().type != TokenType.KW_SET)
                throw ParseException(peek().line, "结构体成员应以「设」开头，但发现「${display(peek())}」")
            members.add(parseVarDecl())
            skipSeparators()
        }
        next() // consume }
        if (members.isEmpty()) throw ParseException(nameTk.line, "结构体「${nameTk.text}」至少需要一个成员")
        structNames.add(nameTk.text)
        return Stmt.StructDef(nameTk.text, members, sTk.line)
    }

    /** 枚举 名 { 成员名 (= 整数)? … }（成员可用逗号或换行分隔） */
    private fun parseEnum(): Stmt {
        val eTk = expect(TokenType.KW_ENUM, "「枚举」")
        val nameTk = expect(TokenType.IDENT, "枚举名")
        if (nameTk.text in structNames || nameTk.text in enumNames)
            throw ParseException(nameTk.line, "类型「${nameTk.text}」已定义")
        expect(TokenType.LBRACE, "「{」")
        val members = mutableListOf<Pair<String, String?>>()
        skipSeparators()
        while (peek().type != TokenType.RBRACE) {
            if (eof()) throw ParseException(peek().line, "枚举缺少「}」")
            val mTk = expect(TokenType.IDENT, "枚举成员名")
            var value: String? = null
            if (peek().type == TokenType.OP_ASSIGN) {
                next()
                value = expect(TokenType.NUMBER, "整数值").text
            }
            members.add(mTk.text to value)
            if (peek().type == TokenType.COMMA) next()
            skipSeparators()
        }
        next() // consume }
        if (members.isEmpty()) throw ParseException(nameTk.line, "枚举「${nameTk.text}」至少需要一个成员")
        enumNames.add(nameTk.text)
        return Stmt.EnumDef(nameTk.text, members, eTk.line)
    }

    /** 语句结尾：换行 / 分号 / 「}」前 */
    private fun endOfStatement(startTk: Token) {
        when (peek().type) {
            TokenType.NEWLINE, TokenType.SEMICOLON -> next()
            TokenType.RBRACE, TokenType.COMMENT, TokenType.EOF -> {}
            else -> throw ParseException(peek().line, "语句「${startTk.text}…」后应换行，但发现「${display(peek())}」")
        }
    }

    // ---------- 表达式（优先级从低到高） ----------

    fun parseExpr(): Expr = parseTernary()

    /** 三元：条件 ? a : b；中文形式 若 条件 则 a 否则 b（归一化到同一节点） */
    private fun parseTernary(): Expr {
        if (peek().type == TokenType.KW_TERN) {
            val tk = next()
            val cond = parseExpr()
            expect(TokenType.KW_THEN, "「则」")
            val thenE = parseExpr()
            expect(TokenType.KW_ELSE, "「否则」")
            return Expr.Ternary(cond, thenE, parseExpr(), tk.line)
        }
        val cond = parseOr()
        if (peek().type == TokenType.OP_QUESTION) {
            val tk = next()
            val thenE = parseExpr()
            expect(TokenType.COLON, "「:」")
            return Expr.Ternary(cond, thenE, parseExpr(), tk.line)
        }
        return cond
    }

    private fun parseOr(): Expr {
        var left = parseAnd()
        while (peek().type == TokenType.OP_OR) {
            val tk = next()
            left = Expr.Binary("||", left, parseAnd(), tk.line)
        }
        return left
    }

    private fun parseAnd(): Expr {
        var left = parseBitOr()
        while (peek().type == TokenType.OP_AND) {
            val tk = next()
            left = Expr.Binary("&&", left, parseBitOr(), tk.line)
        }
        return left
    }

    /** 位或 |（中文：位或） */
    private fun parseBitOr(): Expr {
        var left = parseBitXor()
        while (peek().type == TokenType.OP_BIT_OR) {
            val tk = next()
            left = Expr.Binary("|", left, parseBitXor(), tk.line)
        }
        return left
    }

    /** 位异或 ^（中文：位异或） */
    private fun parseBitXor(): Expr {
        var left = parseBitAnd()
        while (peek().type == TokenType.OP_BIT_XOR) {
            val tk = next()
            left = Expr.Binary("^", left, parseBitAnd(), tk.line)
        }
        return left
    }

    /** 位与 &（中文：位与） */
    private fun parseBitAnd(): Expr {
        var left = parseEquality()
        while (peek().type == TokenType.OP_BIT_AND) {
            val tk = next()
            left = Expr.Binary("&", left, parseEquality(), tk.line)
        }
        return left
    }

    private fun parseEquality(): Expr {
        var left = parseRelational()
        while (peek().type == TokenType.OP_EQ || peek().type == TokenType.OP_NE) {
            val tk = next()
            left = Expr.Binary(tk.text, left, parseRelational(), tk.line)
        }
        return left
    }

    private fun parseRelational(): Expr {
        var left = parseShift()
        while (peek().type in listOf(TokenType.OP_GT, TokenType.OP_GE, TokenType.OP_LT, TokenType.OP_LE)) {
            val tk = next()
            left = Expr.Binary(tk.text, left, parseShift(), tk.line)
        }
        return left
    }

    /** 移位 << >>（中文：左移 / 右移） */
    private fun parseShift(): Expr {
        var left = parseAdditive()
        while (peek().type == TokenType.OP_SHL || peek().type == TokenType.OP_SHR) {
            val tk = next()
            // token 文本是中文词（左移/右移），op 必须硬编码为 C++ 符号
            val op = if (tk.type == TokenType.OP_SHL) "<<" else ">>"
            left = Expr.Binary(op, left, parseAdditive(), tk.line)
        }
        return left
    }

    private fun parseAdditive(): Expr {
        var left = parseMultiplicative()
        while (peek().type == TokenType.OP_PLUS || peek().type == TokenType.OP_MINUS) {
            val tk = next()
            left = Expr.Binary(tk.text, left, parseMultiplicative(), tk.line)
        }
        return left
    }

    private fun parseMultiplicative(): Expr {
        var left = parseUnary()
        while (peek().type in listOf(TokenType.OP_STAR, TokenType.OP_SLASH, TokenType.OP_PERCENT)) {
            val tk = next()
            left = Expr.Binary(tk.text, left, parseUnary(), tk.line)
        }
        return left
    }

    private fun parseUnary(): Expr {
        val tk = peek()
        if (tk.type == TokenType.OP_MINUS || tk.type == TokenType.OP_NOT || tk.type == TokenType.OP_BIT_NOT) {
            next()
            // token 文本可能是中文词（非/位负），按类型映射为 C++ 符号
            val op = when (tk.type) {
                TokenType.OP_NOT -> "!"
                TokenType.OP_BIT_NOT -> "~"
                else -> "-"
            }
            return Expr.Unary(op, parseUnary(), tk.line)
        }
        return parsePrimary()
    }

    private fun parsePrimary(): Expr {
        val tk = peek()
        return when (tk.type) {
            TokenType.NUMBER -> {
                next()
                Expr.Num(tk.text, tk.text.contains('.'), tk.line)
            }
            TokenType.STRING -> {
                next()
                Expr.Str(tk.text, tk.line)
            }
            TokenType.CHAR -> {
                next()
                Expr.CharLit(tk.text, tk.line)
            }
            TokenType.KW_TRUE -> {
                next()
                Expr.BoolLit(true, tk.line)
            }
            TokenType.KW_FALSE -> {
                next()
                Expr.BoolLit(false, tk.line)
            }
            TokenType.KW_NULLPTR -> {
                next()
                Expr.Nullptr(tk.line)
            }
            TokenType.IDENT -> {
                next()
                var name = tk.text
                while (peek().type == TokenType.DOT) {
                    next()
                    val m = expect(TokenType.IDENT, "成员名")
                    name += "." + m.text
                }
                if (peek().type == TokenType.LPAREN) return parseCallTail(name, tk.line)
                // 数组索引：a[i]、m[i][j]（之后不允许再取成员）
                if (peek().type == TokenType.LBRACKET) {
                    val indices = mutableListOf<Expr>()
                    while (peek().type == TokenType.LBRACKET) {
                        next()
                        indices.add(parseExpr())
                        expect(TokenType.RBRACKET, "「]」")
                    }
                    // 索引后成员：a[i].x、成绩[i].长度
                    var suffix = ""
                    while (peek().type == TokenType.DOT) {
                        next()
                        suffix += "." + expect(TokenType.IDENT, "成员名").text
                    }
                    return Expr.Index(name, indices, suffix, tk.line)
                }
                Expr.Var(name, tk.line)
            }
            TokenType.LBRACE -> {
                // 列表字面量：{1, 2, 3}（用于数组初始化）
                next()
                val items = mutableListOf<Expr>()
                if (peek().type != TokenType.RBRACE) {
                    items.add(parseExpr())
                    while (peek().type == TokenType.COMMA) {
                        next()
                        items.add(parseExpr())
                    }
                }
                expect(TokenType.RBRACE, "「}」")
                Expr.ListLit(items, tk.line)
            }
            TokenType.LPAREN -> {
                next()
                val e = parseExpr()
                expect(TokenType.RPAREN, "「)」")
                e
            }
            else -> throw ParseException(tk.line, "此处应为表达式，但发现「${display(tk)}」")
        }
    }

    private fun parseCallTail(callee: String, line: Int): Expr {
        expect(TokenType.LPAREN, "「(」")
        val args = mutableListOf<Expr>()
        if (peek().type != TokenType.RPAREN) {
            args.add(parseExpr())
            while (peek().type == TokenType.COMMA) {
                next()
                args.add(parseExpr())
            }
        }
        expect(TokenType.RPAREN, "「)」")
        return Expr.Call(callee, args, line)
    }
}
