package com.yilang.ui.ai

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yilang.ai.AiClient
import com.yilang.ui.common.SaveToProjectDialog
import com.yilang.ui.common.consumeTaps
import com.yilang.ui.projects.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 一条聊天消息：role = user / assistant / error（error 仅本地展示，不回传 API）/ note（压缩提示，不回传） */
private data class ChatUiMsg(val role: String, val content: String)

/**
 * 会话状态：进程级单例持有，底部 tab 切换不丢对话；
 * 自建 CoroutineScope，切走后请求继续、回来仍在。
 * 支持多会话：自动保存到本地、新建对话、历史对话、AI 生成标题、长对话自动压缩。
 */
private object ChatState {
    val msgs = mutableStateListOf<ChatUiMsg>()
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var convId by mutableStateOf<String?>(null) // 当前会话 id（null = 尚未保存的新对话）
    var title by mutableStateOf("新对话")
    var summary by mutableStateOf("") // 上下文压缩摘要：随请求携带，不以气泡展示

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 实际参与对话的消息（不含 note / error） */
    private val realMsgs: List<ChatUiMsg> get() = msgs.filter { it.role == "user" || it.role == "assistant" }

    /** 保存当前会话（有实质内容才存） */
    fun save(ctx: Context) {
        val real = realMsgs
        if (real.isEmpty()) return
        val id = convId ?: java.util.UUID.randomUUID().toString().also { convId = it }
        AiClient.ChatStore.save(
            ctx,
            AiClient.Conversation(
                id = id,
                title = title,
                updatedAt = System.currentTimeMillis(),
                summary = summary,
                msgs = real.map { AiClient.Msg(it.role, it.content) },
            ),
        )
    }

    /** 新建对话：先保存当前，再清空状态 */
    fun startNew(ctx: Context) {
        if (busy) return
        save(ctx)
        msgs.clear()
        error = null
        convId = null
        title = "新对话"
        summary = ""
    }

    /** 打开历史会话（先保存当前） */
    fun open(ctx: Context, c: AiClient.Conversation) {
        if (busy) return
        save(ctx)
        msgs.clear()
        if (c.summary.isNotBlank()) msgs.add(ChatUiMsg("note", "早期对话已压缩为摘要，AI 仍可结合上下文回答"))
        c.msgs.forEach { msgs.add(ChatUiMsg(it.role, it.content)) }
        convId = c.id
        title = c.title
        summary = c.summary
        error = null
    }

    /** 删除会话；若删除的是当前打开的会话则回到新对话状态 */
    fun deleteExternal(ctx: Context, id: String) {
        AiClient.ChatStore.delete(ctx, id)
        if (convId == id) {
            convId = null
            title = "新对话"
            summary = ""
            msgs.clear()
        }
    }

    fun send(ctx: Context, text: String) {
        val t = text.trim()
        if (t.isEmpty() || busy) return
        msgs.add(ChatUiMsg("user", t))
        busy = true
        error = null
        val firstRound = realMsgs.count { it.role == "user" } == 1
        scope.launch {
            try {
                maybeCompress(ctx)
                AiClient.chat(ctx, buildHistory())
                    .onSuccess {
                        msgs.add(ChatUiMsg("assistant", it))
                        save(ctx)
                        if (firstRound && title == "新对话") generateTitle(ctx)
                    }
                    .onFailure { error = it.message ?: "请求失败" }
            } finally {
                busy = false
            }
        }
    }

    /** 组装请求历史：压缩摘要置顶 + 实际消息 */
    private fun buildHistory(): List<AiClient.Msg> {
        val list = mutableListOf<AiClient.Msg>()
        if (summary.isNotBlank()) {
            list.add(AiClient.Msg("user", "以下是之前对话的摘要，请结合它继续回答：\n$summary"))
        }
        realMsgs.forEach { list.add(AiClient.Msg(it.role, it.content)) }
        return list
    }

    /** 实际消息过多时压缩：早期消息并入摘要，仅保留最近 4 条 */
    private suspend fun maybeCompress(ctx: Context) {
        val real = realMsgs
        if (real.size < 16) return
        val keep = 4
        val old = real.subList(0, real.size - keep)
        val transcript = buildString {
            if (summary.isNotBlank()) {
                append(summary)
                append("\n\n")
            }
            for (m in old) append(if (m.role == "user") "用户：" else "助手：").append(m.content).append('\n')
        }
        val s = AiClient.compress(ctx, transcript).getOrNull() ?: return
        summary = s
        val head = if (msgs.firstOrNull()?.role == "note") 1 else 0
        val kept = msgs.drop(head).takeLast(keep)
        msgs.clear()
        msgs.add(ChatUiMsg("note", "早期对话已压缩为摘要，AI 仍可结合上下文回答"))
        kept.forEach { msgs.add(it) }
        save(ctx)
    }

