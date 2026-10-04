package com.yilang.ui.editor

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.yilang.R
import com.yilang.compiler.CompilerService
import com.yilang.model.CompileResult
import com.yilang.model.TranspileResult
import com.yilang.transpiler.YiTranspiler
import com.yilang.ui.ai.AiChatScreen
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.ContentListener
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.LinkedBlockingQueue

/**
 * 编辑器页（全屏）：
 * - 顶栏：返回 / ☰ 文件树 / 文件名 / ↶ ↷ / ▶ 运行 / ⋮ 菜单（新建·格式化·搜索·日志·保存）
 * - 左滑或点 ☰ 打开文件树抽屉；编辑器下方是符号快捷栏
 * - 点 ▶ 运行后覆盖显示终端界面（状态条/逐行着色/闪烁光标，↻ 可重跑），× 返回编辑
 * - 切换文件前自动保存当前文件，不丢改动
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    projectDir: String,
    onBack: () -> Unit,
    onOpenManual: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val dir = remember(projectDir) { File(projectDir) }

    // 标签持久化：重启后恢复上次打开的文件与当前 tab（按项目目录区分）
    val prefs = remember(projectDir) { ctx.getSharedPreferences("editor_tabs", Context.MODE_PRIVATE) }
    // 已打开的文件（优先恢复上次的标签；文件树点击后追加去重）
    var openFiles by remember(projectDir) {
        mutableStateOf(
            prefs.getString("tabs:$projectDir", null)?.split("\n")
                ?.map { File(it) }?.filter { it.isFile }?.takeIf { it.isNotEmpty() }
                ?: loadSourceFiles(dir),
        )
    }
    var currentTab by remember { mutableIntStateOf(prefs.getInt("tab:$projectDir", 0)) }
    var cppOut by remember { mutableStateOf("") }
    val logLines = remember { mutableStateListOf<String>() } // 终端日志：逐行追加，避免整串重建
    val treeExpanded = remember { mutableStateMapOf<String, Boolean>() } // 文件树展开状态（抽屉重开不丢）
    var showTerminal by remember { mutableStateOf(false) }
    var isError by remember { mutableStateOf(false) }
    var building by remember { mutableStateOf(false) }
    var runMs by remember { mutableStateOf(0L) } // 本次运行耗时（终端状态条显示）
    var dirty by remember { mutableStateOf(false) }
    var showCppViewer by remember { mutableStateOf(false) } // ⋮ 菜单打开的 C++ 查看页
    var treeTick by remember { mutableStateOf(0) } // 文件树刷新计数（新建文件后 +1）
    var showNewFile by remember { mutableStateOf(false) }
    var showNewFolder by remember { mutableStateOf(false) } // 新建文件夹对话框
    var folderName by remember { mutableStateOf("") }
    val stdinQueue = remember { LinkedBlockingQueue<String>() } // 运行期用户输入 → 子进程 stdin
    var pendingJump by remember { mutableStateOf<Pair<Int, Int>?>(null) } // (tab, 行0基) 切 tab 后跳转
    var renameTarget by remember { mutableStateOf<File?>(null) } // 待重命名的文件
    var renameText by remember { mutableStateOf("") } // 重命名输入框内容
    var deleteTarget by remember { mutableStateOf<File?>(null) } // 待删除的文件
    var showFind by remember { mutableStateOf(false) } // 查找替换栏
    var showHelp by remember { mutableStateOf(false) } // 语法帮助页
    var showAi by remember { mutableStateOf(false) } // AI 助手覆盖层
    var aiSeed by remember { mutableStateOf<String?>(null) } // 传给 AI 覆盖层的自动提问（报错/选中代码）

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // sora-editor 实例（跨重组持有；语言/配色/字体等配置只做一次）
    val editor = remember {
        CodeEditor(ctx).apply {
            setEditorLanguage(YiLanguage())
            setColorScheme(SchemeDarcula())
            setTypefaceText(android.graphics.Typeface.MONOSPACE)
            setTextSize(15f)
            setLineNumberEnabled(true)
        }
    }
    DisposableEffect(Unit) {
        // 内容变化 → 标题打 * 提示未保存
        editor.text.addContentListener(object : ContentListener {
            override fun beforeReplace(content: Content) {}
            override fun afterInsert(
                content: Content, startLine: Int, startColumn: Int,
                endLine: Int, endColumn: Int, insertedContent: CharSequence,
            ) {
                dirty = true
            }

            override fun afterDelete(
                content: Content, startLine: Int, startColumn: Int,
                endLine: Int, endColumn: Int, deletedContent: CharSequence,
            ) {
                dirty = true
            }
        })
        onDispose { editor.release() }
    }

    val safeTab = if (openFiles.isEmpty()) -1 else currentTab.coerceIn(0, openFiles.lastIndex)
    val current = openFiles.getOrNull(safeTab)

    // 标签变化写回持久化：下次进项目恢复同样的标签与位置
    LaunchedEffect(openFiles, safeTab) {
        prefs.edit()
            .putString("tabs:$projectDir", openFiles.joinToString("\n") { it.absolutePath })
            .putInt("tab:$projectDir", safeTab.coerceAtLeast(0))
            .apply()
    }

    // 切换文件：IO 线程读盘 → 编辑器（setText 触发的 dirty 标记随后被覆盖）
    LaunchedEffect(current) {
        if (current != null) {
            val text = withContext(Dispatchers.IO) { current.readText() }
            editor.setText(text)
            dirty = false
            // 报错跳转：切 tab 后 setText 会重置光标，这里补跳
            pendingJump?.let { (tab, ln) ->
                if (tab == currentTab) editor.setSelection(ln, 0)
                pendingJump = null
            }
        }
    }

    fun toast(msg: String) {
        scope.launch { snackbar.showSnackbar(msg) }
    }

    // 括号配对提醒：不平衡时 snackbar 警告但不阻断（保存/运行照常）
    fun warnIfBrackets() {
        checkBrackets(editor.text.toString())?.let { toast("注意：$it") }
    }

    fun appendLog(line: String) {
        // SnapshotStateList 线程安全：编译进程回调线程直接追加即可
        logLines.add(line)
    }

    // 切走前把当前编辑器内容写回文件，防丢改动
    fun flushCurrent() {
        if (current != null) current.writeText(editor.text.toString())
    }

    fun switchTo(idx: Int) {
        val target = idx.coerceIn(0, openFiles.lastIndex)
        if (target == safeTab) return
        flushCurrent()
        currentTab = target
    }

    // 关闭标签（× 按钮）：当前标签先保存，再修正 currentTab
    fun closeTabAt(idx: Int) {
        if (openFiles.getOrNull(idx) == null) return
        if (idx == safeTab) flushCurrent()
        openFiles = openFiles.filterIndexed { i, _ -> i != idx }
        val next = if (idx < currentTab) currentTab - 1 else currentTab
        currentTab = if (openFiles.isEmpty()) 0 else next.coerceIn(0, openFiles.lastIndex)
    }

    fun openInEditor(f: File) {
        if (!f.isFile) return
        flushCurrent()
        val existing = openFiles.indexOfFirst { it.absolutePath == f.absolutePath }
        if (existing >= 0) {
            currentTab = existing
        } else {
            openFiles = openFiles + f
            currentTab = openFiles.lastIndex
        }
        scope.launch { drawerState.close() }
    }

    fun createFile(raw: String, content: String = "") {
        var name = raw.trim()
        if (name.isEmpty()) return
        if (!name.contains('.')) name += ".yl"
        val f = File(dir, name)
        if (f.exists()) {
            toast("文件已存在：${f.name}")
            return
        }
        runCatching {
            f.createNewFile()
            if (content.isNotEmpty()) f.writeText(content)
        }
            .onSuccess {
                treeTick++
                openInEditor(f)
            }
            .onFailure { toast("创建失败：${it.message}") }
    }

    // 重命名文件/文件夹：同步更新已打开标签；改名的是当前文件时触发重载
    fun renameFile(f: File, newName: String) {
        val name = newName.trim()
        if (name.isEmpty() || name == f.name) return
        val target = File(f.parentFile, name)
        if (target.exists()) {
            toast("同名文件已存在：$name")
            return
        }
        flushCurrent() // 先把未保存内容写回旧路径，再改名
        runCatching { f.renameTo(target) }
            .onSuccess { ok ->
                if (!ok) {
                    toast("重命名失败")
                    return
                }
                treeTick++
                val idx = openFiles.indexOfFirst { it.absolutePath == f.absolutePath }
                if (idx >= 0) {
                    openFiles = openFiles.mapIndexed { i, old -> if (i == idx) target else old }
                    if (idx == safeTab) currentTab = idx // current 引用变化 → 重新读盘
                }
                // 文件夹改名：修正其内部已打开标签的路径前缀
                if (f.isDirectory) {
                    val oldPrefix = f.absolutePath + File.separator
                    val newPrefix = target.absolutePath + File.separator
                    openFiles = openFiles.map { old ->
                        if (old.absolutePath.startsWith(oldPrefix))
                            File(newPrefix + old.absolutePath.substring(oldPrefix.length))
                        else old
                    }
                }
                toast("已重命名为 ${target.name}")
            }
            .onFailure { toast("重命名失败：${it.message}") }
    }

    // 删除文件/文件夹：递归删除；若在已打开标签中则同步关闭，并修正当前 tab
    fun deleteFile(f: File) {
        runCatching {
            if (f.isDirectory) f.deleteRecursively() else f.delete()
        }
            .onSuccess { ok ->
                if (!ok) {
                    toast("删除失败")
                    return
                }
                treeTick++
                // 关掉被删文件本身、以及被删文件夹内的所有已打开标签
                val prefix = f.absolutePath + File.separator
                val closed = openFiles.withIndex()
                    .filter { it.value == f || it.value.absolutePath.startsWith(prefix) }
                    .map { it.index }
                if (closed.isNotEmpty()) {
                    openFiles = openFiles.filterIndexed { i, _ -> i !in closed }
                    currentTab = currentTab.coerceIn(0, openFiles.lastIndex.coerceAtLeast(0))
                }
                toast(if (f.isDirectory) "已删除文件夹 ${f.name}" else "已删除 ${f.name}")
            }
            .onFailure { toast("删除失败：${it.message}") }
    }

    // 新建文件夹（名字可带子路径：a/b 一次建两层）
    fun createFolder(raw: String) {
        val name = raw.trim().trimEnd('/')
        if (name.isEmpty()) return
        val f = File(dir, name)
        if (f.exists()) {
            toast("同名文件/文件夹已存在：$name")
            return
        }
        runCatching { f.mkdirs() }
            .onSuccess { ok ->
                if (!ok) {
                    toast("创建失败")
                    return
                }
                treeTick++
                toast("已创建 $name")
            }
            .onFailure { toast("创建失败：${it.message}") }
    }

    // 「报错问 AI」：把错误日志 + 出错文件源码打包成提问，自动发给 AI
    fun askAiAboutError() {
        val code = editor.text.toString().take(3000)
        val prompt = buildString {
            append("我的蚁语言程序运行报错了，请分析原因并给出修改建议（蚁语言是中文语法，转译为 C++）。\n\n")
            append("出错文件：").append(current?.name ?: "未知").append('\n')
            append("错误日志：\n```\n")
            logLines.forEach { append(it).append('\n') }
            append("```\n\n源码（最多 3000 字符）：\n```\n").append(code).append("\n```")
        }
        aiSeed = prompt
        showTerminal = false
        showAi = true
    }

    // 「选中代码问 AI」：把编辑器选区打包成提问
    fun askAiAboutSelection() {
        val c = editor.cursor
        val content = editor.text
        val sel = if (c.isSelected) {
            val start = content.getCharIndex(c.leftLine, c.leftColumn)
            val end = content.getCharIndex(c.rightLine, c.rightColumn)
            if (end > start) content.substring(start, end) else null
        } else null
        if (sel.isNullOrBlank()) {
            toast("请先选中一段代码")
            return
        }
        aiSeed = "请解释这段蚁语言代码，如有问题请指出并给出修改：\n```\n$sel\n```"
        showAi = true
    }

    // 点击终端里的错误行：关终端 → 跳到对应文件对应行（fileBase=null = 当前文件）
    fun jumpToError(fileBase: String?, line: Int) {
        showTerminal = false
        showCppViewer = false
        val ln = (line - 1).coerceAtLeast(0)
        val target = if (fileBase == null) safeTab
        else openFiles.indexOfFirst {
            it.nameWithoutExtension == fileBase || it.name == fileBase
        }
        if (target < 0) {
            toast("未找到文件：$fileBase")
            return
        }
        if (target == safeTab) {
            editor.setSelection(ln, 0)
        } else {
            flushCurrent()
            pendingJump = target to ln
            currentTab = target
        }
    }

    fun runTranspile() {
        if (building || current == null) return
        if (current.extension != "yl" && current.extension != "cpp") {
            toast("只能运行 .yl / .cpp 文件")
            return
        }
        warnIfBrackets()
        flushCurrent() // 运行前自动保存，防丢改动
        stdinQueue.clear()
        logLines.clear()
        cppOut = ""
        isError = false
        showTerminal = true
        building = true
        runMs = 0
        val entry = current
        val entryText = editor.text.toString() // 主线程取编辑器内容
        val t0 = System.currentTimeMillis()
        scope.launch {
            // 第一步：读引用文件 + 转译放后台线程，主线程不卡
            when (val prep = withContext(Dispatchers.Default) { prepareSources(entry, entryText) }) {
                is RunPrep.Fail -> {
                    isError = true
                    appendLog(prep.message)
                    building = false
                }
                is RunPrep.Ok -> {
                    cppOut = prep.cppDisplay
                    // 第二步：编译并运行，输出实时进终端
                    val result = CompilerService.buildAndRun(ctx, prep.sources, { appendLog(it) }, stdinQueue, prep.includeDir)
                    runMs = System.currentTimeMillis() - t0
                    when (result) {
                        // 成功时终端只留程序输出；无输出才补一行完成提示
                        is CompileResult.Success ->
                            if (logLines.isEmpty()) appendLog("（运行完成，程序无输出）")
                        is CompileResult.Failure -> {
                            isError = true
                            appendLog(result.log)
                        }
                    }
                    building = false
                }
            }
        }
    }

    fun saveCurrent() {
        if (current == null) return
        warnIfBrackets()
        current.writeText(editor.text.toString())
        dirty = false
        toast("已保存 ${current.name}")
    }

    fun formatCurrent() {
        if (current == null) return
        val formatted = formatCode(editor.text.toString())
        editor.setText(formatted)
        current.writeText(formatted)
        dirty = false
        toast("已格式化并保存")
    }

    // 查找替换（sora Searcher 要求后台线程执行）
    fun doSearch(query: String, ignoreCase: Boolean) {
        if (query.isEmpty()) {
            editor.searcher.stopSearch()
            return
        }
        scope.launch(Dispatchers.IO) {
            runCatching {
                editor.searcher.search(
                    query,
                    EditorSearcher.SearchOptions(EditorSearcher.SearchOptions.TYPE_NORMAL, ignoreCase),
                )
            }
        }
    }

    fun gotoMatch(next: Boolean) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                if (next) editor.searcher.gotoNext() else editor.searcher.gotoPrevious()
            }
        }
    }

    fun closeFind() {
        editor.searcher.stopSearch()
        showFind = false
    }

    // 符号栏插入：光标可回退（成对符号落在中间）
    fun insertSnippet(text: String, caretBack: Int) {
        val cur = editor.cursor
        val line = cur.leftLine
        val col = cur.leftColumn
        editor.text.insert(line, col, text)
        editor.setSelection(line, col + text.length - caretBack)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // 关闭状态禁用边缘手势：编辑器里从左往右拖（横滚代码/移动光标）不该误拉出文件树；
        // 打开后保留手势，可从抽屉边缘拖回关闭。开抽屉请点左上角 ☰。
        gesturesEnabled = drawerState.targetValue == DrawerValue.Open,
        drawerContent = {
            ModalDrawerSheet {
                FileTreeDrawerContent(
                    root = dir,
                    tick = treeTick,
                    expanded = treeExpanded,
                    onOpenFile = ::openInEditor,
                    onRename = { f ->
                        renameTarget = f
                        renameText = f.name
                    },
                    onDelete = { deleteTarget = it },
                    onCreateFolder = {
                        folderName = ""
                        showNewFolder = true
                    },
                )
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    EditorTopBar(
                        title = (current?.name ?: "（空项目）") + if (dirty) " *" else "",
                        showCppItem = cppOut.isNotBlank(),
                        onBack = { flushCurrent(); onBack() }, // 返回前自动保存
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onUndo = { editor.undo() },
                        onRedo = { editor.redo() },
                        onRun = { runTranspile() },
                        onViewCpp = { showCppViewer = true },
                        onNewFile = { showNewFile = true },
                        onFormat = { formatCurrent() },
                        onSearch = { showFind = true },
                        onShowHelp = { showHelp = true },
                        onShowAi = { showAi = true },
                        onAskAiSelection = { askAiAboutSelection() },
                        onShowLog = { showTerminal = true },
                        onSave = { saveCurrent() },
                    )
                },
                snackbarHost = { SnackbarHost(snackbar) },
            ) { padding ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    if (openFiles.size > 1) {
                        ScrollableTabRow(
                            selectedTabIndex = safeTab.coerceAtLeast(0),
                            edgePadding = 8.dp,
                        ) {
                            openFiles.forEachIndexed { idx, f ->
                                Tab(
                                    selected = safeTab == idx,
                                    onClick = { switchTo(idx) },
                                ) {
                                    Row(
                                        Modifier.padding(start = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            f.name,
                                            maxLines = 1,
                                            modifier = Modifier.widthIn(max = 120.dp),
                                        )
                                        Text(
                                            "×",
                                            style = MaterialTheme.typography.titleMedium,
                                            modifier = Modifier
                                                .clickable { closeTabAt(idx) }
                                                .padding(horizontal = 10.dp, vertical = 12.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (showFind && current != null) {
                        FindReplaceBar(
                            onSearch = ::doSearch,
                            onGoto = ::gotoMatch,
                            onReplaceThis = { rep ->
                                scope.launch(Dispatchers.IO) {
                                    runCatching { editor.searcher.replaceThis(rep) }
                                }
                            },
                            onReplaceAll = { rep ->
                                scope.launch(Dispatchers.IO) {
                                    runCatching { editor.searcher.replaceAll(rep) }
                                }
                            },
                            onClose = { closeFind() },
                        )
                    }
                    if (current == null) {
                        Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "项目中没有源码文件\n点 ☰ 浏览文件，或点 ⋮ 新建文件",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        AndroidView(
                            factory = { editor },
                            update = { v ->
                                v.setBackgroundResource(android.R.color.transparent)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        )
                        SymbolBar(onInsert = ::insertSnippet)
                    }
                }
            }

            // 运行后：全屏终端覆盖层（上滑进场，下滑退出）
            AnimatedVisibility(
                visible = showTerminal,
                enter = slideInVertically(tween(260)) { it } + fadeIn(tween(160)),
                exit = slideOutVertically(tween(220)) { it } + fadeOut(tween(140)),
            ) {
                TerminalOverlay(
                    logLines = logLines,
                    isError = isError,
                    running = building,
                    fileName = current?.name ?: "",
                    durationMs = runMs,
                    showInput = building,
                    onClose = { showTerminal = false },
                    onRerun = { runTranspile() },
                    onSendInput = { line ->
                        stdinQueue.put(line)
                        appendLog("> $line")
                    },
                    onJump = { fileBase, line -> jumpToError(fileBase, line) },
                    onAskAi = { askAiAboutError() },
                )
            }

            // ⋮ 菜单打开的 C++ 查看页（叠在终端之上，返回后回到终端）
            AnimatedVisibility(
                visible = showCppViewer && cppOut.isNotBlank(),
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(150)),
            ) {
                CppViewerOverlay(
                    cppCode = cppOut,
                    fileName = current?.name ?: "",
                    onClose = { showCppViewer = false },
                )
            }

            // AI 助手覆盖层（上滑进场；对话状态进程级持有，关掉再开不丢）
            AnimatedVisibility(
                visible = showAi,
                enter = slideInVertically(tween(260)) { it } + fadeIn(tween(160)),
                exit = slideOutVertically(tween(220)) { it } + fadeOut(tween(140)),
            ) {
                AiChatScreen(
                    onClose = {
                        showAi = false
                        aiSeed = null // 关闭时清掉播种，下次手动打开不会重发旧提问
                    },
                    initialPrompt = aiSeed,
                )
            }
        }
    }

    // 新建文件对话框
    if (showNewFile) {
        NewFileDialog(
            onDismiss = { showNewFile = false },
            onCreate = { name, content ->
                showNewFile = false
                createFile(name, content)
            },
        )
    }

    // 语法帮助覆盖层
    AnimatedVisibility(
        visible = showHelp,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(150)),
    ) {
        SyntaxHelpOverlay(
            onClose = { showHelp = false },
            onOpenManual = onOpenManual,
        )
    }

    // 重命名对话框（文件 / 文件夹共用）
    renameTarget?.let { f ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text(if (f.isDirectory) "新文件夹名" else "新文件名") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(enabled = renameText.isNotBlank(), onClick = {
                    val t = renameTarget
                    renameTarget = null
                    if (t != null) renameFile(t, renameText)
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("取消") }
            },
        )
    }

    // 删除确认对话框（文件 / 文件夹文案区分）
    deleteTarget?.let { f ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(if (f.isDirectory) "删除文件夹" else "删除文件") },
            text = {
                Text(
                    if (f.isDirectory) "确定删除文件夹「${f.name}」及其全部内容吗？此操作不可撤销。"
                    else "确定删除「${f.name}」吗？此操作不可撤销。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val t = deleteTarget
                    deleteTarget = null
                    if (t != null) deleteFile(t)
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }

    // 新建文件夹对话框
    if (showNewFolder) {
        AlertDialog(
            onDismissRequest = { showNewFolder = false },
            title = { Text("新建文件夹") },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text("文件夹名（可用 / 建子目录）") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(enabled = folderName.isNotBlank(), onClick = {
                    val n = folderName
                    showNewFolder = false
                    createFolder(n)
                }) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolder = false }) { Text("取消") }
            },
        )
    }
}

/**
 * 括号配对检查：() [] {} 需成对闭合；字符串内的括号不计数。
 * 返回第一个问题的中文描述（含行号）；完全平衡返回 null。
 */
