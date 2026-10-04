package com.yilang.transpiler

/**
 * 蚁语言词法分析器（v0.6）
 * 按字符扫描源码，产出 Token 流。
 * 支持中文关键字、英文/中文标识符、数字、字符串、字符字面量、注释、
 * 运算符（含 ++ -- += 等双字符、位运算 & | ^ ~ << >>、三元 ?）、点号（成员访问）。
 */

enum class TokenType {
    // 类型关键字
    KW_SET,        // 设
    KW_CONST,      // 常量
    KW_INT,        // 整数
    KW_FLOAT,      // 浮点
    KW_DOUBLE,     // 小数 / 双浮点
    KW_CHAR,       // 字符
    KW_BOOL,       // 布尔 / 逻辑
    KW_LONG,       // 长整
    KW_SHORT,      // 短整
    KW_STRING,     // 字符串
    KW_UNSIGNED,   // 无符号
    KW_AUTO,       // 自动
    KW_VOID,       // 空

    // 控制流关键字
    KW_FUNC,       // 函数
    KW_IF,         // 如果
    KW_ELSE,       // 否则
    KW_FOR,        // 对于 / 循环
    KW_DO,         // 做
    KW_WHILE,      // 当
    KW_SWITCH,     // 选择
    KW_CASE,       // 情况
    KW_DEFAULT,    // 默认
    KW_BREAK,      // 跳出
    KW_CONTINUE,   // 继续
    KW_RETURN,     // 返回
    KW_OUTPUT,     // 输出

    // 字面量关键字
    KW_TRUE,       // 真
    KW_FALSE,      // 假
    KW_NULLPTR,    // 空指针

    // 复合类型关键字
    KW_STRUCT,     // 结构体
    KW_ENUM,       // 枚举
    KW_MAP,        // 字典 / 映射
    KW_IN,         // 于 / 在（for-each）
    KW_REF,        // 引用（函数引用参数）
    KW_TERN,       // 若（三元）
    KW_THEN,       // 则（三元）

    IDENT,         // 标识符（支持英文与中文）
    NUMBER,        // 数字字面量
    STRING,        // 字符串（支持中文）
    CHAR,          // 字符字面量（'A'）
    COMMENT,       // 注释

    // 运算符 / 分隔符
    OP_PLUS, OP_MINUS, OP_STAR, OP_SLASH, OP_PERCENT,
    OP_ASSIGN, OP_EQ, OP_NE, OP_GT, OP_GE, OP_LT, OP_LE,
    OP_INC, OP_DEC,                       // ++ --
    OP_PLUS_ASSIGN, OP_MINUS_ASSIGN,      // += -=
    OP_STAR_ASSIGN, OP_SLASH_ASSIGN,      // *= /=
    OP_AND, OP_OR, OP_NOT,                // && / 并且、|| / 或者、! / 非
    OP_BIT_AND, OP_BIT_OR, OP_BIT_XOR,    // & | ^
    OP_BIT_NOT,                           // ~ / 位非
    OP_SHL, OP_SHR,                       // << 左移、>> 右移
    OP_QUESTION,                          // ?（三元）
    LPAREN, RPAREN, LBRACE, RBRACE, LBRACKET, RBRACKET, SEMICOLON, COMMA, COLON, DOT,

    NEWLINE,       // 换行（语句边界）
    EOF
}

data class Token(
    val type: TokenType,
    val text: String,
    val line: Int,      // 1-based，用于错误定位与行号映射
    val col: Int,
)

/** 词法错误：中文信息 + 行号 */
class LexException(val line: Int, message: String) : Exception("第 $line 行：$message")

object Lexer {

