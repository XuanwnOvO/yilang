package com.yilang.compiler

import android.content.Context
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLongArray

/**
 * Termux aarch64 stable 仓库固定版本的 .deb 包（clang 21.1.8 工具链全量依赖闭包）。
 * 版本/体积/SHA256 提取自仓库索引（开发期存档于 d:\yilang\.toolchain\Packages）。
 *
 * 未包含 llvm 包（llvm-ar/nm/strip 等辅助工具，约 70MB）：
 * 单文件「编译 + 链接」只需要 clang++ 与 ld.lld，不需要这些工具。
 */
data class ToolchainPackage(
    val name: String,
    val path: String,   // 仓库内 pool 路径
    val sha256: String,
    val sizeBytes: Long,
)

/** 安装进度：message 供文字展示，downloadedBytes/totalBytes 驱动进度条 */
data class ToolchainProgress(
    val message: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
)

object ToolchainCatalog {
    /** 双源：清华 TUNA 镜像优先（国内直连快），官方源兜底 */
    val mirrors = listOf(
        "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main/",
        "https://packages.termux.dev/apt/termux-main/",
    )

    val packages = listOf(
        // ── 编译器本体 ──
        ToolchainPackage("clang", "pool/main/c/clang/clang_21.1.8-3_aarch64.deb",
            "9fb565013b96ce10abd8f8f217aaebb48f72873f53acbf8886329bcea841a21c", 30_740_228L),
        ToolchainPackage("libllvm", "pool/main/libl/libllvm/libllvm_21.1.8-3_aarch64.deb",
            "5056a73f2645fc9c0759e6f94a1bd6edcf36305acaef58dc685211ced9cc2a02", 30_733_892L),
        ToolchainPackage("lld", "pool/main/l/lld/lld_21.1.8-3_aarch64.deb",
            "6299c8fdff59dcff972f6b444805bcdb211c2bd797a64d2ddb3875e699f30630", 2_812_212L),
        ToolchainPackage("libcompiler-rt", "pool/main/libc/libcompiler-rt/libcompiler-rt_21.1.8-3_aarch64.deb",
            "89de02f642e34fa6494ad16105bd08510bd1dbdbe4fdb2b297aaa4f4e8e534b6", 3_024_604L),
        // ── sysroot：Bionic 头文件 + crt 启动对象 ──
        ToolchainPackage("ndk-sysroot", "pool/main/n/ndk-sysroot/ndk-sysroot_30_aarch64.deb",
            "73502f9b04fce210c430c83bf9ae2b8e332ba5c319f3eb94a61ba5cef090f787", 1_996_888L),
        // ── NDK 多库层：crt 对象 + libc/libm/liblog 等存根库 + libc++_shared（实证于 ndk-multilib_30 deb）──
        ToolchainPackage("ndk-multilib", "pool/main/n/ndk-multilib/ndk-multilib_30_all.deb",
            "bf5ed9c7176786bf71d8c2a64e7395aaa54f7469229ace5de7bd14e8b68819c8", 423_064L),
        // ── 运行库（libLLVM / clang 的动态依赖 + 生成程序的 C++ 运行时）──
        ToolchainPackage("libc++", "pool/main/libc/libc++/libc++_30_aarch64.deb",
            "53d0b84a7ba7459024257cb94d5b136fe13ef858567f65a8064b35950799f2ca", 341_604L),
        ToolchainPackage("libffi", "pool/main/libf/libffi/libffi_3.8.0_aarch64.deb",
            "4f255badf74cd31f6a2801c17fa1444199c84c834b517b7c843e2fe9ebe91d77", 37_592L),
        ToolchainPackage("libxml2", "pool/main/libx/libxml2/libxml2_2.15.4-1_aarch64.deb",
            "d315861bbcf716cfb088f53cddab9fb6a96430cc66769f79774cc69c2f53bcff", 442_652L),
        ToolchainPackage("libiconv", "pool/main/libi/libiconv/libiconv_1.19_aarch64.deb",
            "fe9481b1dc101c6c3552943f25435109fd522aecc615ae49594f9fbee863bb37", 562_724L),
        ToolchainPackage("libandroid-glob", "pool/main/liba/libandroid-glob/libandroid-glob_0.6-3_aarch64.deb",
            "2276ae8adedf0db76c2f4ffc94cc4cceb2f4f5d78e021b54e2e046d1233e7826", 7_032L),
        ToolchainPackage("ncurses", "pool/main/n/ncurses/ncurses_6.6.20260307+really6.5.20250830_aarch64.deb",
            "f44bbfdc3d42ec0217bffa978309390e59cea5a48a9a83226d4a496c42ad0b99", 557_792L),
        ToolchainPackage("zlib", "pool/main/z/zlib/zlib_1.3.2_aarch64.deb",
            "75e7d0af17fcc3b40004309fdc00a1ddb9ae08346dce5e269902c34ac3966ac9", 62_840L),
        ToolchainPackage("zstd", "pool/main/z/zstd/zstd_1.5.7-1_aarch64.deb",
            "e1b4a5113648da8de189620ba1fce74c48b2d0833d9043391b9a1c91fb606fd3", 360_488L),
        ToolchainPackage("liblzma", "pool/main/libl/liblzma/liblzma_5.8.4_aarch64.deb",
            "44e95e6e60dddb3705e60a344a4f009a1085a210797342b721eaff41b44033c0", 194_796L),
    )
}