private fun checkBrackets(code: String): String? {
    val stack = ArrayDeque<Pair<Char, Int>>() // (开括号, 行号)
    val closerOf = mapOf(')' to '(', ']' to '[', '}' to '{')
    var line = 1
    var i = 0
    while (i < code.length) {
        val c = code[i]
        when {
            c == '\n' -> line++
            c == '"' -> { // 字符串：整体跳过（支持 \" 转义），顺带检查未闭合
                i++
                while (i < code.length && code[i] != '"') {
                    if (code[i] == '\n') return "第 $line 行：字符串没有结束的引号"
                    if (code[i] == '\\' && i + 1 < code.length) i++
                    i++
                }
                if (i >= code.length) return "第 $line 行：字符串没有结束的引号"
            }
            c == '/' && i + 1 < code.length && code[i + 1] == '/' -> { // 行注释：跳到行尾
                i++
                while (i < code.length && code[i] != '\n') i++
                if (i < code.length) { line++; i++ }
                continue
            }
            c == '(' || c == '[' || c == '{' -> stack.addLast(c to line)
            c in closerOf -> {
                val expect = closerOf[c]
                val top = stack.lastOrNull()
                if (top == null || top.first != expect)
                    return "第 $line 行：「$c」没有匹配的「$expect」"
                stack.removeLast()
            }
        }
        i++
    }
    val top = stack.lastOrNull()
    if (top != null) return "第 ${top.second} 行：「${top.first}」没有闭合"
    return null
}