    private val keywords = mapOf(
        "设" to TokenType.KW_SET,
        "常量" to TokenType.KW_CONST,
        // 类型
        "整数" to TokenType.KW_INT,
        "浮点" to TokenType.KW_FLOAT,
        "小数" to TokenType.KW_DOUBLE,
        "双浮点" to TokenType.KW_DOUBLE,
        "字符" to TokenType.KW_CHAR,
        "布尔" to TokenType.KW_BOOL,
        "逻辑" to TokenType.KW_BOOL,
        "长整" to TokenType.KW_LONG,
        "短整" to TokenType.KW_SHORT,
        "字符串" to TokenType.KW_STRING,
        "无符号" to TokenType.KW_UNSIGNED,
        "自动" to TokenType.KW_AUTO,
        "空" to TokenType.KW_VOID,
        // 控制流
        "函数" to TokenType.KW_FUNC,
        "如果" to TokenType.KW_IF,
        "否则" to TokenType.KW_ELSE,
        "对于" to TokenType.KW_FOR,
        "循环" to TokenType.KW_FOR,
        "做" to TokenType.KW_DO,
        "当" to TokenType.KW_WHILE,
        "选择" to TokenType.KW_SWITCH,
        "情况" to TokenType.KW_CASE,
        "默认" to TokenType.KW_DEFAULT,
        "跳出" to TokenType.KW_BREAK,
        "继续" to TokenType.KW_CONTINUE,
        "返回" to TokenType.KW_RETURN,
        "输出" to TokenType.KW_OUTPUT,
        // 字面量
        "真" to TokenType.KW_TRUE,
        "假" to TokenType.KW_FALSE,
        "空指针" to TokenType.KW_NULLPTR,
        // 复合类型
        "结构体" to TokenType.KW_STRUCT,
        "枚举" to TokenType.KW_ENUM,
        "字典" to TokenType.KW_MAP,
        "映射" to TokenType.KW_MAP,
        // for-each / 三元 / 引用参数
        "于" to TokenType.KW_IN,
        "在" to TokenType.KW_IN,
        "引用" to TokenType.KW_REF,
        "若" to TokenType.KW_TERN,
        "则" to TokenType.KW_THEN,
        // 位运算中文词
        "位与" to TokenType.OP_BIT_AND,
        "位或" to TokenType.OP_BIT_OR,
        "位异或" to TokenType.OP_BIT_XOR,
        "位非" to TokenType.OP_BIT_NOT,
        "左移" to TokenType.OP_SHL,
        "右移" to TokenType.OP_SHR,
    )

    /** 中文运算符词：作为独立词出现时（前后留空或标点）识别，避免误切含关键字的标识符 */
    private val operatorWords = mapOf(
        "并且" to TokenType.OP_AND,
        "且" to TokenType.OP_AND,
        "或者" to TokenType.OP_OR,
        "或" to TokenType.OP_OR,
        "非" to TokenType.OP_NOT,
    )

