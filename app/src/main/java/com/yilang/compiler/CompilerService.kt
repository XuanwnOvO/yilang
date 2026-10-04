package com.yilang.compiler

import android.content.Context
import android.os.SystemClock
import android.system.Os
import com.yilang.model.CompileResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 编译执行内核（路线 B：设备端真 C++ 工具链，运行时从 Termux 仓库下载）。
 * 流程：工具链就绪（首次自动下载约 69MB）→ clang++ 编译转译产物 → 运行并逐行回调输出。
 *
 * 提速三板斧（示例代码从十几秒压到秒级）：
 *  1. 预编译头（PCH）：标准库头只解析一次（首次构建数秒，之后复用），单次编译大幅变快
 *  2. 对象缓存：.o 按源码内容哈希缓存，没改动的文件重跑时跳过编译；可执行产物同理
 *  3. 阶段计时：终端显示 编译/链接/运行 各阶段耗时，等待不再黑盒
 */
object CompilerService {

    private const val COMPILE_TIMEOUT_SEC = 120L
    private const val RUN_TIMEOUT_SEC = 30L

    /** 预编译头前置头：转译产物固定包含的 9 个头（CodeGenerator）+ 最常用的 <algorithm> */
    private val PCH_PREFIX = """
        #include <algorithm>
        #include <cctype>
        #include <cmath>
        #include <cstdio>
        #include <cstdlib>
        #include <cstring>
        #include <iostream>
        #include <map>
        #include <string>
        #include <vector>
    """.trimIndent()