private fun loadSourceFiles(dir: File): List<File> =
    dir.listFiles { f -> f.isFile && f.extension.lowercase() in OPENABLE_EXT }
        ?.sortedBy { it.name.lowercase() }
        ?: emptyList()

/**
 * 括号深度重缩进：4 空格；行首 } 先退一级再输出；
 * 字符串、字符字面量与 // 行注释内的括号不参与计数。
 */
private fun formatCode(code: String): String {
    val out = StringBuilder()
    var depth = 0
    for (raw in code.lines()) {
        val line = raw.trim()
        if (line.isEmpty()) {
            out.append('\n')
            continue
        }
        val printDepth = if (line.startsWith("}")) (depth - 1).coerceAtLeast(0) else depth
        repeat(printDepth) { out.append("    ") }
        out.append(line).append('\n')
        var opens = 0
        var closes = 0
        var inStr = false
        var inChar = false
        var escape = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                escape -> escape = false
                (inStr || inChar) && c == '\\' -> escape = true
                inStr -> if (c == '"') inStr = false
                inChar -> if (c == '\'') inChar = false
                c == '"' -> inStr = true
                c == '\'' -> inChar = true
                c == '/' && i + 1 < line.length && line[i + 1] == '/' -> i = line.length
                c == '{' -> opens++
                c == '}' -> closes++
            }
            i++
        }
        depth = (depth + opens - closes).coerceAtLeast(0)
    }
    return out.toString().trimEnd('\n') + "\n"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorTopBar(
    title: String,
    showCppItem: Boolean,
    onBack: () -> Unit,
    onOpenDrawer: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onRun: () -> Unit,
    onViewCpp: () -> Unit,
    onNewFile: () -> Unit,
    onFormat: () -> Unit,
    onSearch: () -> Unit,
    onShowHelp: () -> Unit,
    onShowAi: () -> Unit,
    onAskAiSelection: () -> Unit,
    onShowLog: () -> Unit,
    onSave: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text(title, maxLines = 1) },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        },
        actions = {
            IconButton(onClick = onOpenDrawer) { Icon(Icons.Filled.Menu, "文件树") }
            IconButton(onClick = onUndo) {
                Icon(
                    painterResource(R.drawable.ic_undo),
                    "撤销",
                )
            }
            IconButton(onClick = onRedo) {
                Icon(
                    painterResource(R.drawable.ic_redo),
                    "重做",
                )
            }
            IconButton(onClick = onRun) {
                Icon(Icons.Filled.PlayArrow, "运行", tint = MaterialTheme.colorScheme.primary)
            }
            // Box 包住按钮与菜单：锚定 ⋮ 正下方，避免菜单飘到 actions 行中央
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "更多") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("AI 助手") }, onClick = { menuOpen = false; onShowAi() })
                    DropdownMenuItem(text = { Text("选中代码问 AI") }, onClick = { menuOpen = false; onAskAiSelection() })
                    DropdownMenuItem(text = { Text("新建文件") }, onClick = { menuOpen = false; onNewFile() })
                    DropdownMenuItem(text = { Text("格式化") }, onClick = { menuOpen = false; onFormat() })
                    DropdownMenuItem(text = { Text("搜索") }, onClick = { menuOpen = false; onSearch() })
                    DropdownMenuItem(text = { Text("语法帮助") }, onClick = { menuOpen = false; onShowHelp() })
                    // 本次运行产出过 C++ 才显示
                    if (showCppItem) {
                        DropdownMenuItem(
                            text = { Text("查看生成的 C++") },
                            onClick = { menuOpen = false; onViewCpp() },
                        )
                    }
                    DropdownMenuItem(text = { Text("运行日志") }, onClick = { menuOpen = false; onShowLog() })
                    DropdownMenuItem(text = { Text("保存") }, onClick = { menuOpen = false; onSave() })
                }
            }
        },
    )
}

