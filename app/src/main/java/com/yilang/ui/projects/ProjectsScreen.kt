package com.yilang.ui.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yilang.compiler.ToolchainCatalog
import com.yilang.compiler.ToolchainInstaller
import com.yilang.ui.main.ToolchainSetupDialog
import com.yilang.ui.main.ToolchainSetupState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 项目页（首屏）：品牌琥珀配色
 * - 顶栏：蚁字标 + 标题 + 搜索 + 更多
 * - 卡片：类型字块 + 项目名 + 类型/构建徽章，长按弹出锚定菜单
 * - FAB 新建；搜索胶囊过滤；空状态引导
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(
    onOpenProject: (Project) -> Unit,
    reloadKey: Int = 0,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var projects by remember { mutableStateOf<List<Project>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var showNewDialog by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<String?>(null) } // 长按弹菜单的项目路径
    var renameTarget by remember { mutableStateOf<Project?>(null) }
    var deleteTarget by remember { mutableStateOf<Project?>(null) }
    var setupState by remember { mutableStateOf<ToolchainSetupState?>(null) }
    val scope = rememberCoroutineScope()

    fun reload() {
        scope.launch {
            projects = withContext(Dispatchers.IO) { Project.loadAll(ctx) }
        }
    }
    LaunchedEffect(reloadKey) { reload() }

    // 首启检测：工具链未安装则弹出下载询问（点确认后弹窗内显示进度条）
    LaunchedEffect(Unit) {
        val installed = withContext(Dispatchers.IO) { ToolchainInstaller.isInstalled(ctx) }
        if (!installed) setupState = ToolchainSetupState.Ask
    }

    val filtered = if (query.isBlank()) projects
        else projects.filter {
            it.name.contains(query, ignoreCase = true) || it.type.label.contains(query, ignoreCase = true)
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BrandTile("蚁", 28.dp, 14.sp)
                        Spacer(Modifier.width(10.dp))
                        Text("项目")
                    }
                },
                actions = {
                    IconButton(onClick = { searching = !searching; query = "" }) {
                        Icon(Icons.Filled.Search, "搜索")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, "更多")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("新建项目") },
                                onClick = { menuOpen = false; showNewDialog = true },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showNewDialog = true },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("新建项目") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (searching) {
                SearchPill(
                    query = query,
                    onChange = { query = it },
                    onClose = { searching = false; query = "" },
                )
            }
            if (filtered.isEmpty()) {
                EmptyProjects(hasAny = projects.isNotEmpty(), onNew = { showNewDialog = true })
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(filtered, key = { it.dir.absolutePath }) { p ->
                        ProjectCard(
                            project = p,
                            menuOpen = menuFor == p.dir.absolutePath,
                            onToggleMenu = { menuFor = p.dir.absolutePath },
                            onCloseMenu = { menuFor = null },
                            onRename = { renameTarget = p },
                            onDelete = { deleteTarget = p },
                            onClick = { onOpenProject(p) },
                        )
                    }
                }
            }
        }
    }

    if (showNewDialog) {
        NewProjectDialog(
            onDismiss = { showNewDialog = false },
            onConfirm = { name, type, build ->
                showNewDialog = false
                scope.launch {
                    withContext(Dispatchers.IO) { Project.create(ctx, name, type, build) }
                    reload()
                }
            },
        )
    }

    // 工具链首启弹窗：询问 → 下载进度 → 完成/失败
    setupState?.let { state ->
        ToolchainSetupDialog(
            state = state,
            onDismiss = { setupState = null },
            onStart = {
                val total = ToolchainCatalog.packages.sumOf { it.sizeBytes }
                setupState = ToolchainSetupState.Downloading("准备下载…", 0, total)
                scope.launch {
                    try {
                        ToolchainInstaller.ensureInstalled(ctx) { p ->
                            // 回调在 IO 线程，切回主线程更新弹窗
                            scope.launch {
                                setupState = ToolchainSetupState.Downloading(p.message, p.downloadedBytes, p.totalBytes)
                            }
                        }
                        scope.launch { setupState = ToolchainSetupState.Done }
                    } catch (e: Exception) {
                        scope.launch {
                            setupState = ToolchainSetupState.Failed(e.message ?: "未知错误")
                        }
                    }
                }
            },
        )
    }

    renameTarget?.let { target ->
        RenameDialog(
            initial = target.name,
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                Project.rename(target, newName)
                renameTarget = null
                reload()
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除项目") },
            text = { Text("确定删除「${target.name}」？其全部源码将被移除。") },
            confirmButton = {
                TextButton(onClick = { Project.delete(target); deleteTarget = null; reload() }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
}

/** 品牌字块：圆角色块 + 单字（蚁 / C+），用于 logo 与项目图标 */
@Composable
private fun BrandTile(
    label: String,
    boxSize: androidx.compose.ui.unit.Dp,
    fontSize: TextUnit,
    container: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primaryContainer,
    content: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    Box(
        Modifier
            .size(boxSize)
            .clip(RoundedCornerShape(boxSize / 4))
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = fontSize, fontWeight = FontWeight.Bold, color = content)
    }
}

/** 搜索胶囊：圆角底 + 放大镜 + 输入行内提示 */
@Composable
private fun SearchPill(query: String, onChange: (String) -> Unit, onClose: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Search,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        "搜索项目",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
                Icon(
                    Icons.Filled.Close,
                    "关闭搜索",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** 空状态：品牌圆标 + 引导文案 + 新建按钮 */
@Composable
private fun EmptyProjects(hasAny: Boolean, onNew: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(bottom = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(92.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "蚁",
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            if (hasAny) "没有匹配的项目" else "还没有项目",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "用中文写代码，让 C++ 跑起来",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(22.dp))
        FilledTonalButton(onClick = onNew) {
            Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("新建项目")
        }
    }
}

/** 项目卡片：类型字块 + 名称 + 类型/构建徽章；长按弹出锚定菜单 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ProjectCard(
    project: Project,
    menuOpen: Boolean,
    onToggleMenu: () -> Unit,
    onCloseMenu: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onClick: () -> Unit,
) {
    val isYi = project.type == ProjectType.YILANG
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onToggleMenu),
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 2.dp, top = 13.dp, bottom = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 类型字块：蚁语言 = 蜜色「蚁」，C++ = 苔绿「C+」
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isYi) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.tertiaryContainer,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (isYi) "蚁" else "C+",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isYi) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    project.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f),
                    ) {
                        Text(
                            project.type.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        project.buildSystem.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Box {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = onCloseMenu) {
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        leadingIcon = { Icon(Icons.Filled.Edit, null) },
                        onClick = { onCloseMenu(); onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text("删除") },
                        leadingIcon = { Icon(Icons.Filled.Delete, null) },
                        onClick = { onCloseMenu(); onDelete() },
                    )
                }
            }
        }
    }
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名项目") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("新名称") },
            )
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name.trim()) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
