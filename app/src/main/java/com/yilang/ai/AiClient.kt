package com.yilang.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * AI 助手客户端：OpenAI 兼容 /chat/completions（DeepSeek / 智谱 / Kimi / 通义等均兼容）。
 * 零第三方依赖，HttpURLConnection 直连；配置存 SharedPreferences。
 */
object AiClient {

    private const val PREFS = "ai_settings"
    private const val KEY_BASE = "base_url"
    private const val KEY_KEY = "api_key"
    private const val KEY_MODEL = "model"
    private const val TIMEOUT_MS = 90_000

    /** API 连接配置 */
    data class Config(val baseUrl: String, val apiKey: String, val model: String) {
        val ready: Boolean get() = baseUrl.startsWith("http") && apiKey.isNotBlank() && model.isNotBlank()
    }

    /** 一条对话消息：role = system / user / assistant */
    data class Msg(val role: String, val content: String)

    /** 从代码块提取的源码：语言标注 + 代码正文 */
    data class CodeBlock(val lang: String, val code: String)

    fun loadConfig(ctx: Context): Config {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Config(
            baseUrl = p.getString(KEY_BASE, "https://api.deepseek.com") ?: "https://api.deepseek.com",
            apiKey = p.getString(KEY_KEY, "") ?: "",
            model = p.getString(KEY_MODEL, "deepseek-chat") ?: "deepseek-chat",
        )
    }