/** 查找替换栏：查找输入 + 大小写开关 + 上下导航，可展开替换行 */
@Composable
private fun FindReplaceBar(
    onSearch: (String, Boolean) -> Unit,
    onGoto: (Boolean) -> Unit,
    onReplaceThis: (String) -> Unit,
    onReplaceAll: (String) -> Unit,
    onClose: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var replace by remember { mutableStateOf("") }
    var ignoreCase by remember { mutableStateOf(true) }
    var showReplace by remember { mutableStateOf(false) }

    val fieldStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp)
    val fieldColor = MaterialTheme.colorScheme.onSurface
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant

    @Composable
    fun Field(value: String, hint: String, modifier: Modifier, onChange: (String) -> Unit) {
        Box(modifier) {
            if (value.isEmpty()) {
                Text(hint, style = fieldStyle, color = hintColor, maxLines = 1)
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                textStyle = fieldStyle.copy(color = fieldColor),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Field(
                value = query,
                hint = "查找",
                modifier = Modifier.weight(1f),
                onChange = { q ->
                    query = q
                    onSearch(q, ignoreCase)
                },
            )
            Text(
                "Aa",
                style = MaterialTheme.typography.titleSmall,
                color = if (ignoreCase) hintColor else MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable {
                        ignoreCase = !ignoreCase
                        onSearch(query, ignoreCase)
                    }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            )
            Text(
                "↑",
                style = MaterialTheme.typography.titleMedium,
                color = fieldColor,
                modifier = Modifier
                    .clickable { onGoto(false) }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            )
            Text(
                "↓",
                style = MaterialTheme.typography.titleMedium,
                color = fieldColor,
                modifier = Modifier
                    .clickable { onGoto(true) }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            )
            Text(
                if (showReplace) "⇅" else "⇄",
                style = MaterialTheme.typography.titleMedium,
                color = if (showReplace) MaterialTheme.colorScheme.primary else hintColor,
                modifier = Modifier
                    .clickable { showReplace = !showReplace }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            )
            Text(
                "×",
                style = MaterialTheme.typography.titleMedium,
                color = fieldColor,
                modifier = Modifier
                    .clickable {
                        query = ""
                        onClose()
                    }
                    .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            )
        }
        if (showReplace) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 2.dp),
            ) {
                Field(
                    value = replace,
                    hint = "替换为",
                    modifier = Modifier.weight(1f),
                    onChange = { replace = it },
                )
                Text(
                    "替换",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { onReplaceThis(replace) }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                )
                Text(
                    "全部",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { onReplaceAll(replace) }
                        .padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                )
            }
        }
    }
}