    /**
     * C++ 源码（可多个翻译单元）→ 编译 → 运行。
     * @param cppSources (源文件名, cpp 代码) 列表，首个为入口
     * @param stdin 运行期标准输入队列（null = 无交互）；put 一行即写入子进程
     * @param onLog 阶段/输出日志回调（IO 线程回调，调用方自行切线程）
     * @param includeDir 额外头文件搜索目录（如项目目录，支持 #include "本地头文件"）
     */
    suspend fun buildAndRun(
        ctx: Context,
        cppSources: List<Pair<String, String>>,
        onLog: (String) -> Unit,
        stdin: LinkedBlockingQueue<String>? = null,
        includeDir: File? = null,
    ): CompileResult = withContext(Dispatchers.IO) {
        if (cppSources.isEmpty()) return@withContext CompileResult.Failure("没有可编译的源码。")

        // 1. 工具链就绪检查（下载统一在项目页首启弹窗完成，带进度条）
        if (!ToolchainInstaller.isInstalled(ctx)) {
            return@withContext CompileResult.Failure(
                "编译工具链尚未下载：请回到「项目」页（重新打开应用即可），按弹窗提示完成首次下载（约 69MB，仅一次）。",
            )
        }
        val root = ToolchainInstaller.toolchainRoot(ctx)
        val clangFile = File(root, "bin/clang++")
        val clang = clangFile.absolutePath
        val buildDir = File(ctx.cacheDir, "build").apply { mkdirs() }
        val env = mapOf(
            "LD_LIBRARY_PATH" to File(root, "lib").absolutePath,
            "TMPDIR" to ctx.cacheDir.absolutePath,
            "HOME" to ctx.cacheDir.absolutePath,
        )

        // 2. 预编译头：跳过标准库头解析，这是单次编译耗时的最大头
        val pch = ensurePch(ctx, root, clangFile, env, onLog)

        // 3. 逐翻译单元编译：.o 按源码内容哈希缓存，未改动的文件直接复用
        val ocache = File(ctx.cacheDir, "ocache").apply { mkdirs() }
        val tCompile = SystemClock.elapsedRealtime()
        val objs = ArrayList<File>(cppSources.size)
        var reused = 0
        for ((name, code) in cppSources) {
            // clang 身份与 includeDir 混入 key：换 clang 版本或不同项目（头文件解析不同）都不串缓存
            val obj = File(
                ocache,
                sha256(code + "@" + clangFile.length() + "@" + (includeDir?.absolutePath ?: "")) + ".o",
            )
            if (obj.isFile) { reused++; objs.add(obj); continue }
            val src = File(buildDir, sanitizeBase(name) + ".cpp").apply {
                writeText(code, Charsets.UTF_8)
            }
            val args = clangArgs(clang, root)
            if (includeDir != null) args += "-I${includeDir.absolutePath}"
            if (pch != null) args += listOf("-include-pch", pch.absolutePath)
            args += listOf("-c", src.absolutePath, "-o", obj.absolutePath)
            val (c, compileTimeout) = exec(args, env, COMPILE_TIMEOUT_SEC, onLog)
            when {
                compileTimeout -> {
                    obj.delete() // 强杀时 .o 可能是半成品，不能留给下次误命中
                    return@withContext CompileResult.Failure(
                        "编译超时（$COMPILE_TIMEOUT_SEC 秒），已终止。",
                    )
                }
                c != 0 -> return@withContext CompileResult.Failure(
                    "编译失败（$name，clang++ 退出码 $c），见上方错误信息。",
                )
                !obj.isFile -> return@withContext CompileResult.Failure(
                    "编译失败（$name）：未生成目标文件。",
                )
            }
            objs.add(obj)
        }
        val compileMs = SystemClock.elapsedRealtime() - tCompile
        if (reused > 0) onLog("（编译缓存：$reused/${cppSources.size} 个文件命中）")

        // 4. 链接：产物按「全部 .o 指纹」缓存——代码没变时连链接都跳过
        val exe = File(ocache, "exe-" + sha256(objs.joinToString("\n") { it.name }))
        val linkMs: Long
        if (exe.isFile) {
            linkMs = 0
        } else {
            val tLink = SystemClock.elapsedRealtime()
            // Termux 布局的存根库（libc/libm/liblog…）不带 API 级别子目录，
            // 显式 -l 才能让驱动为 bionic 链上这些库（-lm：非内联数学函数；-dl：dlopen）
            val linkArgs = listOf(
                "-L${File(root, "lib").absolutePath}",
                "-L${File(root, "usr/lib/aarch64-linux-android").absolutePath}",
                "-lc", "-lm", "-ldl",
            )
            val (lcode, ltimeout) = exec(
                clangArgs(clang, root) +
                    objs.map { it.absolutePath } + listOf("-o", exe.absolutePath) + linkArgs,
                env, COMPILE_TIMEOUT_SEC, onLog,
            )
            when {
                ltimeout -> return@withContext CompileResult.Failure(
                    "链接超时（$COMPILE_TIMEOUT_SEC 秒），已终止。",
                )
                lcode != 0 -> {
                    exe.delete() // 强杀/失败时清掉半成品，避免下次误复用
                    return@withContext CompileResult.Failure(
                        "链接失败（clang++ 退出码 $lcode），见上方错误信息。",
                    )
                }
                !exe.isFile -> return@withContext CompileResult.Failure("链接失败：未生成可执行文件。")
            }
            linkMs = SystemClock.elapsedRealtime() - tLink
        }

        // 5. 运行产物（targetSdk 28 允许执行应用数据目录可执行文件）
        try {
            Os.chmod(exe.absolutePath, 0b111101101) // 755
        } catch (_: Exception) { /* chmod 失败时交给 linker 报错提示 */ }
        val tRun = SystemClock.elapsedRealtime()
        val (runCode, runTimeout) = exec(listOf(exe.absolutePath), env, RUN_TIMEOUT_SEC, onLog, stdin)
        val runMs = SystemClock.elapsedRealtime() - tRun
        when {
            runTimeout -> return@withContext CompileResult.Failure(
                "运行超时（$RUN_TIMEOUT_SEC 秒），已终止。",
            )
            runCode != 0 -> return@withContext CompileResult.Failure(
                "程序异常退出（退出码 $runCode）。",
            )
        }
        // 成功输出已在运行中逐行回调；末尾附各阶段耗时（缓存全命中时编译/链接接近 0）
        onLog("（编译 ${fmtMs(compileMs)} · 链接 ${fmtMs(linkMs)} · 运行 ${fmtMs(runMs)}）")
        CompileResult.Success("")
    }