/**
 * 工具链安装器：下载（双源 + SHA256 校验）→ 解 .deb（ar）→ 解 data.tar.xz → 解 tar → chmod。
 *
 * 目录布局：filesDir/toolchain/{bin, lib, include, share}（Termux 包即 $PREFIX 相对布局）
 * 调用约定（编译内核使用）：
 *   <root>/bin/clang++ --target=aarch64-linux-android26 --sysroot=<root> 源.cpp -o 输出
 */
object ToolchainInstaller {

    fun toolchainRoot(ctx: Context): File = File(ctx.filesDir, "toolchain")

    fun isInstalled(ctx: Context): Boolean {
        val root = toolchainRoot(ctx)
        return File(root, "bin/clang").let { it.exists() && it.canExecute() } &&
                File(root, "bin/ld.lld").exists() &&
                File(root, "include/stdio.h").exists() &&
                // clang Android 驱动按 NDK 约定查 <sysroot>/usr/...，需 usr 兼容链接在位
                File(root, "usr/include/stdio.h").exists() &&
                File(root, "usr/lib/aarch64-linux-android/crtbegin_dynamic.o").exists()
    }

    /**
     * 确保工具链就绪；已装直接返回，未装则下载解压（耗时操作，须在协程中调用）。
     * onProgress 携带阶段文案与字节进度（总量 = 全部 14 包之和，恒定已知），可驱动进度条。
     * 4 路并发下载 + 每包独立解压（流水线化），国内清华镜像优先。
     */
    suspend fun ensureInstalled(
        ctx: Context,
        onProgress: (ToolchainProgress) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val root = toolchainRoot(ctx)
        if (isInstalled(ctx)) return@withContext root
        // 清掉历史残留（旧布局半成品/上次流错位产生的垃圾文件），确保干净安装
        if (root.exists()) root.deleteRecursively()

        val debDir = File(ctx.cacheDir, "toolchain-deb").apply { mkdirs() }
        val pkgs = ToolchainCatalog.packages
        val total = pkgs.sumOf { it.sizeBytes }
        val count = pkgs.size
        val pkgBytes = AtomicLongArray(count)   // 各包已下载字节（并发写）
        val doneCount = AtomicInteger(0)        // 已完成（下载+解压）包数

        fun report(msg: String) {
            var sum = 0L
            for (i in 0 until count) sum += pkgBytes[i]
            onProgress(ToolchainProgress(msg, sum, total))
        }
        report("准备下载 C++ 工具链")

        val gate = Semaphore(4)
        coroutineScope {
            pkgs.mapIndexed { idx, pkg ->
                async {
                    gate.withPermit {
                        val deb = File(debDir, pkg.path.substringAfterLast('/'))
                        if (!deb.exists() || deb.length() != pkg.sizeBytes) {
                            download(pkg, deb) { bytes ->
                                pkgBytes[idx] = bytes
                                report("下载中（${doneCount.get()}/$count）")
                            }
                        }
                        pkgBytes[idx] = pkg.sizeBytes
                        report("解压 ${pkg.name}")
                        // 每包独立临时目录：并发解压时 data.tar.xz 互不覆盖
                        extractDeb(deb, root, File(debDir, "tmp-$idx").apply { mkdirs() })
                        deb.delete()
                        doneCount.incrementAndGet()
                        report("已完成 ${doneCount.get()}/$count")
                    }
                }
            }.awaitAll()
        }

        grantExecBits(root)
        createSysrootCompatLinks(root)
        check(isInstalled(ctx)) {
            "工具链安装不完整（缺少 clang/ld.lld/头文件），请重试下载"
        }
        onProgress(ToolchainProgress("工具链就绪（clang 21.1.8）", total, total))
        root
    }