/** 编辑器下方一排符号快捷键 */
@Composable
private fun SymbolBar(onInsert: (String, Int) -> Unit) {
    // (键面, 插入文本, 光标回退位数)
    val keys = listOf(
        Triple("()", "()", 1),
        Triple("{}", "{}", 1),
        Triple("[]", "[]", 1),
        Triple("\"\"", "\"\"", 1),
        Triple("''", "''", 1),
        Triple("=", "=", 0),
        Triple(":", ":", 0),
        Triple(";", ";", 0),
        Triple(",", ",", 0),
        Triple("⇥", "    ", 0),
    )
    Row(
        Modifier
            .fillMaxWidth()
            .height(42.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        keys.forEach { (label, text, back) ->
            // 键帽样式：浅色圆角小块浮在深色条上
            Text(
                label,
                modifier = Modifier
                    .padding(start = 5.dp, top = 7.dp, bottom = 7.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable { onInsert(text, back) }
                    .padding(horizontal = 13.dp, vertical = 7.dp),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.width(7.dp))
    }
}

/** 「引用 "xxx"」行：整行剥离后不再进入转译器（Lexer 不认识该关键字）。不吞换行，配合 stripRefLines 保行号 */
private val REF_LINE = Regex("""(?m)^[ \t]*引用\s+"([^"]+)"[ \t]*(//[^\n]*)?""")

/** 剥「引用」行：行内容清空但保留换行——剥行前后行数严格不变，转译报错行号才不会整体偏移 */
private fun stripRefLines(src: String): String = REF_LINE.replace(src) { "\n" }

/** 手写 C++ 文件中的 main 函数（混合编译时与蚁语言主程序冲突） */
private val CPP_MAIN = Regex("""(?m)^\s*(?:int|void)\s+main\s*\(""")

/** 运行准备结果：Ok 携带 C++ 源码列表与查看页展示文本；Fail 携带中文错误信息 */
private sealed interface RunPrep {
    /** @param includeDir 项目目录（C++ 入口时传入）：让 #include "本地头文件" 可解析 */
    data class Ok(
        val sources: List<Pair<String, String>>,
        val cppDisplay: String,
        val includeDir: File? = null,
    ) : RunPrep
    data class Fail(val message: String) : RunPrep
}

/**
 * 运行第一步：蚁语言与 C++ 混合参编（共存）。
 * - 入口 .yl：引用链逐文件转译（入口生成 main）+ 项目其余手写 .cpp 一起编译链接；
 *   C++ 函数可在蚁语言中直接按名调用（转译为同名 C++ 调用）。
 * - 入口 .cpp：入口 + 项目其余 .cpp + 项目全部 .yl 转译参编；
 *   此时 .yl 不能有主程序语句（main 由 C++ 入口提供），蚁语言函数可在 C++ 中按中文名调用。
 * 含文件 IO 与 CPU 转译，应在后台线程调用。
 */
private fun prepareSources(entry: File, entryText: String): RunPrep {
    val dir = entry.parentFile ?: return RunPrep.Fail("无法定位项目目录")
    val list = mutableListOf<Pair<String, String>>() // (文件名, C++ 源码)

    fun readCpp(f: File): String? = try {
        f.readText(Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    if (entry.extension == "cpp") {
        // 入口 C++ + 其余手写 .cpp（main 只允许出现在入口）
        list.add(entry.name to entryText)
        val siblings = dir.listFiles { f -> f.isFile && f.extension == "cpp" && f.name != entry.name }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()
        for (f in siblings) {
            val text = readCpp(f) ?: return RunPrep.Fail("读取「${f.name}」失败")
            if (CPP_MAIN.containsMatchIn(text)) {
                return RunPrep.Fail("「${f.name}」含 main()：与入口「${entry.name}」冲突，一个程序只能有一个 main")
            }
            list.add(f.name to text)
        }
        // 项目全部 .yl 转译参编：与手写 C++ 链接互通
        val yls = dir.listFiles { f -> f.isFile && f.extension == "yl" }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()
        for (f in yls) {
            val text = readCpp(f) ?: return RunPrep.Fail("读取「${f.name}」失败")
            when (val r = YiTranspiler.transpile(stripRefLines(text), sourcePath = f.absolutePath)) {
                is TranspileResult.Error ->
                    return RunPrep.Fail("「${f.name}」转译失败（第 ${r.line} 行）：${r.message}")
                is TranspileResult.Success -> {
                    if (r.hasMainBody)
                        return RunPrep.Fail("「${f.name}」含主程序语句：入口是 ${entry.name}（C++）时，蚁语言文件只能定义函数/结构体/枚举")
                    list.add(f.name to r.cppCode)
                }
            }
        }
    } else {
        // 入口蚁语言：引用链收集 + 转译（入口必须带主程序）
        val (sources, refErr) = collectYiSources(entry, entryText)
        if (refErr != null) return RunPrep.Fail(refErr)
        for ((idx, s) in sources.withIndex()) {
            val (name, path, src) = s
            when (val r = YiTranspiler.transpile(src, sourcePath = path)) {
                is TranspileResult.Error ->
                    return RunPrep.Fail("「$name」转译失败（第 ${r.line} 行）：${r.message}")
                is TranspileResult.Success -> {
                    if (idx == 0 && !r.hasMainBody)
                        return RunPrep.Fail("入口文件「$name」缺少主程序语句（顶层的 输出/赋值 等），无法生成 main")
                    if (idx > 0 && r.hasMainBody)
                        return RunPrep.Fail("「$name」包含顶层语句：被引用的文件只能定义函数/结构体/枚举")
                    list.add(name to r.cppCode)
                }
            }
        }
        // 项目其余手写 .cpp 参编：C++ 与蚁语言共存编译链接
        val siblings = dir.listFiles { f -> f.isFile && f.extension == "cpp" }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()
        for (f in siblings) {
            val text = readCpp(f) ?: return RunPrep.Fail("读取「${f.name}」失败")
            if (CPP_MAIN.containsMatchIn(text)) {
                return RunPrep.Fail("「${f.name}」含 main()：主程序已在蚁语言入口中，请删除 C++ 里的 main")
            }
            list.add(f.name to text)
        }
    }

    // C++ 查看页：单文件显示原文；多文件带文件名分隔条
    val display = if (list.size == 1) list[0].second
    else list.joinToString("\n") { (n, c) -> "// ===== $n =====\n$c" }
    return RunPrep.Ok(list, display, includeDir = dir)
}

/**
 * f8 多文件：从入口 .yl 递归收集「引用」依赖（DFS，canonicalPath 防环）。
 * 返回 (文件名, 剥离引用行后的源码) 列表，入口在最前；失败时返回空列表 + 中文错误信息。
 */
private fun collectYiSources(entry: File, entryText: String): Pair<List<Triple<String, String, String>>, String?> {
    // Triple: (文件名, 绝对路径, 剥引用行后的源码)；路径给 #line 用，错误提示显示文件名
    val visited = mutableSetOf<String>()
    val ordered = mutableListOf<Triple<String, String, String>>()

    fun visit(f: File, text: String): String? {
        val key = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)
        if (!visited.add(key)) return null // 已收集（含循环引用）：跳过
        ordered.add(Triple(f.name, f.absolutePath, stripRefLines(text)))
        for (m in REF_LINE.findAll(text)) {
            val raw = m.groupValues[1].trim()
            var dep = File(f.parentFile, raw)
            if (dep.extension != "yl") dep = File(f.parentFile, "$raw.yl")
            if (!dep.isFile) return "找不到引用的文件「$raw」（被 ${f.name} 引用）"
            val depText = runCatching { dep.readText() }
                .getOrElse { return "读取「${dep.name}」失败：${it.message}" }
            visit(dep, depText)?.let { return it }
        }
        return null
    }

    val err = visit(entry, entryText)
    return if (err == null) ordered to null else emptyList<Triple<String, String, String>>() to err
}

/** 新建文件模板：(名称, 文件内容)；空文件内容为空串 */
private val FILE_TEMPLATES: List<Pair<String, String>> = listOf(
    "空文件" to "",
    "你好世界" to """输出("你好，世界！")""",
    "循环求和" to """设 整数 总和 = 0
对于 (设 整数 i = 1; i <= 100; i++) {
    总和 += i
}
输出("1 加到 100 的和：", 总和)""",
    "猜数字" to """随机种子(7)
设 整数 答案 = 随机数() % 100 + 1
设 整数 猜的 = 0
输出("我想了一个 1~100 的数，猜猜看！")
当 (猜的 != 答案) {
    输入 整数 猜的
    如果 (猜的 > 答案) {
        输出("大了，再试试")
    } 否则如果 (猜的 < 答案) {
        输出("小了，再试试")
    }
}
输出("猜对了！就是", 答案)""",
    "数组求平均" to """整数[] 分数 = { 90, 85, 77, 96, 68 }
设 整数 总分 = 0
对于 (设 整数 i = 0; i < 分数.长度; i++) {
    总分 += 分数[i]
}
输出("平均分：", 总分 / 分数.长度)""",
    "函数" to """函数 整数 最大值(整数 a, 整数 b) {
    如果 (a > b) {
        返回 a
    }
    返回 b
}

输出("max(3, 7) = ", 最大值(3, 7))""",
    "结构体" to """结构体 点 {
    设 整数 x = 0
    设 整数 y = 0
}

设 点 p
p.x = 3
p.y = 4
输出("p = (", p.x, ", ", p.y, ")")""",
)

@Composable
private fun NewFileDialog(onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var templateIdx by remember { mutableIntStateOf(0) }
    val template = FILE_TEMPLATES[templateIdx]
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建文件") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("文件名（无后缀自动加 .yl）") },
                    singleLine = true,
                )
                Text(
                    "从模板开始：",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                ) {
                    FILE_TEMPLATES.forEachIndexed { i, (label, _) ->
                        FilterChip(
                            selected = templateIdx == i,
                            onClick = { templateIdx = i },
                            label = { Text(label) },
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                }
                if (template.second.isNotEmpty()) {
                    Text(
                        template.second,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .heightIn(max = 140.dp)
                            .verticalScroll(rememberScrollState())
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name, template.second) }) {
                Text("创建")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
