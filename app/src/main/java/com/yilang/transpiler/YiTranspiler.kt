package com.yilang.transpiler

import com.yilang.model.TranspileResult

/**
 * 蚁语言 → C++ 转译器（对外入口）
 *
 * 用法：
 *   YiTranspiler.transpile(source)  // .yl 源码 → .cpp 源码 / 中文错误
 *
 * 流程：.yl → Lexer → Token 流 → Parser → AST → CodeGenerator → .cpp
 */
object YiTranspiler {

    fun transpile(source: String, sourcePath: String? = null): TranspileResult {
        return try {
            val tokens = Lexer.tokenize(source)
            val ast = Parser(tokens).parseProgram()
            val gen = CodeGenerator(sourcePath)
            val cpp = gen.generate(ast)
            TranspileResult.Success(cpp, gen.hasMainBody)
        } catch (e: LexException) {
            TranspileResult.Error(e.line, e.message ?: "词法错误")
        } catch (e: ParseException) {
            TranspileResult.Error(e.line, e.message ?: "语法错误")
        } catch (e: Exception) {
            TranspileResult.Error(0, "内部错误：${e.message}")
        }
    }

    /** 便捷方法：直接拿 cpp 文本，失败时返回带注释的错误信息 */
    fun transpileOrComment(source: String): String {
        return when (val r = transpile(source)) {
            is TranspileResult.Success -> r.cppCode
            is TranspileResult.Error -> "// 蚁语言转译失败：${r.message}\n// 错误行：${r.line}\n"
        }
    }
}
