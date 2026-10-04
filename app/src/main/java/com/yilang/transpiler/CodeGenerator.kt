package com.yilang.transpiler

/**
 * 蚁语言代码生成器（v0.6）：遍历 AST 生成 C++ 源码。
 *
 * 规则：
 * - 顶层非函数语句统一包进 int main()
 * - 顶层结构体/枚举按序生成，成员表用于点路径（p.x）类型推断
 * - 函数生成前向声明（支持互相递归）
 * - 头部引入 cstdio/cstdlib/cstring/cctype/cmath/string/vector/map/iostream/algorithm/thread/chrono/ctime + using namespace std
 * - 语句前发 #line 指令：C++ 编译错误行号 = 蚁语言源码行号（报错跳转用）
 * - 整数[] a → std::vector<int>；整数[][] b → std::vector<std::vector<int>>；a[i]/b[i][j] 索引
 * - a.长度 → a.size()（数组/字符串/字典均支持）；a[i].x 索引后成员
 * - 字典<K,V> → std::map<K,V>；包含键/删除键；for-each 迭代得 pair（e.键 → e.first）
 * - 对于 (x 于 集合) → for (auto &&x : 集合)；字符串字面量自动包 std::string
 * - 字符串操作：截取/查找/包含/替换/转文本（std::string 方法 + __yl_replace 辅助函数）
 * - 容器操作：追加/插入/弹出/排序/倒序/清空（push_back/insert/erase/sort/reverse/clear）
 * - 类型转换：转整数/转浮点/转小数/转字符 按实参类型生成（stoi/stod/强转/atoi/atof）
 * - 转大写/转小写：字符走 toupper/tolower，整串走 __yl_case 辅助函数
 * - 时间：休眠(毫秒) → sleep_for；当前时间() → time(0)
 * - 函数引用参数：参数类型后加「引用」→ C++ 引用传递（int& a）
 * - 字符串拼接重写："共" + n → std::string("共") + std::to_string(n)（防 const char* 指针算术）
 * - 字符 'A' → C++ 字符字面量；三元 若..则..否则 → cond ? a : b；位运算 & | ^ ~ << >>
 * - 中文函数名映射 C++ 标准库（CALL_MAP），中文流名映射 cin/cout/endl（VAR_MAP）
 * - 设 自动 x = 表达式 → auto 推断，并按表达式类型记录变量表
 * - 输出(...) 按类型推断 printf 格式符；std::string 值追加 .c_str()
 * - 块内语句缩进 4 空格
 */
