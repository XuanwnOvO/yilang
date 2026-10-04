package com.yilang.ui.editor

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yilang.ui.common.consumeTaps
import kotlinx.coroutines.delay
import java.util.Locale

private val TerminalBg = Color(0xFF0D1117)
private val TerminalFg = Color(0xFFC9D1D9)
private val TerminalDim = Color(0xFF8B949E)
private val TerminalOk = Color(0xFF3FB950)
private val TerminalWarn = Color(0xFFD29922)
private val TerminalErr = Color(0xFFF85149)
private val TerminalLine = Color(0xFF21262D)

/** 窗口红黄绿圆点（经典终端观感） */
private val DotColors = listOf(Color(0xFFFF5F57), Color(0xFFFEBC2E), Color(0xFF28C840))

/** braille 运行 spinner 帧序列 */
private val SpinnerFrames = listOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")

/** 终端正文统一字号/行高 */
private val MonoBody = 13.sp

/**
 * 全屏终端界面：点击 ▶ 运行后弹出
 * - 终端窗口观感：红黄绿圆点标题栏、状态条（运行中 spinner / 退出码 / 耗时）
 * - 输出逐行着色：$ 命令行绿提示符、error 红黄、正文白；末尾提示符光标闪烁
 * - 运行中显示输入行：> 提示符 + 文本框，回车/发送写入子进程 stdin
 * - 编译错误行可点击（下划线提示）：关闭终端并跳到编辑器对应文件与行
 * - ↻ 重新运行（自动清屏）；× 关闭返回编辑器；C++ 代码不在这里，从 ⋮ 菜单的「查看生成的 C++」进入
 * - 日志用 LazyColumn 懒渲染 + 行级缓存，长输出不卡顿；停在底部时自动跟随滚动
 */
@Composable
fun TerminalOverlay(
    logLines: List<String>,
    isError: Boolean,
    running: Boolean,
    fileName: String,
    durationMs: Long,
    showInput: Boolean = false,
    onClose: () -> Unit,
    onRerun: () -> Unit,
    onSendInput: (String) -> Unit = {},
    onJump: (fileBase: String?, line: Int) -> Unit = { _, _ -> },
    /** 非空且出错时，状态条下方显示「问 AI」按钮 */
    onAskAi: (() -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    // 跟随滚动：停在底部时新输出自动滚到底；用户上翻查看历史时不打扰
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            follow = info.totalItemsCount == 0 || lastVisible >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(logLines.size, showInput) {
        if (follow && logLines.isNotEmpty()) {
            listState.scrollToItem(logLines.size + 1) // 0=命令+状态条，1..n=日志行，n+1=输入/光标
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(TerminalBg)
            .statusBarsPadding()
            .consumeTaps(),
    ) {
        // ── 标题栏：红黄绿圆点 + 终端名 + 重跑/关闭 ──
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DotColors.forEach { c ->
                    Box(
                        Modifier
                            .size(11.dp)
                            .clip(CircleShape)
                            .background(c),
                    )
                }
            }
            Text(
                "终端 — $fileName",
                color = TerminalDim,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
                maxLines = 1,
            )
            Text(
                "↻",
                color = TerminalDim,
                fontSize = 18.sp,
                modifier = Modifier
                    .clickable(enabled = !running) { onRerun() }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, "关闭终端", tint = TerminalDim)
            }
        }
        HorizontalDivider(color = TerminalLine, thickness = 1.dp)

        // ── 正文：$ yi build → 状态条 → 日志（懒加载）→ 输入行 → 提示符光标 ──
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            item(key = "head") {
                Column {
                    PromptLine("yi build")
                    Spacer(Modifier.height(6.dp))
                    StatusLine(isError, running, durationMs)
                    if (isError && onAskAi != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "✦ 问 AI 这个错误",
                            color = TerminalOk,
                            fontSize = MonoBody,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(TerminalLine)
                                .clickable(onClick = onAskAi)
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
            }
            itemsIndexed(logLines) { _, line -> LogLine(line, onJump) }
            item(key = "tail") {
                Column {
                    if (showInput) {
                        Spacer(Modifier.height(6.dp))
                        InputLine(onSendInput)
                        Spacer(Modifier.height(4.dp))
                    }
                    BlinkingPrompt()
                }
            }
        }
    }
}

/** `$ 命令`：提示符绿色加粗、命令白色 */
@Composable
private fun PromptLine(cmd: String) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(TerminalOk, fontWeight = FontWeight.Bold)) { append("$ ") }
            withStyle(SpanStyle(TerminalFg)) { append(cmd) }
        },
        fontFamily = FontFamily.Monospace,
        fontSize = MonoBody,
    )
}

/**
 * 状态条：运行中 spinner 转圈；结束显示 ✓/✗ + 耗时。
 */
@Composable
private fun StatusLine(isError: Boolean, running: Boolean, durationMs: Long) {
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(running) {
        while (running) {
            frame = (frame + 1) % SpinnerFrames.size
            delay(120)
        }
    }
    val status = when {
        running -> AnnotatedString("${SpinnerFrames[frame]} 编译运行中…")
        isError -> buildAnnotatedString {
            withStyle(SpanStyle(TerminalErr, fontWeight = FontWeight.Bold)) { append("✗ ") }
            append("进程已退出")
            if (durationMs > 0) append(" · " + fmtDuration(durationMs))
        }
        else -> buildAnnotatedString {
            withStyle(SpanStyle(TerminalOk, fontWeight = FontWeight.Bold)) { append("✓ ") }
            append("进程已完成，退出码 0")
            if (durationMs > 0) append(" · " + fmtDuration(durationMs))
        }
    }
    Text(
        status,
        color = TerminalDim,
        fontFamily = FontFamily.Monospace,
        fontSize = MonoBody,
    )
}

