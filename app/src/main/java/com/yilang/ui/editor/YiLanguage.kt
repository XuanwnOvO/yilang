package com.yilang.ui.editor

import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager
import io.github.rosemoe.sora.lang.analysis.SimpleAnalyzeManager
import io.github.rosemoe.sora.lang.styling.MappedSpans
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.lang.styling.TextStyle
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/**
 * 蚁语言高亮（YiLanguage）
 *
 * Token 分类：
 * - KEYWORD：中文关键字（设/整数/浮点/字符/空/函数/如果/否则/当/返回/输出）
 * - IDENTIFIER_NAME：变量名（英文 + 中文）
 * - LITERAL：数字字面量 与 字符串（支持中文）
 * - COMMENT：// 与  注释
 * - OPERATOR：+ - * / = > < ( ) { } ; 等
 */
class YiLanguage : EmptyLanguage() {

    companion object {
        // 长词在前，保证最长匹配
        val KEYWORDS = listOf(
            "双浮点", "字符串", "无符号", "空指针", "结构体", "枚举",
            "常量", "整数", "浮点", "小数", "字符", "布尔", "逻辑", "长整", "短整", "自动",
            "函数", "如果", "否则", "对于", "循环", "选择", "情况", "默认", "跳出", "继续",
            "返回", "输出", "并且", "或者",
            "设", "空", "当", "做", "真", "假", "非", "且", "或",
        )
    }

    private val manager = YiAnalyzeManager()

    override fun getAnalyzeManager(): AnalyzeManager = manager
}

/**
 * 轻量扫描：整文扫描一遍，产出高亮 Span。
 *
 * MappedSpans.Builder 的写入规则（经字节码核实）：
 * - addIfNeeded(line, col, style) 必须按行号递增顺序调用，行号回退会抛异常；
 * - 列号为 0 时会先清空该行已有 Span，再写入；
 * - 因此每行行首先铺一层 TEXT_NORMAL，再由各 token 按列覆盖。
 */
class YiAnalyzeManager : SimpleAnalyzeManager<Unit>() {

    private lateinit var builder: MappedSpans.Builder
    private var line = 0
    private var lineStart = 0

    private fun isIdentStart(c: Char) = c.isLetter() || c == '_' || c.code > 0x2E7F
    private fun isIdentPart(c: Char) = c.isLetterOrDigit() || c == '_' || c.code > 0x2E7F

    override fun analyze(
        content: StringBuilder,
        delegate: SimpleAnalyzeManager<Unit>.Delegate<Unit>,
    ): Styles {
        builder = MappedSpans.Builder()
        val src = content.toString()
        val n = src.length
        line = 0
        lineStart = 0
        markLineStart()

        var i = 0
        while (i < n) {
            val c = src[i]

            // 换行：进入下一行
            if (c == '\n') {
                line++
                lineStart = i + 1
                markLineStart()
                i++
                continue
            }

            // 行注释 //
            if (c == '/' && i + 1 < n && src[i + 1] == '/') {
                val end = src.indexOf('\n', i).let { if (it == -1) n else it }
                styleRange(src, i, end, EditorColorScheme.COMMENT)
                i = end
                continue
            }
            // 块注释 /* */（可跨行）
            if (c == '/' && i + 1 < n && src[i + 1] == '*') {
                val end = src.indexOf("*/", i + 2).let { if (it == -1) n else it + 2 }
                styleRange(src, i, end, EditorColorScheme.COMMENT)
                i = end
                continue
            }
            // 字符串（支持中文，处理转义）
            if (c == '"') {
                var j = i + 1
                while (j < n && src[j] != '\n') {
                    if (src[j] == '\\') j++
                    else if (src[j] == '"') break
                    j++
                }
                val end = if (j < n && src[j] == '"') (j + 1).coerceAtMost(n) else j
                styleRange(src, i, end, EditorColorScheme.LITERAL)
                i = end
                continue
            }
            // 数字
            if (c.isDigit()) {
                var j = i
                while (j < n && (src[j].isDigit() || (src[j] == '.' && j + 1 < n && src[j + 1].isDigit()))) j++
                styleRange(src, i, j, EditorColorScheme.LITERAL)
                i = j
                continue
            }
            // 标识符 / 中文关键字（在连续词内做最长关键词匹配切分）
            if (isIdentStart(c)) {
                var j = i
                while (j < n && isIdentPart(src[j])) j++
                var k = i
                while (k < j) {
                    val kwLen = matchKeyword(src, k, j)
                    if (kwLen > 0) {
                        styleRange(src, k, k + kwLen, EditorColorScheme.KEYWORD)
                        k += kwLen
                    } else {
                        // 标识符段：推进到下一个关键字起点或词尾
                        var e = k + 1
                        while (e < j && matchKeyword(src, e, j) == 0) e++
                        styleRange(src, k, e, EditorColorScheme.IDENTIFIER_NAME)
                        k = e
                    }
                }
                i = j
                continue
            }
            // 运算符等非空白字符
            if (!c.isWhitespace()) {
                styleRange(src, i, i + 1, EditorColorScheme.OPERATOR)
            }
            i++
        }

        builder.addNormalIfNull()
        return Styles(builder.build())
    }

    /** 每行行首先铺一层普通样式（列 0 写入会清空该行，保证不会残留上一行的样式） */
    private fun markLineStart() {
        builder.addIfNeeded(line, 0, TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL))
    }

    /**
     * 给 [start, end) 加样式。区间可跨行（如块注释）：
     * 起点按当前行列号写入，区间内的每个换行后续行从列 0 续写同色。
     * 调用后 line / lineStart 保持与 end 所在行一致。
     */
    private fun styleRange(src: String, start: Int, end: Int, colorId: Int) {
        val style = TextStyle.makeStyle(colorId)
        builder.addIfNeeded(line, start - lineStart, style)
        var nl = src.indexOf('\n', start)
        while (nl != -1 && nl + 1 < end) {
            line++
            lineStart = nl + 1
            builder.addIfNeeded(line, 0, style)
            nl = src.indexOf('\n', lineStart)
        }
    }

    /** 最长匹配关键字，返回匹配长度（0 = 不匹配） */
    private fun matchKeyword(src: String, from: Int, until: Int): Int {
        for (kw in YiLanguage.KEYWORDS) {
            val end = from + kw.length
            if (end <= until && src.regionMatches(from, kw, 0, kw.length)) return kw.length
        }
        return 0
    }
}