class CodeGenerator(
    /** 对应的 .yl 源文件绝对路径：写入 #line 让 clang 错误指向中文源码（null = 不带文件名） */
    private val sourcePath: String? = null,
) {

    // 变量 → cpp 元素类型（数组存元素类型；字典存完整 std::map<K,V>）
    private val varTypes = HashMap<String, String>()
    private val structMembers = HashMap<String, List<Stmt.ValDecl>>() // 结构体名 → 成员表
    private val enumNames = HashSet<String>() // 枚举名集合
    private val arrayDims = HashMap<String, Int>() // 数组变量 → 总维度
    // for-each 字典元素变量 → (键类型, 值类型)：e.键 → e.first、e.值 → e.second
    private val pairVars = HashMap<String, Pair<String, String>>()
    private val userFuncs = HashSet<String>() // 用户定义函数名（特殊调用透传）
    private val userFuncRet = HashMap<String, String>() // 用户函数 → 返回类型
    private var lastLine = 0 // 上一个 #line 的行号（去重）
    private var mainGenerated = false

    /** 是否生成了 main 函数体（被引用的模块文件只允许定义，无 main） */
    val hasMainBody: Boolean get() = mainGenerated

    private val sb = StringBuilder()

    /** 中文函数名 → C++ 标准库函数 */
    private val CALL_MAP = mapOf(
        // 输入输出
        "打印" to "printf", "格式输出" to "printf",
        "输入" to "scanf", "格式输入" to "scanf",
        "读行" to "getline",
        // 数学 <cmath>
        "平方根" to "sqrt",
        "次方" to "pow", "幂" to "pow",
        "绝对值" to "abs", "浮点绝对值" to "fabs",
        "向下取整" to "floor", "向上取整" to "ceil", "四舍五入" to "round",
        "正弦" to "sin", "余弦" to "cos", "正切" to "tan",
        "反正弦" to "asin", "反余弦" to "acos", "反正切" to "atan",
        "自然对数" to "log", "常用对数" to "log10", "指数" to "exp",
        "最大" to "fmax", "最小" to "fmin",
        // 字符串 <cstring>
        "字符串长度" to "strlen",
        "字符串复制" to "strcpy", "字符串拼接" to "strcat", "字符串比较" to "strcmp",
        // 随机 / 退出 <cstdlib>
        "随机数" to "rand", "随机种子" to "srand", "退出" to "exit",
        // 字符判断 <cctype>（转大写/转小写走特殊调用，支持整串）
        "是否数字" to "isdigit", "是否字母" to "isalpha",
    )

    /** 返回 double 的标准库函数（用于类型推断） */
    private val DOUBLE_FUNCS = setOf(
        "sqrt", "pow", "fabs", "floor", "ceil", "round",
        "sin", "cos", "tan", "asin", "acos", "atan",
        "log", "log10", "exp", "fmax", "fmin",
    )

    /** 中文流/操纵符 → C++ 标准名 */
    private val VAR_MAP = mapOf(
        "输入流" to "cin", "标准输入" to "cin",
        "输出流" to "cout", "标准输出" to "cout",
        "错误流" to "cerr",
        "换行" to "endl", "换行符" to "endl",
    )

    /** 「替换」辅助函数（首次使用「替换」时生成一次） */
    private val REPLACE_HELPER = """static std::string __yl_replace(std::string s, const std::string& from, const std::string& to) {
    if (from.empty()) return s;
    size_t pos = 0;
    while ((pos = s.find(from, pos)) != std::string::npos) {
        s.replace(pos, from.size(), to);
        pos += to.size();
    }
    return s;
}"""

    /** 「转大写/转小写」整串辅助函数（首次使用时生成一次；char 参数直接走 toupper/tolower 不用这个） */
    private val CASE_HELPER = """static std::string __yl_case(std::string s, bool upper) {
    for (char& c : s) c = upper ? (char)toupper((unsigned char)c) : (char)tolower((unsigned char)c);
    return s;
}"""

    fun generate(program: List<Stmt>): String {
        sb.setLength(0)
        varTypes.clear()
        structMembers.clear()
        enumNames.clear()
        arrayDims.clear()
        pairVars.clear()
        userFuncs.clear()
        userFuncRet.clear()
        lastLine = 0
        mainGenerated = false

        val mainBody = mutableListOf<Stmt>()
        val funcs = mutableListOf<Stmt.FuncDef>()
        val structs = mutableListOf<Stmt.StructDef>()
        val enums = mutableListOf<Stmt.EnumDef>()

        for (stmt in program) {
            when (stmt) {
                is Stmt.FuncDef -> funcs.add(stmt)
                is Stmt.StructDef -> structs.add(stmt)
                is Stmt.EnumDef -> enums.add(stmt)
                else -> mainBody.add(stmt)
            }
        }

        // 复合类型 / 用户函数注册（点路径类型推断、特殊调用透传用）
        for (e in enums) enumNames.add(e.name)
        for (s in structs) structMembers[s.name] = s.members
        for (fn in funcs) {
            userFuncs.add(fn.name)
            userFuncRet[fn.name] = fn.cppReturnType
        }

        // 头部
        sb.appendLine("#include <cstdio>")
        sb.appendLine("#include <cstdlib>")
        sb.appendLine("#include <cstring>")
        sb.appendLine("#include <cctype>")
        sb.appendLine("#include <cmath>")
        sb.appendLine("#include <string>")
        sb.appendLine("#include <vector>")
        sb.appendLine("#include <map>")
        sb.appendLine("#include <iostream>")
        sb.appendLine("#include <algorithm>")
        sb.appendLine("#include <thread>")
        sb.appendLine("#include <chrono>")
        sb.appendLine("#include <ctime>")
        sb.appendLine()
        sb.appendLine("using namespace std;")
        sb.appendLine()

        // 辅助函数（用户自定义了同名函数则不生成）
        val helperScan = mainBody + funcs.flatMap { it.body }
        if (anyHelperCall(helperScan, REPLACE_MATCH)) {
            sb.appendLine(REPLACE_HELPER)
            sb.appendLine()
        }
        if (anyHelperCall(helperScan, CASE_MATCH)) {
            sb.appendLine(CASE_HELPER)
            sb.appendLine()
        }

        for (e in enums) emitEnumDef(e)
        for (s in structs) emitStructDef(s)

        // 前向声明（支持互相递归调用）
        if (funcs.isNotEmpty()) {
            for (fn in funcs) {
                val params = fn.params.joinToString(", ") { paramCode(it) }
                sb.appendLine("${fn.cppReturnType} ${fn.name}($params);")
            }
            sb.appendLine()
        }

        for (fn in funcs) emitFuncDef(fn)

        if (mainBody.isNotEmpty()) {
            mainGenerated = true
            sb.appendLine("int main() {")
            indent += 1
            for (stmt in mainBody) emitStmt(stmt)
            indent -= 1
            sb.appendLine("    return 0;")
            sb.appendLine("}")
        }

        return sb.toString().trimEnd() + "\n"
    }

    private var indent = 0

    private val pad: String get() = "    ".repeat(indent)

    // ---------- 函数 / 结构体 / 枚举 ----------

    /** 参数 C++ 代码：引用参数（非数组）加 & */
    private fun paramCode(p: Stmt.YiParam): String {
        val ref = if (p.byRef && p.dims == 0) "&" else ""
        return "${cppTypeName(p.cppType, p.dims)}$ref ${p.name}"
    }

    private fun emitFuncDef(fn: Stmt.FuncDef) {
        val params = fn.params.joinToString(", ") { paramCode(it) }
        sb.appendLine("${fn.cppReturnType} ${fn.name}($params) {")
        val savedTypes = HashMap(varTypes)
        val savedDims = HashMap(arrayDims)
        val savedPair = HashMap(pairVars)
        fn.params.forEach { p ->
            varTypes[p.name] = p.cppType
            if (p.dims > 0) arrayDims[p.name] = p.dims
        }
        indent += 1
        for (stmt in fn.body) emitStmt(stmt)
        indent -= 1
        varTypes.clear(); varTypes.putAll(savedTypes)
        arrayDims.clear(); arrayDims.putAll(savedDims)
        pairVars.clear(); pairVars.putAll(savedPair)
        sb.appendLine("}")
        sb.appendLine()
    }

    private fun emitEnumDef(e: Stmt.EnumDef) {
        val members = e.members.joinToString(", ") { (name, value) ->
            if (value != null) "$name = $value" else name
        }
        sb.appendLine("enum ${e.name} { $members };")
        sb.appendLine()
    }

    private fun emitStructDef(s: Stmt.StructDef) {
        sb.appendLine("struct ${s.name} {")
        indent += 1
        for (m in s.members) {
            val prefix = if (m.isConst) "const " else ""
            val init = m.init?.let { " = ${emitExpr(it)}" } ?: ""
            sb.appendLine("$pad$prefix${cppTypeName(m.cppType, m.dims)} ${m.name}$init;")
        }
        indent -= 1
        sb.appendLine("};")
        sb.appendLine()
    }

    // ---------- 语句 ----------

    private fun emitStmt(stmt: Stmt) {
        // 报错跳转：#line 让 C++ 编译错误行号对齐蚁语言源码；带路径时错误片段直接显示 .yl 源码行
        if (stmt.line > 0 && stmt.line != lastLine) {
            if (sourcePath != null) sb.appendLine("#line ${stmt.line} \"$sourcePath\"")
            else sb.appendLine("#line ${stmt.line}")
            lastLine = stmt.line
        }
        when (stmt) {
            is Stmt.ValDecl -> {
                // auto：按初始表达式推断实际类型记入变量表（printf 格式用）
                var dims = stmt.dims
                if (stmt.cppType == "auto" && stmt.init is Expr.ListLit && dims == 0) dims = 1
                varTypes[stmt.name] =
                    if (stmt.cppType == "auto" && stmt.init != null) exprType(stmt.init) else stmt.cppType
                if (dims > 0) arrayDims[stmt.name] = dims
                val prefix = if (stmt.isConst) "const " else ""
                val init = stmt.init?.let { " = ${emitExpr(it)}" } ?: ""
                sb.appendLine("$pad$prefix${cppTypeName(stmt.cppType, dims)} ${stmt.name}$init;")
            }
            is Stmt.Assign -> sb.appendLine("$pad${emitAssignLine(stmt.name, stmt.indices, stmt.suffix, stmt.op, stmt.value)};")
            is Stmt.Input -> {
                sb.appendLine("${pad}std::cin >> ${stmt.name};")
            }
            is Stmt.If -> {
                sb.appendLine("$pad${"if (${emitExpr(stmt.cond)}) {".trim()}")
                indent += 1
                stmt.thenBody.forEach(::emitStmt)
                indent -= 1
                if (stmt.elseBody != null) {
                    sb.appendLine("$pad} else {")
                    indent += 1
                    stmt.elseBody.forEach(::emitStmt)
                    indent -= 1
                }
                sb.appendLine("$pad}")
            }
            is Stmt.While -> {
                sb.appendLine("$pad${"while (${emitExpr(stmt.cond)}) {".trim()}")
                indent += 1
                stmt.body.forEach(::emitStmt)
                indent -= 1
                sb.appendLine("$pad}")
            }
            is Stmt.For -> {
                val init = stmt.init?.let { emitForInit(it) } ?: ""
                val cond = stmt.cond?.let { emitExpr(it) } ?: ""
                val update = stmt.update?.let { emitUpdate(it) } ?: ""
                sb.appendLine("$pad${"for ($init; $cond; $update) {".trim()}")
                indent += 1
                stmt.body.forEach(::emitStmt)
                indent -= 1
                sb.appendLine("$pad}")
            }
            is Stmt.DoWhile -> {
                sb.appendLine(pad + "do {")
                indent += 1
                stmt.body.forEach(::emitStmt)
                indent -= 1
                sb.appendLine("$pad} while (${emitExpr(stmt.cond)});")
            }
            is Stmt.ForEach -> {
                val iterableCode = if (stmt.iterable is Expr.Str)
                    "std::string(${emitExpr(stmt.iterable)})" // 防 const char[N] 连 '\0' 一起迭代
                else emitExpr(stmt.iterable)
                val (et, ed, mp) = forEachElemInfo(stmt.iterable)
                val savedTypes = HashMap(varTypes)
                val savedDims = HashMap(arrayDims)
                val savedPair = HashMap(pairVars)
                if (mp != null) {
                    pairVars[stmt.elem] = mp
                    varTypes[stmt.elem] = "std::pair<${mp.first}, ${mp.second}>"
                } else {
                    varTypes[stmt.elem] = et
                    if (ed > 0) arrayDims[stmt.elem] = ed
                }
                sb.appendLine("$pad${"for (auto &&${stmt.elem} : $iterableCode) {".trim()}")
                indent += 1
                stmt.body.forEach(::emitStmt)
                indent -= 1
                varTypes.clear(); varTypes.putAll(savedTypes)
                arrayDims.clear(); arrayDims.putAll(savedDims)
                pairVars.clear(); pairVars.putAll(savedPair)
                sb.appendLine("$pad}")
            }
            is Stmt.Switch -> {
                sb.appendLine("$pad${"switch (${emitExpr(stmt.subject)}) {".trim()}")
                indent += 1
                for (c in stmt.cases) {
                    val label = if (c.isDefault) "default:" else "case ${emitExpr(c.test!!)}:"
                    sb.appendLine("$pad$label")
                    indent += 1
                    c.body.forEach(::emitStmt)
                    indent -= 1
                }
                indent -= 1
                sb.appendLine("$pad}")
            }
            is Stmt.Break -> sb.appendLine("${pad}break;")
            is Stmt.Continue -> sb.appendLine("${pad}continue;")
            is Stmt.Return -> {
                val v = stmt.value?.let { " ${emitExpr(it)}" } ?: ""
                sb.appendLine("$pad${"return$v;".trim()}")
            }
            is Stmt.Output -> emitOutput(stmt)
            is Stmt.ExprStmt -> {
                sb.appendLine("$pad${emitExpr(stmt.expr)};")
            }
            is Stmt.SwitchCase -> throw ParseException(stmt.line, "「情况」分支只能出现在「选择」块内")
            is Stmt.StructDef -> throw ParseException(stmt.line, "「结构体」只能在顶层定义")
            is Stmt.EnumDef -> throw ParseException(stmt.line, "「枚举」只能在顶层定义")
            is Stmt.FuncDef -> throw ParseException(stmt.line, "函数不能嵌套定义")
        }
    }

    /** 赋值语句主体（无分号）：目标 += 字符串/数值混合时做拼接重写 */
    private fun emitAssignLine(name: String, indices: List<Expr>, suffix: String, op: String, value: Expr): String {
        var valueCode = emitExpr(value)
        if (op == "+=" && targetPathType(name, indices, suffix) == "std::string") {
            valueCode = concatSide(value, exprType(value))
        }
        val hostT = targetPathType(name, indices, "")
        val target = "$name${indices.joinToString("") { "[${emitExpr(it)}]" }}${suffixCodeOf(hostT, suffix)}"
        return "$target $op $valueCode"
    }

    /** for 初始段（无分号结尾） */
    private fun emitForInit(s: Stmt): String = when (s) {
        is Stmt.ValDecl -> {
            var dims = s.dims
            if (s.cppType == "auto" && s.init is Expr.ListLit && dims == 0) dims = 1
            varTypes[s.name] =
                if (s.cppType == "auto" && s.init != null) exprType(s.init) else s.cppType
            if (dims > 0) arrayDims[s.name] = dims
            val prefix = if (s.isConst) "const " else ""
            val init = s.init?.let { " = ${emitExpr(it)}" } ?: ""
            "$prefix${cppTypeName(s.cppType, dims)} ${s.name}$init"
        }
        is Stmt.Assign -> emitAssignLine(s.name, s.indices, s.suffix, s.op, s.value)
        is Stmt.ExprStmt -> when (val e = s.expr) {
            is Expr.IncDec, is Expr.AssignOp -> emitUpdate(e)
            else -> emitExpr(e)
        }
        else -> throw ParseException(s.line, "循环初始段不支持此语句")
    }

    /** for 更新段表达式 */
    private fun emitUpdate(e: Expr): String = when (e) {
        is Expr.IncDec -> {
            val hostT = targetPathType(e.name, e.indices, "")
            "${e.name}${e.indices.joinToString("") { "[${emitExpr(it)}]" }}${suffixCodeOf(hostT, e.suffix)}${e.op}"
        }
        is Expr.AssignOp -> emitAssignLine(e.name, e.indices, e.suffix, e.op, e.value)
        else -> emitExpr(e)
    }

    private fun emitOutput(stmt: Stmt.Output) {
        val args = stmt.args
        if (args.isEmpty()) {
            sb.appendLine(pad + "printf(\"\");")
            return
        }

        // 单个字符串字面量：printf("大") —— 与规范一致，不加换行
        if (args.size == 1 && args[0] is Expr.Str) {
            val s = escapeCppString((args[0] as Expr.Str).value)
            sb.appendLine(pad + "printf(\"" + s + "\");")
            return
        }

        // 其余：按类型推断格式符
        val fmt = StringBuilder()
        val values = mutableListOf<String>()
        for (arg in args) {
            when (arg) {
                is Expr.Str -> fmt.append(escapeCppString(arg.value))
                else -> {
                    val t = exprType(arg)
                    fmt.append(typeFormat(t))
                    var code = emitExpr(arg)
                    if (t == "std::string") code += ".c_str()"
                    values.add(code)
                }
            }
        }
        val tail = if (values.isEmpty()) "" else ", ${values.joinToString(", ")}"
        sb.appendLine(pad + "printf(\"" + fmt + "\"" + tail + ");")
    }

    private fun typeFormat(t: String): String = when (t) {
        "char" -> "%c"
        "long" -> "%ld"
        "unsigned int" -> "%u"
        "unsigned long" -> "%lu"
        "float", "double" -> "%f"
        "std::string", "const char*" -> "%s"
        else -> "%d" // int / short / bool 等
    }

    // ---------- 类型系统 ----------

    private fun isMapType(t: String): Boolean = t.startsWith("std::map<")

    /** 可取「.长度」的类型：字符串 / 数组 / 字典 */
    private fun isLenType(t: String): Boolean =
        t == "std::string" || t.startsWith("std::vector<") || isMapType(t)

    /** C++ 类型显示：dims 层 vector 包裹 */
    private fun cppTypeName(cpp: String, dims: Int): String {
        var t = cpp
        repeat(dims) { t = "std::vector<$t>" }
        return t
    }

    /** 拆 std::map<K, V> → (K, V)：按尖括号深度 0 的顶层逗号切分 */
    private fun mapTypeParts(mapType: String): Pair<String, String> {
        val inner = mapType.removePrefix("std::map<").removeSuffix(">")
        var depth = 0
        for (i in inner.indices) {
            when (inner[i]) {
                '<' -> depth++
                '>' -> depth--
                ',' -> if (depth == 0) {
                    return Pair(inner.substring(0, i).trim(), inner.substring(i + 1).trim())
                }
            }
        }
        return Pair(inner.trim(), "int")
    }

    private fun mapValueType(mapType: String): String = mapTypeParts(mapType).second

    /**
     * 点路径静态信息：返回 (元素类型, 数组维度, map 完整类型或 null)。
     * 支持 p.x.y 成员链、e.键 / e.值（for-each 字典元素）。
     */
    private fun pathInfo(path: String): Triple<String, Int, String?> {
        val parts = path.split('.')
        // for-each 字典元素：e / e.键 / e.值 / e.键.x
        if (parts.size >= 1 && parts[0] in pairVars) {
            val (kt, vt) = pairVars[parts[0]]!!
            var curType = when (parts.getOrNull(1)) {
                null -> "std::pair<$kt, $vt>"
                "键" -> kt
                "值" -> vt
                else -> "int"
            }
            var curDims = 0
            for (i in 2 until parts.size) {
                val members = structMembers[curType] ?: return Triple("int", 0, null)
                val m = members.firstOrNull { it.name == parts[i] } ?: return Triple("int", 0, null)
                curType = m.cppType
                curDims = m.dims
            }
            if (isMapType(curType)) return Triple("", 0, curType)
            return Triple(curType, curDims, null)
        }
        if (parts.size == 1) {
            val n = parts[0]
            val t = varTypes[n]
            if (t != null && isMapType(t)) return Triple("", 0, t)
            return Triple(t ?: "int", arrayDims[n] ?: 0, null)
        }
        var curType = varTypes[parts[0]] ?: return Triple("int", 0, null)
        var curDims = arrayDims[parts[0]] ?: 0
        for (i in 1 until parts.size) {
            if (curDims > 0) return Triple("int", 0, null) // 数组中间点访问不支持
            val members = structMembers[curType] ?: return Triple("int", 0, null)
            val m = members.firstOrNull { it.name == parts[i] } ?: return Triple("int", 0, null)
            curType = m.cppType
            curDims = m.dims
        }
        if (isMapType(curType)) return Triple("", 0, curType)
        return Triple(curType, curDims, null)
    }

    /** 路径中 for-each 字典元素的 键/值 → first/second（e.键.x → e.first.x） */
    private fun pathCode(path: String): String {
        val dot = path.indexOf('.')
        if (dot > 0) {
            val head = path.substring(0, dot)
            if (head in pairVars) {
                val rest = path.substring(dot + 1)
                val mapped = when {
                    rest == "键" -> ".first"
                    rest.startsWith("键.") -> ".first" + rest.substring(2)
                    rest == "值" -> ".second"
                    rest.startsWith("值.") -> ".second" + rest.substring(2)
                    else -> null
                }
                if (mapped != null) return head + mapped
            }
        }
        return path
    }

    private class Resolved(val code: String, val type: String)

    /** 点路径 → (C++ 代码, 类型)：处理 .长度 / e.键 / e.值 */
    private fun resolvePath(path: String): Resolved {
        if (path.endsWith(".长度")) {
            val inner = path.removeSuffix(".长度")
            val (t, d, m) = pathInfo(inner)
            if (isLenType(m ?: cppTypeName(t, d))) {
                return Resolved(pathCode(inner) + ".size()", "unsigned long")
            }
        }
        val (t, d, m) = pathInfo(path)
        val code = pathCode(path)
        return if (m != null) Resolved(code, m) else Resolved(code, cppTypeName(t, d))
    }

    /**
     * 索引/左值目标类型：name[indices] 后取 suffix 的完整类型。
     * remaining = arrayDims[name] - indices.size > 0 → 仍是数组（vector 剩余层）。
     * 字典变量索引 1 次得值类型，suffix 再沿成员链。
     */
    private fun targetPathType(name: String, indices: List<Expr>, suffix: String): String {
        val base = varTypes[name] ?: return "int"
        if (isMapType(base)) {
            if (indices.isEmpty()) return base
            val vt = mapValueType(base)
            val (t, d, m) = suffixType(vt, suffix)
            return m ?: cppTypeName(t, d)
        }
        val dims = arrayDims[name] ?: 0
        val remaining = dims - indices.size
        if (remaining > 0) return cppTypeName(base, remaining)
        val (t, d, m) = suffixType(base, suffix)
        return m ?: cppTypeName(t, d)
    }

    /** suffix（".x" / ".x.y" / ".长度"）相对宿主类型的类型：返回 (元素类型, dims, map 完整类型) */
    private fun suffixType(base: String, suffix: String): Triple<String, Int, String?> {
        if (suffix.isEmpty()) return Triple(base, 0, null)
        if (suffix == ".长度") {
            if (isLenType(base) || structMembers[base]?.any { it.name == "长度" } != true)
                return Triple("unsigned long", 0, null)
        }
        if (suffix.endsWith(".长度")) return Triple("unsigned long", 0, null)
        // 一般成员链
        val parts = suffix.removePrefix(".").split('.')
        var cur = base
        var curDims = 0
        for (p in parts) {
            if (curDims > 0 || isMapType(cur)) return Triple("int", 0, null)
            val members = structMembers[cur] ?: return Triple("int", 0, null)
            val m = members.firstOrNull { it.name == p } ?: return Triple("int", 0, null)
            cur = m.cppType
            curDims = m.dims
        }
        if (isMapType(cur)) return Triple("", 0, cur)
        return Triple(cur, curDims, null)
    }

    /** 索引/左值后的成员后缀代码：.长度 → .size()（当宿主可取长度） */
    private fun suffixCodeOf(hostType: String, suffix: String): String {
        if (suffix.isEmpty()) return ""
        if (suffix == ".长度") {
            if (isLenType(hostType)) return ".size()"
            return suffix
        }
        if (suffix.endsWith(".长度")) {
            val prefix = suffix.removeSuffix(".长度")
            val (t, d, m) = suffixType(hostType, prefix)
            if (isLenType(m ?: cppTypeName(t, d))) return prefix + ".size()"
        }
        return suffix
    }

    /** for-each 元素信息：返回 (元素类型, 剩余维度, map 键值对或 null) */
    private fun forEachElemInfo(iterable: Expr): Triple<String, Int, Pair<String, String>?> {
        val t = exprType(iterable)
        if (isMapType(t)) {
            val (k, v) = mapTypeParts(t)
            return Triple("", 0, Pair(k, v))
        }
        if (t == "std::string" || t == "const char*") return Triple("char", 0, null)
        if (t.startsWith("std::vector<")) {
            var inner = t.removePrefix("std::vector<").removeSuffix(">")
            var d = 0
            while (inner.startsWith("std::vector<")) {
                inner = inner.removePrefix("std::vector<").removeSuffix(">")
                d++
            }
            return Triple(inner, d, null)
        }
        return Triple("int", 0, null)
    }

    /** 简单类型推断：变量查声明表，字面量看形式，运算看操作数，函数查映射表 */
    private fun exprType(e: Expr): String = when (e) {
        is Expr.Num -> if (e.isFloat) "float" else "int"
        is Expr.Str -> "const char*"
        is Expr.CharLit -> "char"
        is Expr.BoolLit -> "bool"
        is Expr.Nullptr -> "int" // printf 不会直接输出 nullptr，兜底
        is Expr.Var -> resolvePath(e.name).type
        is Expr.Index -> targetPathType(e.base, e.indices, e.suffix)
        is Expr.ListLit -> e.items.firstOrNull()?.let { exprType(it) } ?: "int"
        is Expr.Binary -> when (e.op) {
            "==", "!=", ">", ">=", "<", "<=", "&&", "||" -> "bool"
            "&", "|", "^", "<<", ">>" -> promote(exprType(e.left), exprType(e.right))
            else -> {
                val lt = exprType(e.left)
                val rt = exprType(e.right)
                if (e.op == "+" && (lt == "std::string" || rt == "std::string")) "std::string"
                else promote(lt, rt)
            }
        }
        is Expr.Unary -> if (e.op == "!") "bool" else exprType(e.operand)
        is Expr.Ternary -> {
            val at = exprType(e.thenExpr)
            val bt = exprType(e.elseExpr)
            if (at == "std::string" || bt == "std::string") "std::string" else promote(at, bt)
        }
        is Expr.IncDec -> targetPathType(e.name, e.indices, e.suffix)
        is Expr.AssignOp -> targetPathType(e.name, e.indices, e.suffix)
        is Expr.Call -> when (e.callee) {
            "截取", "替换", "转文本" -> "std::string"
            "包含", "包含键" -> "bool"
            "查找", "删除键" -> "int"
            "转整数", "字符串转整数" -> "int"
            "转浮点", "字符串转浮点", "转小数" -> "double"
            "转字符" -> "char"
            "当前时间" -> "long"
            "转大写", "转小写" -> if (e.args.size == 1 && exprType(e.args[0]) == "char") "char" else "std::string"
            else -> {
                if (e.callee in userFuncs) userFuncRet[e.callee] ?: "int"
                else {
                    val fn = CALL_MAP[e.callee] ?: e.callee
                    when {
                        fn in DOUBLE_FUNCS -> "double"
                        fn == "strlen" -> "unsigned long"
                        else -> "int"
                    }
                }
            }
        }
    }

    /** 数值类型提升链（printf 格式推断用） */
    private fun promote(a: String, b: String): String {
        val num = listOf("bool", "short", "int", "unsigned int", "long", "unsigned long", "float", "double")
        if (a !in num || b !in num) return "int"
        return if (num.indexOf(a) >= num.indexOf(b)) a else b
    }

    // ---------- 表达式 ----------

    private fun emitExpr(e: Expr): String = when (e) {
        is Expr.Num -> e.value
        is Expr.Str -> "\"" + escapeCppString(e.value) + "\""
        is Expr.CharLit -> "'" + escapeCppChar(e.value) + "'"
        is Expr.BoolLit -> if (e.value) "true" else "false"
        is Expr.Nullptr -> "nullptr"
        is Expr.Var -> {
            if (e.name in VAR_MAP) VAR_MAP[e.name]!!
            else resolvePath(e.name).code
        }
        is Expr.Index -> {
            val hostT = targetPathType(e.base, e.indices, "")
            "${e.base}${e.indices.joinToString("") { "[${emitExpr(it)}]" }}${suffixCodeOf(hostT, e.suffix)}"
        }
        is Expr.ListLit -> "{${e.items.joinToString(", ") { emitExpr(it) }}}"
        is Expr.Binary -> {
            if (e.op == "+") {
                val lt = exprType(e.left)
                val rt = exprType(e.right)
                // 至少一侧是字符串 → 触发拼接重写（两侧均无字符串保持算术/位语义）
                if (lt == "std::string" || lt == "const char*" || rt == "std::string" || rt == "const char*") {
                    "(${concatSide(e.left, lt)} + ${concatSide(e.right, rt)})"
                } else {
                    "(${emitExpr(e.left)} ${e.op} ${emitExpr(e.right)})"
                }
            } else {
                "(${emitExpr(e.left)} ${e.op} ${emitExpr(e.right)})"
            }
        }
        is Expr.Unary -> "(${e.op}${emitExpr(e.operand)})"
        is Expr.Ternary -> {
            val c = emitExpr(e.cond)
            var a = emitExpr(e.thenExpr)
            var b = emitExpr(e.elseExpr)
            // 一侧 std::string 另一侧 const char* → 包 std::string（防 C++ 三元类型不匹配）
            val at = exprType(e.thenExpr)
            val bt = exprType(e.elseExpr)
            if (at == "std::string" && bt == "const char*") b = "std::string($b)"
            else if (at == "const char*" && bt == "std::string") a = "std::string($a)"
            "($c ? $a : $b)"
        }
        is Expr.IncDec -> {
            val hostT = targetPathType(e.name, e.indices, "")
            "${e.name}${e.indices.joinToString("") { "[${emitExpr(it)}]" }}${suffixCodeOf(hostT, e.suffix)}${e.op}"
        }
        is Expr.AssignOp -> emitAssignLine(e.name, e.indices, e.suffix, e.op, e.value)
        is Expr.Call -> emitSpecialCall(e.callee, e.args)
            ?: "${CALL_MAP[e.callee] ?: e.callee}(${e.args.joinToString(", ") { emitExpr(it) }})"
    }

    /** 拼接操作数重写：字符串侧包 std::string，数值侧包 to_string，char 侧包 string(1,·) */
    private fun concatSide(e: Expr, t: String): String {
        val code = emitExpr(e)
        return when (t) {
            "std::string" -> code
            "const char*" -> "std::string($code)"
            "char" -> "std::string(1, $code)"
            else -> if (t in NUMERIC_TYPES) "std::to_string($code)" else code
        }
    }

    /** 「转文本」参数包裹 */
    private fun toTextCode(e: Expr): String = concatSide(e, exprType(e))

    /** 首参若是 const char*（如字符串字面量），包 std::string 使其支持 .substr/.find */
    private fun asStdString(e: Expr): String {
        val code = emitExpr(e)
        return if (exprType(e) == "const char*") "std::string($code)" else code
    }

    /** 特殊内置调用 → C++ 代码；null 表示非特殊（走 CALL_MAP / 用户函数常规生成） */
    private fun emitSpecialCall(callee: String, args: List<Expr>): String? {
        if (callee in userFuncs) return null
        return when (callee) {
            "截取" -> if (args.size == 2) {
                "${asStdString(args[0])}.substr(${emitExpr(args[1])})"
            } else if (args.size == 3) {
                "${asStdString(args[0])}.substr(${emitExpr(args[1])}, ${emitExpr(args[2])})"
            } else null
            "查找" -> if (args.size == 2)
                "(int)${asStdString(args[0])}.find(${emitExpr(args[1])})" else null
            "包含" -> if (args.size == 2)
                "(${asStdString(args[0])}.find(${emitExpr(args[1])}) != std::string::npos)" else null
            "包含键" -> if (args.size == 2)
                "(${emitExpr(args[0])}.count(${emitExpr(args[1])}) > 0)" else null
            "删除键" -> if (args.size == 2)
                "${emitExpr(args[0])}.erase(${emitExpr(args[1])})" else null
            "替换" -> if (args.size == 3)
                "__yl_replace(${asStdString(args[0])}, ${asStdString(args[1])}, ${asStdString(args[2])})" else null
            "转文本" -> if (args.size == 1) toTextCode(args[0]) else null
            // 容器操作（向量/字符串通用；字典仅支持 清空/插入/删除 下标形式）
            "追加" -> if (args.size == 2)
                "${emitExpr(args[0])}.push_back(${emitExpr(args[1])})" else null
            "插入" -> if (args.size == 3) {
                val host = emitExpr(args[0])
                "$host.insert($host.begin() + ${emitExpr(args[1])}, ${emitExpr(args[2])})"
            } else null
            "弹出" -> when (args.size) {
                1 -> "${emitExpr(args[0])}.pop_back()"
                2 -> {
                    val host = emitExpr(args[0])
                    "$host.erase($host.begin() + ${emitExpr(args[1])})"
                }
                else -> null
            }
            "排序" -> if (args.size == 1) {
                val host = emitExpr(args[0])
                "std::sort($host.begin(), $host.end())"
            } else null
            "倒序" -> if (args.size == 1) {
                val host = emitExpr(args[0])
                "std::reverse($host.begin(), $host.end())"
            } else null
            "清空" -> if (args.size == 1)
                "${emitExpr(args[0])}.clear()" else null
            // 时间
            "休眠" -> if (args.size == 1)
                "std::this_thread::sleep_for(std::chrono::milliseconds(${emitExpr(args[0])}))" else null
            "当前时间" -> if (args.isEmpty()) "time(0)" else null
            // 类型转换（按实参类型选择：字符串走 stoi/stod，数值强转，其余走 C 函数）
            "转整数", "字符串转整数" -> if (args.size == 1) when (exprType(args[0])) {
                "std::string" -> "std::stoi(${emitExpr(args[0])})"
                "char" -> "(int)(${emitExpr(args[0])})"
                in NUMERIC_TYPES -> "(int)(${emitExpr(args[0])})"
                else -> "atoi(${emitExpr(args[0])})" // const char* / 未知
            } else null
            "转浮点", "字符串转浮点", "转小数" -> if (args.size == 1) when (exprType(args[0])) {
                "std::string" -> "std::stod(${emitExpr(args[0])})"
                in NUMERIC_TYPES -> "(double)(${emitExpr(args[0])})"
                else -> "atof(${emitExpr(args[0])})"
            } else null
            "转字符" -> if (args.size == 1)
                "(char)(${emitExpr(args[0])})" else null
            // 大小写：字符直接 toupper/tolower，字符串走整串辅助
            "转大写" -> if (args.size == 1) caseCode(args[0], true) else null
            "转小写" -> if (args.size == 1) caseCode(args[0], false) else null
            else -> null
        }
    }

    /** 转大写/转小写生成：char 用 toupper/tolower，其余按整串处理 */
    private fun caseCode(e: Expr, upper: Boolean): String {
        val code = emitExpr(e)
        return if (exprType(e) == "char") {
            "(${if (upper) "toupper" else "tolower"}((unsigned char)($code)))"
        } else {
            "__yl_case(${asStdString(e)}, ${if (upper) "true" else "false"})"
        }
    }

    // ---------- 辅助函数使用预扫描 ----------

    /** 「替换」使用判定：非用户自定义的替换调用 */
    private val REPLACE_MATCH: (Expr.Call) -> Boolean = { it.callee == "替换" && it.callee !in userFuncs }

    /** 「转大写/转小写」整串辅助判定：非用户自定义、且参数不是字符（字符直接 toupper/tolower）。
     *  预扫描阶段局部变量类型未知，按需生成：宁可多生成（未用的 static 函数无副作用）。 */
    private val CASE_MATCH: (Expr.Call) -> Boolean = {
        (it.callee == "转大写" || it.callee == "转小写") && it.callee !in userFuncs &&
            (it.args.size != 1 || exprType(it.args[0]) != "char")
    }

    /** 语句树中是否存在匹配的调用 */
    private fun anyHelperCall(stmts: List<Stmt>, match: (Expr.Call) -> Boolean): Boolean =
        stmts.any { stmtNeedsHelper(it, match) }

    private fun stmtNeedsHelper(s: Stmt, match: (Expr.Call) -> Boolean): Boolean = when (s) {
        is Stmt.ValDecl -> s.init != null && exprNeedsHelper(s.init, match)
        is Stmt.Assign -> exprNeedsHelper(s.value, match) || s.indices.any { exprNeedsHelper(it, match) }
        is Stmt.Input -> false
        is Stmt.If -> exprNeedsHelper(s.cond, match) || s.thenBody.any { stmtNeedsHelper(it, match) } ||
            (s.elseBody?.any { stmtNeedsHelper(it, match) } ?: false)
        is Stmt.While -> exprNeedsHelper(s.cond, match) || s.body.any { stmtNeedsHelper(it, match) }
        is Stmt.For -> ((s.init?.let { stmtNeedsHelper(it, match) }) ?: false) ||
            (s.cond?.let { exprNeedsHelper(it, match) } ?: false) ||
            (s.update?.let { exprNeedsHelper(it, match) } ?: false) ||
            s.body.any { stmtNeedsHelper(it, match) }
        is Stmt.DoWhile -> s.body.any { stmtNeedsHelper(it, match) } || exprNeedsHelper(s.cond, match)
        is Stmt.ForEach -> exprNeedsHelper(s.iterable, match) || s.body.any { stmtNeedsHelper(it, match) }
        is Stmt.Switch -> exprNeedsHelper(s.subject, match) || s.cases.any {
            (it.test?.let { t -> exprNeedsHelper(t, match) } ?: false) || it.body.any { st -> stmtNeedsHelper(st, match) }
        }
        is Stmt.Break, is Stmt.Continue -> false
        is Stmt.Return -> s.value?.let { exprNeedsHelper(it, match) } ?: false
        is Stmt.Output -> s.args.any { exprNeedsHelper(it, match) }
        is Stmt.ExprStmt -> exprNeedsHelper(s.expr, match)
        else -> false
    }

    private fun exprNeedsHelper(e: Expr, match: (Expr.Call) -> Boolean): Boolean = when (e) {
        is Expr.Call -> match(e) || e.args.any { exprNeedsHelper(it, match) }
        is Expr.Binary -> exprNeedsHelper(e.left, match) || exprNeedsHelper(e.right, match)
        is Expr.Unary -> exprNeedsHelper(e.operand, match)
        is Expr.Ternary -> exprNeedsHelper(e.cond, match) || exprNeedsHelper(e.thenExpr, match) || exprNeedsHelper(e.elseExpr, match)
        is Expr.Index -> e.indices.any { exprNeedsHelper(it, match) }
        is Expr.ListLit -> e.items.any { exprNeedsHelper(it, match) }
        is Expr.AssignOp -> exprNeedsHelper(e.value, match) || e.indices.any { exprNeedsHelper(it, match) }
        is Expr.IncDec -> e.indices.any { exprNeedsHelper(it, match) }
        else -> false
    }

    // ---------- 字符转义 ----------

    private fun escapeCppString(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\t", "\\t")

    /** 字符字面量转义（Lexer 存的是已处理实际字符） */
    private fun escapeCppChar(c: String): String = when (c) {
        "\\" -> "\\\\"
        "'" -> "\\'"
        "\n" -> "\\n"
        "\t" -> "\\t"
        "\u0000" -> "\\0"
        else -> c
    }

    companion object {
        private val NUMERIC_TYPES = setOf(
            "bool", "short", "int", "unsigned int", "long", "unsigned long", "float", "double",
        )
    }
}