    /**
     * 建 sysroot 兼容符号链接。实证（ndk-sysroot_30 / ndk-multilib_30 deb 实测）：
     * Termux 包内容落在 $PREFIX 直接层（include/、lib/、<triple>/lib/），
     * 而 clang 的 Android 驱动按 NDK 约定从 --sysroot 推导：
     *   C 头文件    <sysroot>/usr/include
     *   C++ 头文件  <sysroot>/usr/include/c++/v1
     *   crt/存根库  <sysroot>/usr/lib/<triple>/
     * 补 usr→. 自链接 + usr/lib/<triple>→<triple>/lib 后，两种路径体系指向同一批文件。
     * app 私有 ext4 支持符号链接；失败时由调用处报错（无 usr/ 层编译必失败）。
     */
    private fun createSysrootCompatLinks(root: File) {
        val usr = File(root, "usr")
        if (!usr.exists()) {
            runCatching { Files.createSymbolicLink(usr.toPath(), Paths.get(".")) }
                .onFailure { throw IOException("创建 sysroot 链接失败（usr → .）", it) }
        }
        val usrLib = File(usr, "lib")
        usrLib.mkdirs()
        for (triple in listOf("aarch64-linux-android", "arm-linux-androideabi")) {
            val link = File(usrLib, triple)
            if (!link.exists()) {
                runCatching {
                    // 注意：usr→. 使得 usr/lib 物理上就是 lib/，符号链接随物理解析——
                    // 相对目标以 lib/ 为基准只上跳一级：lib/<triple> → ../<triple>/lib
                    Files.createSymbolicLink(link.toPath(), Paths.get("../$triple/lib"))
                }.onFailure { throw IOException("创建 sysroot 链接失败（usr/lib/$triple）", it) }
            }
        }
    }

    /** 依次尝试所有镜像（清华优先），边下边算 SHA256 并回报本包字节进度，校验失败换源 */
    private fun download(
        pkg: ToolchainPackage,
        dest: File,
        onProgress: (Long) -> Unit,
    ) {
        onProgress(0)
        var lastErr: IOException? = null
        for (mirror in ToolchainCatalog.mirrors) {
            if (downloadOnce(mirror + pkg.path, pkg, dest, onProgress)) return
            dest.delete()
            lastErr = IOException("源不可用或校验失败：$mirror")
        }
        throw IOException("下载 ${pkg.name} 失败（清华镜像与官方源均不可达）", lastErr)
    }