    /** clang++ 驱动公共参数：目标三元组 + sysroot（usr 兼容链接在安装时建好） */
    private fun clangArgs(clang: String, root: File) = mutableListOf(
        clang,
        "--target=aarch64-linux-android26",
        "--sysroot=${root.absolutePath}",
    )

    /**
     * 预编译头（PCH）：把转译产物固定包含的标准库头预先解析一次。
     * 缓存键绑定 前置头内容 + clang 身份（大小/改动时间），任一变化即重建。
     * 构建失败不致命：提示后退回无 PCH 的普通编译。
     */
    private fun ensurePch(
        ctx: Context,
        root: File,
        clangFile: File,
        env: Map<String, String>,
        onLog: (String) -> Unit,
    ): File? {
        val pchDir = File(ctx.cacheDir, "pch").apply { mkdirs() }
        val key = sha256(PCH_PREFIX + "|" + clangFile.length() + "|" + clangFile.lastModified())
        val pch = File(pchDir, "$key.pch")
        if (pch.isFile) return pch
        val prefix = File(pchDir, "yi_prefix.h").apply { writeText(PCH_PREFIX) }
        onLog("首次构建预编译头（仅一次，之后复用）…")
        val (code, timeout) = exec(
            clangArgs(clangFile.absolutePath, root) +
                listOf("-x", "c++-header", prefix.absolutePath, "-o", pch.absolutePath),
            env, COMPILE_TIMEOUT_SEC, onLog,
        )
        if (timeout || code != 0 || !pch.isFile) {
            onLog("预编译头不可用，按普通方式编译")
            return null
        }
        return pch
    }

    /** 执行命令：实时逐行回调输出，超时强杀。返回 (退出码, 是否超时)。
     *  stdin 非空时启动 feeder 线程：队列每出现一行就写入子进程并 flush。 */
    private fun exec(
        cmd: List<String>,
        env: Map<String, String>,
        timeoutSec: Long,
        onLine: (String) -> Unit,
        stdin: LinkedBlockingQueue<String>? = null,
    ): Pair<Int, Boolean> {
        val proc = ProcessBuilder(cmd).apply {
            environment().putAll(env)
            redirectErrorStream(true)
        }.start()
        val pump = Thread {
            try {
                proc.inputStream.bufferedReader(Charsets.UTF_8).forEachLine(onLine)
            } catch (_: Exception) { /* 进程被强杀时流会异常，忽略 */ }
        }.apply { isDaemon = true; start() }
        if (stdin != null) {
            Thread {
                try {
                    val out = proc.outputStream
                    while (proc.isAlive) {
                        val line = stdin.poll(150, TimeUnit.MILLISECONDS) ?: continue
                        out.write((line + "\n").toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                } catch (_: Exception) { /* 进程退出后写流会异常，忽略 */ }
            }.apply { isDaemon = true; start() }
        }
        val finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            pump.join(2000)
            return -1 to true
        }
        runCatching { proc.outputStream.close() }
        pump.join(2000)
        return proc.exitValue() to false
    }

    /** 源文件名 → 合法产物名（仅字母数字与 . _ -，其余转下划线） */
    private fun sanitizeBase(sourceName: String): String {
        val base = sourceName.substringBeforeLast('.')
        return "[^A-Za-z0-9_.-]".toRegex().replace(base, "_").ifBlank { "main" }
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun fmtMs(ms: Long): String =
        if (ms < 1000) "${ms}ms" else String.format(Locale.US, "%.1fs", ms / 1000.0)
}