    fun tokenize(src: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        var line = 1
        var col = 1
        val n = src.length

        fun isIdentStart(c: Char) = c.isLetter() || c == '_' || c.code > 0x2E7F // 含中文区
        fun isIdentPart(c: Char) = c.isLetterOrDigit() || c == '_' || c.code > 0x2E7F

        while (i < n) {
            val c = src[i]
            val startLine = line
            val startCol = col

            // 换行
            if (c == '\n') {
                tokens.add(Token(TokenType.NEWLINE, "\\n", startLine, startCol))
                i++; line++; col = 1
                continue
            }
            if (c == '\r') { i++; continue }

            // 空白
            if (c == ' ' || c == '\t') { i++; col++; continue }

            // 注释：// 与 /* */
            if (c == '/' && i + 1 < n) {
                when (src[i + 1]) {
                    '/' -> {
                        val sb = StringBuilder()
                        while (i < n && src[i] != '\n') { sb.append(src[i]); i++; col++ }
                        tokens.add(Token(TokenType.COMMENT, sb.toString(), startLine, startCol))
                        continue
                    }
                    '*' -> {
                        val sb = StringBuilder("/*")
                        i += 2; col += 2
                        var closed = false
                        while (i < n) {
                            if (src[i] == '\n') { line++; col = 1 }
                            else col++
                            if (src[i] == '*' && i + 1 < n && src[i + 1] == '/') {
                                sb.append("*/"); i += 2; col += 2; closed = true; break
                            }
                            sb.append(src[i]); i++
                        }
                        if (!closed) throw LexException(startLine, "块注释未闭合")
                        tokens.add(Token(TokenType.COMMENT, sb.toString(), startLine, startCol))
                        continue
                    }
                }
            }

            // 字符串
            if (c == '"') {
                val sb = StringBuilder()
                i++; col++
                var closed = false
                while (i < n) {
                    val ch = src[i]
                    if (ch == '\n') throw LexException(startLine, "字符串未闭合")
                    if (ch == '\\') {
                        if (i + 1 >= n) throw LexException(startLine, "非法转义符")
                        when (val esc = src[i + 1]) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t')
                            '"' -> sb.append('"'); '\\' -> sb.append('\\')
                            else -> throw LexException(startLine, "不支持的转义符 \\$esc")
                        }
                        i += 2; col += 2
                        continue
                    }
                    if (ch == '"') { i++; col++; closed = true; break }
                    sb.append(ch)
                    col++ // 中文按 1 列计，简化
                    i++
                }
                if (!closed) throw LexException(startLine, "字符串未闭合")
                tokens.add(Token(TokenType.STRING, sb.toString(), startLine, startCol))
                continue
            }

            // 字符字面量：'A'、'\n'
            if (c == '\'') {
                i++; col++
                var value = ""
                var closed = false
                while (i < n) {
                    val ch = src[i]
                    if (ch == '\n') throw LexException(startLine, "字符字面量未闭合")
                    if (ch == '\\') {
                        if (i + 1 >= n) throw LexException(startLine, "非法转义符")
                        when (val esc = src[i + 1]) {
                            'n' -> value = "\n"; 't' -> value = "\t"
                            '0' -> value = "\u0000"
                            '\'' -> value = "'"; '\\' -> value = "\\"
                            else -> throw LexException(startLine, "不支持的转义符 \\$esc")
                        }
                        i += 2; col += 2
                        continue
                    }
                    if (ch == '\'') { i++; col++; closed = true; break }
                    value += ch
                    col++; i++
                }
                if (!closed) throw LexException(startLine, "字符字面量未闭合")
                if (value.isEmpty()) throw LexException(startLine, "字符字面量不能为空")
                if (value.length > 1) throw LexException(startLine, "字符字面量只能放一个字符（字符串请用双引号）")
                tokens.add(Token(TokenType.CHAR, value, startLine, startCol))
                continue
            }

            // 数字
            if (c.isDigit()) {
                val sb = StringBuilder()
                var isFloat = false
                while (i < n && (src[i].isDigit() || (src[i] == '.' && !isFloat && i + 1 < n && src[i + 1].isDigit()))) {
                    if (src[i] == '.') isFloat = true
                    sb.append(src[i]); i++; col++
                }
                tokens.add(Token(TokenType.NUMBER, sb.toString(), startLine, startCol))
                continue
            }

            // 标识符 / 关键字 / 中文运算符词（整词最长匹配）
            if (isIdentStart(c)) {
                val sb = StringBuilder()
                while (i < n && isIdentPart(src[i])) { sb.append(src[i]); i++; col++ }
                val word = sb.toString()
                val t = keywords[word] ?: operatorWords[word]
                tokens.add(Token(t ?: TokenType.IDENT, word, startLine, startCol))
                continue
            }

            // 运算符（含双字符 ++ -- += -= *= /=；类型 + 长度）
            val op: Pair<TokenType, Int>? = when (c) {
                '+' -> when {
                    i + 1 < n && src[i + 1] == '+' -> TokenType.OP_INC to 2
                    i + 1 < n && src[i + 1] == '=' -> TokenType.OP_PLUS_ASSIGN to 2
                    else -> TokenType.OP_PLUS to 1
                }
                '-' -> when {
                    i + 1 < n && src[i + 1] == '-' -> TokenType.OP_DEC to 2
                    i + 1 < n && src[i + 1] == '=' -> TokenType.OP_MINUS_ASSIGN to 2
                    else -> TokenType.OP_MINUS to 1
                }
                '*' -> if (i + 1 < n && src[i + 1] == '=') TokenType.OP_STAR_ASSIGN to 2 else TokenType.OP_STAR to 1
                '/' -> if (i + 1 < n && src[i + 1] == '=') TokenType.OP_SLASH_ASSIGN to 2 else TokenType.OP_SLASH to 1
                '%' -> TokenType.OP_PERCENT to 1
                '(' -> TokenType.LPAREN to 1
                ')' -> TokenType.RPAREN to 1
                '{' -> TokenType.LBRACE to 1
                '}' -> TokenType.RBRACE to 1
                '[' -> TokenType.LBRACKET to 1
                ']' -> TokenType.RBRACKET to 1
                ';' -> TokenType.SEMICOLON to 1
                ',' -> TokenType.COMMA to 1
                ':' -> TokenType.COLON to 1
                '.' -> TokenType.DOT to 1
                '=' -> if (i + 1 < n && src[i + 1] == '=') TokenType.OP_EQ to 2 else TokenType.OP_ASSIGN to 1
                '!' -> if (i + 1 < n && src[i + 1] == '=') TokenType.OP_NE to 2 else TokenType.OP_NOT to 1
                '>' -> when {
                    i + 1 < n && src[i + 1] == '=' -> TokenType.OP_GE to 2
                    i + 1 < n && src[i + 1] == '>' -> TokenType.OP_SHR to 2
                    else -> TokenType.OP_GT to 1
                }
                '<' -> when {
                    i + 1 < n && src[i + 1] == '=' -> TokenType.OP_LE to 2
                    i + 1 < n && src[i + 1] == '<' -> TokenType.OP_SHL to 2
                    else -> TokenType.OP_LT to 1
                }
                '&' -> if (i + 1 < n && src[i + 1] == '&') TokenType.OP_AND to 2 else TokenType.OP_BIT_AND to 1
                '|' -> if (i + 1 < n && src[i + 1] == '|') TokenType.OP_OR to 2 else TokenType.OP_BIT_OR to 1
                '^' -> TokenType.OP_BIT_XOR to 1
                '~' -> TokenType.OP_BIT_NOT to 1
                '?' -> TokenType.OP_QUESTION to 1
                else -> null
            }
            if (op != null) {
                val (t, len) = op
                tokens.add(Token(t, src.substring(i, i + len), startLine, startCol))
                i += len; col += len
                continue
            }

            throw LexException(startLine, "无法识别的字符「$c」")
        }

        tokens.add(Token(TokenType.EOF, "", line, col))
        return tokens
    }
}