/** 单条日志行：解析/着色按行内容缓存，列表追加时旧行不重算 */
@Composable
private fun LogLine(line: String, onJump: (fileBase: String?, line: Int) -> Unit) {
    val jump = remember(line) { parseErrJump(line) }
    val annotated = remember(line) { annotateLine(line, jump != null) }
    Text(
        annotated,
        color = TerminalFg,
        fontFamily = FontFamily.Monospace,
        fontSize = MonoBody,
        lineHeight = 19.sp,
        modifier = if (jump != null) Modifier
            .fillMaxWidth()
            .clickable { onJump(jump.first, jump.second) }
        else Modifier,
    )
}

// clang 错误行：main.cpp:5:3: error: … （带可选列号 / fatal 前缀）
private val CLANG_ERR_RE =
    Regex("^(.+?):(\\d+):(?:\\d+)?:?\\s*(?:fatal )?error:", RegexOption.IGNORE_CASE)

// 转译错误行：第 3 行：应为「;」…
private val YL_LINE_RE = Regex("第 (\\d+) 行")

/** 从日志行解析可跳转错误：返回 (文件基名或 null=当前文件, 源码行号)；非错误行返回 null */
private fun parseErrJump(line: String): Pair<String?, Int>? {
    val clang = CLANG_ERR_RE.find(line)
    if (clang != null) {
        val base = clang.groupValues[1]
            .substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
        return base to clang.groupValues[2].toInt()
    }
    if (line.contains("error", ignoreCase = true) || line.contains("错误") || line.contains("失败")) {
        val m = YL_LINE_RE.find(line) ?: return null
        return null to m.groupValues[1].toInt()
    }
    return null
}

/** 单行着色规则（underline = 可点击跳转提示） */
private fun annotateLine(line: String, underline: Boolean = false): AnnotatedString {
    val deco = if (underline) TextDecoration.Underline else null
    return buildAnnotatedString {
        val lower = line.lowercase()
        when {
            // 命令回显：$ 提示符绿色，命令本体白色
            line.startsWith("$ ") -> {
                withStyle(SpanStyle(TerminalOk, fontWeight = FontWeight.Bold)) { append("$ ") }
                withStyle(SpanStyle(TerminalFg)) { append(line.substring(2)) }
            }
            lower.contains("error:") || line.contains("错误") || line.contains("失败") ->
                withStyle(SpanStyle(TerminalErr, textDecoration = deco)) { append(line) }
            lower.contains("warning:") ->
                withStyle(SpanStyle(TerminalWarn, textDecoration = deco)) { append(line) }
            else ->
                withStyle(SpanStyle(TerminalFg, textDecoration = deco)) { append(line) }
        }
    }
}

/** 运行期标准输入行：> 提示符 + 深色文本框，IME Send / ⏎ 按钮发送 */
@Composable
private fun InputLine(onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val submit = {
        if (text.isNotBlank()) {
            onSend(text.trim())
            text = ""
        }
    }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(TerminalOk, fontWeight = FontWeight.Bold)) { append("> ") }
            },
            fontFamily = FontFamily.Monospace,
            fontSize = MonoBody,
        )
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            textStyle = TextStyle(
                color = TerminalFg,
                fontFamily = FontFamily.Monospace,
                fontSize = MonoBody,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
            cursorBrush = SolidColor(TerminalOk),
            singleLine = true,
        )
        Text(
            "⏎",
            color = if (text.isBlank()) TerminalDim else TerminalOk,
            fontSize = 16.sp,
            modifier = Modifier
                .clickable(enabled = text.isNotBlank()) { submit() }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/** 待机提示符 + 闪烁块状光标 */
@Composable
private fun BlinkingPrompt() {
    val blink = rememberInfiniteTransition(label = "cursor")
    val on by blink.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(530), RepeatMode.Reverse),
        label = "cursorAlpha",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(TerminalOk, fontWeight = FontWeight.Bold)) { append("$ ") }
            },
            fontFamily = FontFamily.Monospace,
            fontSize = MonoBody,
        )
        Box(
            Modifier
                .width(8.dp)
                .height(16.dp)
                .background(TerminalFg.copy(alpha = on)),
        )
    }
}

/** 毫秒 → 「1.2s」/「58s」 */
private fun fmtDuration(ms: Long): String {
    val sec = ms / 1000.0
    return if (sec < 60) String.format(Locale.US, "%.1fs", sec) else "${ms / 1000}s"
}

/**
 * 生成的 C++ 只读查看页：从编辑器 ⋮ 菜单的「查看生成的 C++」进入。
 * 黑底等宽，顶栏返回 + 文件名 + 行数，仅浏览不可编辑；#line 指令行不展示。
 */
@Composable
fun CppViewerOverlay(
    cppCode: String,
    fileName: String,
    onClose: () -> Unit,
) {
    // #line 只是报错跳转的实现细节，展示时隐藏
    val display = cppCode.lines()
        .filterNot { it.trimStart().startsWith("#line ") }
        .joinToString("\n")
    val scroll = rememberScrollState()
    Column(
        Modifier
            .fillMaxSize()
            .background(TerminalBg)
            .statusBarsPadding()
            .consumeTaps(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = TerminalDim)
            }
            Text(
                "生成的 C++ — $fileName",
                color = TerminalDim,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 2.dp),
                maxLines = 1,
            )
            Text(
                "${display.lines().size} 行",
                color = TerminalDim,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(end = 14.dp),
            )
        }
        HorizontalDivider(color = TerminalLine, thickness = 1.dp)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(12.dp),
        ) {
            Text(
                display,
                color = TerminalFg,
                fontFamily = FontFamily.Monospace,
                fontSize = MonoBody,
                lineHeight = 19.sp,
            )
        }
    }
}
