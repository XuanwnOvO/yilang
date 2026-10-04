package com.yilang.model

import java.io.File

/** 蚁语言源文件 */
data class YiFile(
    val name: String,
    val file: File,
) {
    fun readContent(): String = try { file.readText(Charsets.UTF_8) } catch (e: Exception) { "" }
    fun writeContent(content: String) {
        file.parentFile?.mkdirs()
        file.writeText(content, Charsets.UTF_8)
    }
}

/** 转译结果：成功带 cpp 代码与是否含 main 体，失败带中文错误与行号 */
sealed class TranspileResult {
    data class Success(val cppCode: String, val hasMainBody: Boolean = true) : TranspileResult()
    data class Error(val line: Int, val message: String) : TranspileResult()
}

/** 编译结果（Phase 3 远程/本地编译用） */
sealed class CompileResult {
    data class Success(val output: String) : CompileResult()
    data class Failure(val log: String, val ylLine: Int? = null) : CompileResult()
}
