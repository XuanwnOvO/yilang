package com.yilang.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yilang.ui.common.consumeTaps

/** 语法条目：语法形式 + 说明 */
private data class HelpItem(val syntax: String, val desc: String)

/** 语法分组 */
private data class HelpSection(val title: String, val items: List<HelpItem>)

/** 蚁语言语法手册（与转译器实际支持的语法一致） */
private val SECTIONS = listOf(
    HelpSection(
        "变量与类型", listOf(
            HelpItem("设 整数 a = 1", "声明变量。类型：整数、小数、浮点、字符、布尔、长整、短整、字符串"),
            HelpItem("常量 小数 pi = 3.14", "声明常量，之后不能重新赋值"),
            HelpItem("设 字符串 s = \"你好\"", "字符串类型，可用 + 拼接"),
            HelpItem("设 字符 c = 'A'", "字符字面量，单引号包一个字符，支持 \\n、\\t 等转义"),
            HelpItem("设 自动 x = a + 1", "自动推断类型（初始化时必须赋值）"),
            HelpItem("设 布尔 ok = 真", "布尔字面量：真 / 假"),
        )
    ),
    HelpSection(
        "输入与输出", listOf(
            HelpItem("输出(\"文字\", a)", "打印到终端，多个值用逗号分隔，按类型自动格式化"),
            HelpItem("输入 整数 x", "等待用户在终端输入一个值，存入变量 x（需先声明）"),
            HelpItem("输入 字符串 名字", "读入一行字符串"),
        )
    ),
    HelpSection(
        "条件", listOf(
            HelpItem("如果 (a > 10) { … }", "条件成立时执行代码块"),
            HelpItem("否则 { … }", "条件不成立时执行"),
            HelpItem("否则如果 (a > 5) { … }", "多分支判断"),
            HelpItem("并且 / 或者 / 非", "逻辑运算：&& / || / !"),
            HelpItem("== != > < >= <=", "比较运算"),
            HelpItem("条件 ? 值1 : 值2", "三元运算符：条件成立取值1，否则取值2"),
        )
    ),
    HelpSection(
        "位运算", listOf(
            HelpItem("a 位与 b / a 位或 b", "按位与 & / 按位或 |"),
            HelpItem("a 位异或 b / 位非 a", "按位异或 ^ / 按位取反 ~"),
            HelpItem("a 左移 n / a 右移 n", "左移 << / 右移 >>，左移 n 位相当于乘 2ⁿ"),
        )
    ),
    HelpSection(
        "循环", listOf(
            HelpItem("当 (i < 10) { … }", "条件成立时反复执行（while）"),
            HelpItem("对于 (设 整数 i = 0; i < 10; i++) { … }", "计数循环（for）"),
            HelpItem("对于 (x 于 数组) { … }", "遍历数组/字符串/字典，每轮把一个元素存入 x"),
            HelpItem("做 { … } 当 (i < 10)", "先执行一次再判断（do-while）"),
            HelpItem("跳出 / 继续", "跳出循环 / 进入下一轮"),
        )
    ),
    HelpSection(
        "选择（switch）", listOf(
            HelpItem("选择 (x) { 情况 1: … 默认: … }", "多路分支。情况 值: 后跟语句，可贯穿；默认: 兜底"),
        )
    ),
    HelpSection(
        "函数", listOf(
            HelpItem("函数 整数 加法(整数 a, 整数 b) { 返回 a + b }", "定义有返回值的函数"),
            HelpItem("函数 空 打招呼() { 输出(\"你好\") }", "「空」表示无返回值"),
            HelpItem("函数 无 交换(整数 引用 a, 整数 引用 b) { … }", "「引用」参数：函数内修改会改到外面的变量"),
            HelpItem("加法(3, 7)", "调用函数"),
            HelpItem("返回 表达式", "返回结果；无返回值函数可只写「返回」"),
        )
    ),
    HelpSection(
        "数组", listOf(
            HelpItem("整数[] a = { 1, 2, 3 }", "声明数组（可动态增长）"),
            HelpItem("a[0] = 5", "按下标读写元素，下标从 0 开始"),
            HelpItem("追加(a, 4)", "尾部添加元素；插入(a, 0, x) 插到指定位置"),
            HelpItem("弹出(a)", "删掉末尾元素；弹出(a, i) 删指定下标"),
            HelpItem("排序(a) / 倒序(a)", "升序排序 / 反转顺序（字符串也可用）"),
            HelpItem("清空(a)", "清空所有元素"),
            HelpItem("整数[][] m = { {1, 2}, {3, 4} }", "二维数组，用 m[i][j] 读写"),
            HelpItem("a[i].x", "数组元素取成员（结构体数组）；a[i].长度 取长度"),
            HelpItem("a.长度", "元素个数（字符串也可用 s.长度）"),
            HelpItem("对于 (设 整数 i = 0; i < a.长度; i++) { 输出(a[i]) }", "遍历数组"),
        )
    ),
    HelpSection(
        "字符串操作", listOf(
            HelpItem("截取(s, 起)", "取从下标「起」到末尾的子串；截取(s, 起, 长) 取指定长度"),
            HelpItem("查找(s, 子串)", "返回子串首次出现的下标，找不到返回 -1"),
            HelpItem("包含(s, 子串)", "判断 s 中是否含有子串，返回 真 / 假"),
            HelpItem("替换(s, 旧, 新)", "把 s 中所有「旧」替换为「新」，返回新字符串"),
            HelpItem("转文本(x)", "数值/字符/字符串统一转为文本，可与字符串拼接"),
            HelpItem("转大写(s) / 转小写(s)", "整串转大写 / 小写；参数是字符时只转该字符"),
        )
    ),
    HelpSection(
        "字典（键值对）", listOf(
            HelpItem("设 字典<字符串, 整数> 年龄", "声明字典：键类型, 值类型（键支持基本类型）"),
            HelpItem("年龄[\"小明\"] = 12", "写入键值对；读：设 整数 a = 年龄[\"小明\"]"),
            HelpItem("包含键(年龄, \"小明\")", "判断键是否存在，返回 真 / 假"),
            HelpItem("删除键(年龄, \"小明\")", "删除指定键"),
            HelpItem("对于 (e 于 年龄) { e.键, e.值 }", "遍历字典：e.键 取键，e.值 取值"),
        )
    ),
    HelpSection(
        "结构体与枚举", listOf(
            HelpItem("结构体 点 { 设 整数 x = 0\n设 整数 y = 0 }", "自定义类型，成员用「设」声明（可带默认值）"),
            HelpItem("设 点 p\np.x = 3", "声明结构体变量，用 . 取成员"),
            HelpItem("枚举 颜色 { 红, 绿, 蓝 }", "枚举类型，成员从 0 递增，可用 = 指定值"),
        )
    ),
    HelpSection(
        "常用函数", listOf(
            HelpItem("平方根(x) 次方(x, y) 绝对值(x)", "数学函数，还有：向下取整、向上取整、四舍五入"),
            HelpItem("最大(a, b) 最小(a, b)", "取两数较大 / 较小值"),
            HelpItem("随机数() 随机种子(n)", "随机数（0~32767）；先设种子可让每次运行不同"),
            HelpItem("转整数(\"123\") 转浮点(\"1.5\") 转小数(x) 转字符(65)", "类型转换：字符串转数值、数值间强转、码转字符"),
            HelpItem("休眠(500) 当前时间()", "暂停 500 毫秒；当前时间（秒级时间戳）"),
            HelpItem("正弦(x) 余弦(x) 正切(x)", "三角函数，还支持反正弦、反余弦、反正切、自然对数、常用对数、指数"),
        )
    ),
    HelpSection(
        "多文件", listOf(
            HelpItem("引用 \"工具.yl\"", "写在文件开头：运行时自动加载同目录的工具.yl 一起编译；可省略 .yl 后缀"),
            HelpItem("被引用文件的内容", "只能定义函数/结构体/枚举，不能有顶层语句（输出、赋值等）"),
        )
    ),
    HelpSection(
        "注意", listOf(
            HelpItem("// 这是注释", "双斜线后到行尾的内容不会执行"),
            HelpItem("每行一条语句", "语句结尾用换行或分号分隔"),
            HelpItem("变量名可用中文", "如：设 整数 总分 = 0"),
        )
    ),
)

/** 语法帮助全屏覆盖层 */
@Composable
fun SyntaxHelpOverlay(onClose: () -> Unit, onOpenManual: () -> Unit = {}) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
            }
            Text(
                "语法帮助",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 2.dp),
            )
            TextButton(onClick = onOpenManual) { Text("完整手册") }
        }
        HorizontalDivider()
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            SECTIONS.forEach { sec ->
                item(key = sec.title) {
                    Text(
                        sec.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
                    )
                }
                items(sec.items, key = { it.syntax }) { item ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(
                            item.syntax,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            item.desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }
            }
            item { Text("", Modifier.padding(bottom = 24.dp)) }
        }
    }
}