    /** 首轮回复后生成会话标题（后台进行，失败静默保持「新对话」） */
    private fun generateTitle(ctx: Context) {
        scope.launch {
            val transcript = realMsgs.take(4).joinToString("\n") { m ->
                (if (m.role == "user") "用户：" else "助手：") + m.content.take(400)
            }
            val t = AiClient.generateTitle(ctx, transcript).getOrNull() ?: return@launch
            title = t
            save(ctx)
        }
    }
}

/** AI 助手页：对话式提问 → 生成蚁语言代码，代码块可复制 / 存入项目；
 *  onClose 非空时作为编辑器内覆盖层打开：顶栏带返回键，且拦截点击防穿透。
 *  initialPrompt 非空时进入后自动作为用户消息发出（编辑器「报错问 AI」/「选中问 AI」播种）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiChatScreen(onClose: (() -> Unit)? = null, initialPrompt: String? = null) {
    val ctx = LocalContext.current
    var cfg by remember { mutableStateOf(AiClient.loadConfig(ctx)) }
    var showSettings by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var saveBlock by remember { mutableStateOf<AiClient.CodeBlock?>(null) }
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // 外部播种：进入组合（或 prompt 变化）时自动发出
    LaunchedEffect(initialPrompt) {
        if (initialPrompt != null) ChatState.send(ctx, initialPrompt)
    }

    // 新消息 / 回复到达 → 滚到底部
    LaunchedEffect(ChatState.msgs.size, ChatState.busy, ChatState.error) {
        val n = ChatState.msgs.size
        if (n > 0) listState.animateScrollToItem((n - 1).coerceAtLeast(0))
    }

    Scaffold(
        modifier = Modifier.consumeTaps(),
        topBar = {
            TopAppBar(
                title = { Text(ChatState.title, maxLines = 1) },
                navigationIcon = {
                    if (onClose != null) {
                        IconButton(onClick = onClose) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = {
                        ChatState.startNew(ctx)
                        Toast.makeText(ctx, "已新建对话", Toast.LENGTH_SHORT).show()
                    }) { Icon(Icons.Filled.Add, "新建对话") }
                    IconButton(onClick = { showHistory = true }) { Icon(Icons.AutoMirrored.Filled.List, "历史对话") }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, "API 设置")
                    }
                },
            )
        },
    ) { padding ->
        if (!cfg.ready) {
            // 未配置 API：引导页
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    "还没有配置 AI 服务\n\n支持 DeepSeek、智谱、Kimi、通义等\nOpenAI 兼容接口，填入地址与密钥即可",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                TextButton(onClick = { showSettings = true }) { Text("去配置") }
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding(),
            ) {
                if (ChatState.msgs.isEmpty() && !ChatState.busy) {
                    Text(
                        "描述你的需求，AI 直接写出蚁语言代码\n\n例如：「写一个猜数字游戏」「用字典统计单词出现次数」",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(32.dp),
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    ) {
                        items(ChatState.msgs) { m -> ChatBubble(m, onSave = { saveBlock = it }) }
                        if (ChatState.busy) {
                            item {
                                Text(
                                    "AI 思考中…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                                )
                            }
                        }
                        ChatState.error?.let { err ->
                            item { ChatBubble(ChatUiMsg("error", "请求失败：$err"), onSave = {}) }
                        }
                    }
                }
                InputBar(
                    value = input,
                    busy = ChatState.busy,
                    onChange = { input = it },
                    onSend = {
                        ChatState.send(ctx, input)
                        input = ""
                    },
                )
            }
        }
    }

    if (showSettings) {
        AiSettingsDialog(
            current = cfg,
            onDismiss = { showSettings = false },
            onSave = {
                cfg = it
                AiClient.saveConfig(ctx, it)
                showSettings = false
                Toast.makeText(ctx, "已保存 AI 设置", Toast.LENGTH_SHORT).show()
            },
        )
    }

    if (showHistory) {
        HistoryDialog(
            ctx = ctx,
            onOpen = { showHistory = false },
            onDismiss = { showHistory = false },
        )
    }

    saveBlock?.let { block ->
        SaveToProjectDialog(
            ctx = ctx,
            code = block.code,
            defaultExt = if (block.lang == "cpp") ".cpp" else ".yl",
            onDismiss = { saveBlock = null },
        )
    }
}

/** 历史对话对话框：本地保存的会话列表，点击载入，可删除 */
@Composable
private fun HistoryDialog(ctx: Context, onOpen: () -> Unit, onDismiss: () -> Unit) {
    // 异步加载：会话多时读盘不该卡主线程
    var list by remember { mutableStateOf<List<AiClient.Conversation>>(emptyList()) }
    LaunchedEffect(Unit) {
        list = withContext(Dispatchers.IO) { AiClient.ChatStore.loadAll(ctx) }
    }
    val timeFmt = remember { java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("历史对话") },
        text = {
            if (list.isEmpty()) {
                Text(
                    "还没有保存的对话\n\n发起对话后会自动保存到这里",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                )
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(list) { c ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    ChatState.open(ctx, c)
                                    onOpen()
                                }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(c.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                Text(
                                    "${timeFmt.format(java.util.Date(c.updatedAt))} · ${c.msgs.size} 条消息",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = {
                                ChatState.deleteExternal(ctx, c.id)
                                list = AiClient.ChatStore.loadAll(ctx)
                            }) { Icon(Icons.Filled.Delete, "删除", tint = MaterialTheme.colorScheme.outline) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/** 消息气泡：用户右侧主色；AI 左侧，内容按 段落/代码块 分段渲染；note 为居中灰色提示 */
@Composable
private fun ChatBubble(m: ChatUiMsg, onSave: (AiClient.CodeBlock) -> Unit) {
    if (m.role == "note") {
        Text(
            "· ${m.content} ·",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
        )
        return
    }
    val user = m.role == "user"
    val isErr = m.role == "error"
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = if (user) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = when {
                user -> MaterialTheme.colorScheme.primaryContainer
                isErr -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                for (seg in AiClient.splitSegments(m.content)) {
                    when (seg) {
                        is String -> Text(
                            seg.trim('\n'),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                        is AiClient.CodeBlock -> CodeCard(seg, onSave)
                    }
                }
            }
        }
    }
}

/** 代码块卡片：语言标签 + 等宽代码（横向滚动）+ 复制 / 存到项目 */
@Composable
private fun CodeCard(block: AiClient.CodeBlock, onSave: (AiClient.CodeBlock) -> Unit) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val langLabel = when (block.lang) {
        "yl", "yilang", "" -> "蚁语言"
        else -> block.lang.ifEmpty { "代码" }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                langLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Text(
                "复制",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable {
                        clip.setText(AnnotatedString(block.code))
                        Toast.makeText(ctx, "已复制代码", Toast.LENGTH_SHORT).show()
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
            Text(
                "存到项目",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable { onSave(block) }
                    .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            )
        }
        Text(
            block.code,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
        )
    }
}

/** 底部输入栏：多行输入 + 发送按钮 */
@Composable
private fun InputBar(value: String, busy: Boolean, onChange: (String) -> Unit, onSend: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onChange,
                placeholder = { Text("描述你的需求…") },
                modifier = Modifier.weight(1f),
                maxLines = 4,
                textStyle = MaterialTheme.typography.bodyMedium,
            )
            IconButton(onClick = onSend, enabled = !busy && value.isNotBlank()) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    "发送",
                    tint = if (busy || value.isBlank()) MaterialTheme.colorScheme.outline
                    else MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** API 设置对话框：地址 / 密钥 / 模型名（OpenAI 兼容格式） */
@Composable
private fun AiSettingsDialog(
    current: AiClient.Config,
    onDismiss: () -> Unit,
    onSave: (AiClient.Config) -> Unit,
) {
    var baseUrl by remember { mutableStateOf(current.baseUrl) }
    var apiKey by remember { mutableStateOf(current.apiKey) }
    var model by remember { mutableStateOf(current.model) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AI 服务设置") },
        text = {
            Column {
                Text(
                    "任何 OpenAI 兼容接口均可，如：\napi.deepseek.com、open.bigmodel.cn/api/paas/v4、api.moonshot.cn",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("接口地址（不含 /chat/completions）") },
                    singleLine = true,
                    modifier = Modifier.padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    modifier = Modifier.padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("模型名（如 deepseek-chat）") },
                    singleLine = true,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = baseUrl.startsWith("http") && apiKey.isNotBlank() && model.isNotBlank(),
                onClick = { onSave(AiClient.Config(baseUrl, apiKey, model)) },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