    fun saveConfig(ctx: Context, c: Config) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_BASE, c.baseUrl.trim().trimEnd('/'))
            .putString(KEY_KEY, c.apiKey.trim())
            .putString(KEY_MODEL, c.model.trim())
            .apply()
    }

    /**
     * 发起多轮对话，返回助手回复全文。
     * @param history 不含 system；系统提示词在此统一拼入（system = null 时不带系统提示，用于标题/压缩等内部调用）
     */
    suspend fun chat(ctx: Context, history: List<Msg>, system: String? = SYSTEM_PROMPT): Result<String> = withContext(Dispatchers.IO) {
        val cfg = loadConfig(ctx)
        if (!cfg.ready) return@withContext Result.failure(IllegalStateException("请先在设置中填写 API 地址与密钥"))
        runCatching {
            val url = URL("${cfg.baseUrl.trimEnd('/')}/chat/completions")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer ${cfg.apiKey}")
            }
            val body = JSONObject().apply {
                put("model", cfg.model)
                put("stream", false)
                put("messages", JSONArray().apply {
                    if (system != null) put(JSONObject().put("role", "system").put("content", system))
                    for (m in history) put(JSONObject().put("role", m.role).put("content", m.content))
                })
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText() ?: ""
            conn.disconnect()
            if (code !in 200..299) {
                // OpenAI 兼容错误体：{"error":{"message":"…"}}；退化为原文
                val msg = runCatching {
                    JSONObject(text).getJSONObject("error").optString("message")
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: "HTTP $code：$text"
                throw IllegalStateException(msg)
            }
            val content = JSONObject(text)
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
            if (content.isBlank()) throw IllegalStateException("AI 返回了空回复，请重试")
            content
        }
    }

    /** 把回复文本拆成 普通段落 / 代码块 交替列表（代码块 = 三反引号围栏） */
    fun splitSegments(text: String): List<Any> {
        val re = Regex("""```([\w+-]*)\r?\n([\s\S]*?)```""")
        val out = mutableListOf<Any>()
        var last = 0
        for (m in re.findAll(text)) {
            if (m.range.first > last) out.add(text.substring(last, m.range.first))
            out.add(CodeBlock(m.groupValues[1], m.groupValues[2].trimEnd('\n')))
            last = m.range.last + 1
        }
        if (last < text.length) out.add(text.substring(last))
        return out
    }

    /** 历史对话：本地持久化模型（一个会话一个 json 文件） */
    data class Conversation(
        val id: String,
        val title: String,
        val updatedAt: Long,
        val summary: String = "",
        val msgs: List<Msg> = emptyList(),
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("title", title)
            .put("updatedAt", updatedAt)
            .put("summary", summary)
            .put("messages", JSONArray().apply {
                for (m in msgs) put(JSONObject().put("role", m.role).put("content", m.content))
            })

        companion object {
            fun fromJson(o: JSONObject): Conversation {
                val arr = o.optJSONArray("messages") ?: JSONArray()
                val msgs = buildList {
                    for (i in 0 until arr.length()) {
                        val m = arr.getJSONObject(i)
                        add(Msg(m.optString("role"), m.optString("content")))
                    }
                }.filter { it.role == "user" || it.role == "assistant" }
                return Conversation(
                    id = o.getString("id"),
                    title = o.optString("title").ifBlank { "新对话" },
                    updatedAt = o.optLong("updatedAt"),
                    summary = o.optString("summary"),
                    msgs = msgs,
                )
            }
        }
    }

    /** 历史对话存储：filesDir/ai_chats/<id>.json，按更新时间倒序读取 */
    object ChatStore {
        private fun dir(ctx: Context): File = File(ctx.filesDir, "ai_chats").apply { mkdirs() }

        fun save(ctx: Context, c: Conversation) {
            runCatching { File(dir(ctx), "${c.id}.json").writeText(c.toJson().toString()) }
        }

        fun loadAll(ctx: Context): List<Conversation> =
            dir(ctx).listFiles()
                ?.mapNotNull { f -> runCatching { Conversation.fromJson(JSONObject(f.readText())) }.getOrNull() }
                ?.sortedByDescending { it.updatedAt }
                ?: emptyList()

        fun delete(ctx: Context, id: String) {
            runCatching { File(dir(ctx), "$id.json").delete() }
        }
    }

    /**
     * 用对话内容生成短标题：中文、无标点、不超过 10 个字符。
     * 不带蚁语言系统提示词。
     */
    suspend fun generateTitle(ctx: Context, transcript: String): Result<String> {
        val prompt = """
你会得到一段 <content> 标签中的用户与助手对话内容。
你需要把这段对话总结成一个简短标题。
1. 标题语言与用户主要使用的语言一致（此处为中文）
2. 不要使用标点符号或其他特殊符号
3. 直接回复标题本身
4. 标题不超过 10 个字符

<content>
$transcript
</content>
        """.trimIndent()
        return chat(ctx, listOf(Msg("user", prompt)), system = null).mapCatching { sanitizeTitle(it) }
    }

    /** 清理标题：去标点空白换行，截取前 10 字 */
    private fun sanitizeTitle(raw: String): String =
        raw.replace(Regex("[\\s\\p{Punct}，。！？；：、·…“”‘’（）《》【】「」]+"), "").take(10)
            .ifBlank { "新对话" }

    /** 上下文压缩：把较长的早期对话压成摘要（不带蚁语言系统提示词） */
    suspend fun compress(ctx: Context, transcript: String): Result<String> {
        val prompt = """
你是对话压缩助手。请把 <conversation> 中的对话压缩成一份简明摘要。
要求：
1. 保留继续对话所需的关键事实、决定与上下文（特别是用户的需求、已确认的做法与代码要点）
2. 用中文
3. 控制在 300 字以内
4. 直接输出摘要，不要任何解释
5. 以「【此前对话摘要】」开头

<conversation>
$transcript
</conversation>
        """.trimIndent()
        return chat(ctx, listOf(Msg("user", prompt)), system = null)
    }

    /**
     * 系统提示词：蚁语言语法速查，让 AI 能直接产出可运行代码。
     * 代码注释均为中文，回复要求代码块用 ```yl 围栏。
     */
    private val SYSTEM_PROMPT = """
你是「蚁语言」编程助手。蚁语言是一门中文语法、转译为 C++ 的编程语言。请用中文回答，尽量给出可直接运行的完整蚁语言代码，代码放在 ```yl 围栏代码块中；纯 C++ 代码放 ```cpp 块。

蚁语言语法速查（严格按此语法作答）：
1. 声明：设 类型 名 = 值。类型：整数、小数、浮点、字符、布尔、长整、短整、字符串、自动（推断）。常量用「常量」代替「设」。无后缀数组写 类型[] 名 = { … }，二维写 类型[][]。
2. 布尔字面量：真 / 假。字符字面量用单引号：'A'。逻辑：并且 / 或者 / 非。比较 == != > < >= <=。
3. 输出("文字", 值)；输入 整数 x（读一行存入已声明变量）。
4. 条件：如果 (…) { … } 否则如果 (…) { … } 否则 { … }。三元：条件 ? 值1 : 值2。
5. 循环：当 (…) { … }；对于 (设 整数 i = 0; i < n; i++) { … }；遍历 对于 (x 于 集合) { … }（x 依次取数组/字符串/字典的元素）；做 { … } 当 (…)；跳出 / 继续。
6. 函数：函数 类型 名(类型 a, 类型 b) { 返回 … }；无返回值用「空」。可互相递归（自动前向声明）。
7. 字符串：+ 拼接；s.长度；截取(s, 起) / 截取(s, 起, 长)；查找(s, 子串)（找不到 -1）；包含(s, 子串)；替换(s, 旧, 新)；转文本(x)（数值转文本用于拼接）。
8. 数组：a[i] 从 0 开始；a.长度；遍历 对于 (x 于 a)。字典：设 字典<键类型, 值类型> m；m[键] 读写；包含键(m, 键)；删除键(m, 键)；遍历时 e.键 / e.值。
9. 数学函数：平方根 次方 绝对值 向下取整 向上取整 四舍五入 最大 最小 正弦 余弦 正切 转整数 转浮点 随机数 随机种子(n)。
10. 结构体：结构体 名 { 设 整数 x = 0 … }，成员用「设」；用 p.x 取成员。枚举：枚举 名 { 红, 绿 }。
11. 位运算：a 位与 b、a 位或 b、a 位异或 b、位非 a、a 左移 n、a 右移 n。
12. 每行一条语句；// 注释；变量名可用中文；顶层直接写语句即主程序；「引用 "工具.yl"」引入同目录文件（被引用文件只能定义函数/结构体/枚举，不能有顶层语句）。

要求：优先蚁语言；用户明确要 C++ 才写 C++。解释简洁，先给代码后给要点。用户报错时指出原因并给出修正后的完整代码。
    """.trimIndent()
}