    private fun downloadOnce(
        urlStr: String,
        pkg: ToolchainPackage,
        dest: File,
        onProgress: (Long) -> Unit,
    ): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            dest.parentFile?.mkdirs()
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "YiLang/0.1")
            }
            if (conn.responseCode != 200) return false
            val md = MessageDigest.getInstance("SHA-256")
            FileOutputStream(dest).use { out ->
                conn.inputStream.use { ins ->
                    val buf = ByteArray(1 shl 16)
                    var read = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        read += n
                        // 256KB 节流：避免高频回调刷爆 UI 线程
                        if (read - lastReport >= (1 shl 18)) {
                            lastReport = read
                            onProgress(read)
                        }
                    }
                }
            }
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            hex == pkg.sha256
        } catch (e: IOException) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    /** .deb = ar 归档，从中取出 data.tar.xz 成员落盘，再走 tar 解包 */
    private fun extractDeb(deb: File, root: File, tmpDir: File) {
        val dataTarXz = File(tmpDir, "data.tar.xz")
        ArReader(deb).use { raf ->
            require(raf.readString(8) == "!<arch>\n") { "${deb.name} 不是合法 ar 归档" }
            var found = false
            while (!raf.atEnd()) {
                val header = raf.readBytes(60)
                val name = String(header, 0, 16).trim().trimEnd('/')
                val size = String(header, 48, 10).trim().toLong()
                if (name.startsWith("data.tar.xz")) {
                    raf.copyCurrent(size, dataTarXz)
                    found = true
                    break
                }
                raf.skip(size)
            }
            if (!found) throw IOException("${deb.name} 中未找到 data.tar.xz")
        }
        extractTarXz(dataTarXz, root)
        dataTarXz.delete()
    }

    private fun extractTarXz(xzFile: File, root: File) {
        XZInputStream(BufferedInputStream(FileInputStream(xzFile), 1 shl 16)).use { tar ->
            var longName: String? = null
            var paxPath: String? = null
            while (true) {
                val header = ByteArray(512)
                if (!readFully(tar, header)) break
                if (header.all { it.toInt() == 0 }) break

                var name = cutString(header, 0, 100)
                val size = parseOctal(header, 124, 12)
                val type = header[156]
                val linkName = cutString(header, 157, 100)
                // ustar 前缀字段（GNU 格式无此字段，走下方 'L' 长名分支）
                if (String(header, 257, 6) == "ustar\u0000") {
                    val prefix = cutString(header, 345, 155)
                    if (prefix.isNotEmpty()) name = "$prefix/$name"
                }

                when (type) {
                    // GNU 长文件名：数据段才是真实名字，对下一条目生效
                    'L'.code.toByte() -> { longName = drainString(tar, size); skipPad(tar, size); continue }
                    // pax 扩展头：解析 path= 覆盖
                    'x'.code.toByte(), 'X'.code.toByte() -> {
                        paxPath = drainString(tar, size)?.let(::paxPathOf)
                        skipPad(tar, size); continue
                    }
                }
                longName?.let { name = it; longName = null }
                paxPath?.let { name = it; paxPath = null }

                val rel = stripTermuxPrefix(name)
                val skip = SKIP_PREFIXES.any { rel.startsWith(it) } || rel.isEmpty()
                val isFile = type == '0'.code.toByte() || type == 0.toByte()

                when {
                    type == '5'.code.toByte() && !skip -> {
                        val d = File(root, rel)
                        if (d.isFile) d.delete() // 清掉异常残留的同名文件
                        d.mkdirs()
                    }
                    type == '2'.code.toByte() && !skip -> createSymlink(File(root, rel), linkName)
                    type == '1'.code.toByte() && !skip ->
                        createHardlink(File(root, rel), File(root, stripTermuxPrefix(linkName)))
                    isFile && !skip -> {
                        val f = File(root, rel)
                        f.parentFile?.let { p ->
                            if (p.isFile) p.delete()
                            p.mkdirs()
                        }
                        FileOutputStream(f).use { out -> copyLimited(tar, out, size) }
                    }
                }
                // 只有「实际读走数据」的文件条目无需再丢弃；跳过的文件/目录/链接条目
                // 都要丢弃数据段再补齐 512 对齐，否则 tar 流错位（曾致 ENOTDIR 解压崩溃）
                if (!(isFile && !skip) && size > 0) skipFully(tar, size)
                skipPad(tar, size)
            }
        }
    }

    /** 仅 bin/ 下的文件需要执行权限（clang、clang++、lld、ld.lld） */
    private fun grantExecBits(root: File) {
        val bin = File(root, "bin")
        bin.listFiles()?.forEach { f ->
            if (f.isFile) runCatching { Os.chmod(f.absolutePath, 0b111101101) } // 0755
        }
    }

    private fun createSymlink(link: File, target: String) {
        link.parentFile?.mkdirs()
        link.delete()
        runCatching {
            Files.createSymbolicLink(link.toPath(), Paths.get(target))
        }.onFailure {
            // 个别文件系统不支持符号链接：退化为复制目标文件
            val t = File(link.parentFile, stripTermuxPrefix(target))
            if (t.isFile) t.copyTo(link, overwrite = true)
        }
    }

    private fun createHardlink(link: File, target: File) {
        link.parentFile?.mkdirs()
        link.delete()
        if (target.isFile) target.copyTo(link, overwrite = true)
    }

    private fun paxPathOf(pax: String): String? =
        pax.lineSequence().firstOrNull { it.substringAfter(' ', "").startsWith("path=") }
            ?.substringAfter("path=")

    private fun skipPad(tar: InputStream, size: Long) {
        val pad = ((512 - (size % 512)) % 512).toInt()
        if (pad > 0) skipFully(tar, pad.toLong())
    }

    /** 兼容 minSdk 26：不使用 Java 12+ 的 InputStream.skipNBytes，手动循环丢弃 */
    private fun skipFully(tar: InputStream, n: Long) {
        var left = n
        val buf = ByteArray(8192)
        while (left > 0) {
            val n1 = tar.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n1 < 0) throw IOException("tar 数据流提前结束")
            left -= n1
        }
    }

    private fun drainString(tar: InputStream, size: Long): String? {
        if (size <= 0 || size > 1 shl 20) return null
        val buf = ByteArray(size.toInt())
        var off = 0
        while (off < buf.size) {
            val n = tar.read(buf, off, buf.size - off)
            if (n < 0) throw IOException("tar 数据流提前结束")
            off += n
        }
        return String(buf).trimEnd('\u0000', '\n')
    }

    private fun copyLimited(tar: InputStream, out: FileOutputStream, size: Long) {
        val buf = ByteArray(1 shl 16)
        var left = size
        while (left > 0) {
            val n = tar.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw IOException("tar 数据流提前结束")
            out.write(buf, 0, n)
            left -= n
        }
    }

    private fun readFully(tar: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = tar.read(buf, off, buf.size - off)
            if (n < 0) return off == 0
            off += n
        }
        return true
    }

    /** 从 tar 头定长字段取字符串：在首个 NUL 处截断 */
    private fun cutString(h: ByteArray, off: Int, len: Int): String {
        var end = off
        val max = off + len
        while (end < max && h[end] != 0.toByte()) end++
        return String(h, off, end - off)
    }

    /**
     * 剥离 Termux 包内路径前缀，落到工具链根目录的 $PREFIX 相对布局。
     * 实证（zlib_1.3.2 deb 实测）：条目形如 ./data/data/com.termux/files/usr/include/zlib.h，
     * 需剥到 usr/ 之后 → include/zlib.h。硬链接/符号链接目标同路径体系，一并处理。
     * ndk-multilib 特例：opt/ndk-multilib/<triple>/lib → <triple>/lib
     * （链接期按 NDK 约定查 <sysroot>/usr/lib/<triple>，usr 链接落点应为根下 <triple>/）。
     */
    private val termuxPrefix = "data/data/com.termux/files/usr/"
    private fun stripTermuxPrefix(name: String): String {
        var rel = name.trim().removePrefix("./")
        while (rel.startsWith("/")) rel = rel.substring(1)
        if (rel.startsWith(termuxPrefix)) {
            rel = rel.substring(termuxPrefix.length)
        } else if (rel.startsWith("data/data/com.termux/files/")) {
            rel = rel.substring("data/data/com.termux/files/".length)
        } else if (rel.startsWith("usr/")) {
            // 兼容 $PREFIX 相对形式的包
            rel = rel.substring(4)
        }
        // ndk-multilib 的 <triple>/lib 提到根层级，usr/lib/<triple> 链接恰好指向这里
        if (rel.startsWith("opt/ndk-multilib/")) {
            rel = rel.substring("opt/ndk-multilib/".length)
        }
        return rel
    }

    private fun parseOctal(h: ByteArray, off: Int, len: Int): Long {
        var v = 0L
        for (i in off until off + len) {
            val b = h[i].toInt() and 0xFF
            if (b == 0 || b == ' '.code) { if (v > 0) break else continue }
            if (b !in '0'.code..'7'.code) break
            v = (v shl 3) or (b - '0'.code).toLong()
        }
        return v
    }

    private val SKIP_PREFIXES = listOf("share/doc/", "share/man/", "share/licenses/")
}

/** 供 extractDeb 用的极简随机访问读取器（ar 成员遍历） */
private class ArReader(file: File) : Closeable {
    private val raf = RandomAccessFile(file, "r")

    fun atEnd(): Boolean = raf.filePointer >= raf.length()

    fun readString(n: Int): String = String(readBytes(n))

    fun readBytes(n: Int): ByteArray {
        val buf = ByteArray(n)
        raf.readFully(buf)
        return buf
    }

    fun skip(n: Long) = raf.seek(raf.filePointer + n)

    fun copyCurrent(size: Long, dest: File) {
        FileOutputStream(dest).use { out ->
            val buf = ByteArray(1 shl 16)
            var left = size
            while (left > 0) {
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) throw IOException("ar 数据流提前结束")
                out.write(buf, 0, n)
                left -= n
            }
        }
    }

    override fun close() = raf.close()
}
