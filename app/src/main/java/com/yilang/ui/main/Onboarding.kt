package com.yilang.ui.main

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * 首次启动引导（3 页横滑）：认识蚁语言 → 三步上手 → 开始使用。
 * 纯展示、不建任何示例项目/文件；完成后写 prefs「onboarding_done」，之后不再出现。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val pages = listOf(Welcome, Steps, Features)
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // 跳过（最后一页隐藏）
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            if (pagerState.currentPage < pages.lastIndex) {
                TextButton(onClick = onDone) { Text("跳过") }
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) { page ->
            when (pages[page]) {
                Welcome -> PageColumn {
                    Text("🐜", fontSize = 72.sp)
                    Spacer(Modifier.height(18.dp))
                    Text("蚁语言", style = MaterialTheme.typography.headlineLarge)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "用中文写代码\n一键编译成 C++ 程序",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                Steps -> PageColumn {
                    Text("三步上手", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(24.dp))
                    Step("①", "新建项目", "项目页点「＋」创建你的第一个项目")
                    Spacer(Modifier.height(16.dp))
                    Step("②", "写中文代码", "输出(\"你好，世界！\") 就是一个完整程序")
                    Spacer(Modifier.height(16.dp))
                    Step("③", "点 ▶ 运行", "自动转译 C++ 并编译，结果在终端里看")
                }
                Features -> PageColumn {
                    Text("不止这些", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(24.dp))
                    Step("🤖", "AI 助手", "报错一键问 AI，选中代码让 AI 讲解")
                    Spacer(Modifier.height(16.dp))
                    Step("📍", "报错定位", "点击终端错误行，直达中文源码对应行")
                    Spacer(Modifier.height(16.dp))
                    Step("📖", "语法手册", "忘了语法随时查，「我的」页里就有")
                }
            }
        }

        // 指示点 + 主按钮
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(pages.size) { i ->
                    val active = pagerState.currentPage == i
                    Box(
                        Modifier
                            .size(if (active) 10.dp else 8.dp)
                            .clip(CircleShape)
                            .background(
                                if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                            ),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            val last = pagerState.currentPage == pages.lastIndex
            Button(
                onClick = {
                    if (last) onDone()
                    else scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                },
            ) { Text(if (last) "开始使用" else "下一步") }
        }
    }
}

private data object Welcome
private data object Steps
private data object Features

@Composable
private fun PageColumn(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { content() }
}

@Composable
private fun Step(badge: String, title: String, desc: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(badge, fontSize = 26.sp)
        Spacer(Modifier.size(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                desc,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
