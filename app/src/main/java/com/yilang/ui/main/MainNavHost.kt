package com.yilang.ui.main

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.yilang.ui.ai.AiChatScreen
import com.yilang.ui.editor.EditorScreen
import com.yilang.ui.forum.ForumPlaceholder
import com.yilang.ui.manual.ManualListScreen
import com.yilang.ui.manual.ManualPageScreen
import com.yilang.ui.profile.ProfilePlaceholder
import com.yilang.ui.projects.Project
import com.yilang.ui.projects.ProjectsScreen
import java.net.URLEncoder
import java.net.URLDecoder

// ── 蚁语言品牌配色：琥珀蜜 × 暖炭底（不用壁纸动态取色，保持品牌一致） ──
private val LightScheme = lightColorScheme(
    primary = Color(0xFF8A5A00), // 深琥珀：白底上可读
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDDB0), // 蜜色
    onPrimaryContainer = Color(0xFF2C1A00),
    secondary = Color(0xFF6F5B40),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF6E7CE),
    onSecondaryContainer = Color(0xFF271A05),
    tertiary = Color(0xFF4C6448), // 苔绿：C++ 等辅助标识
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFCEEAC6),
    onTertiaryContainer = Color(0xFF0B2009),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFCF8F2), // 暖米白
    onBackground = Color(0xFF1F1B13),
    surface = Color(0xFFFCF8F2),
    onSurface = Color(0xFF1F1B13),
    surfaceVariant = Color(0xFFF0E0C7),
    onSurfaceVariant = Color(0xFF4F4539),
    outline = Color(0xFF817567),
    outlineVariant = Color(0xFFD9CBB8),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFFFB954), // 蜜琥珀
    onPrimary = Color(0xFF462B00),
    primaryContainer = Color(0xFF653F00),
    onPrimaryContainer = Color(0xFFFFDDB4),
    secondary = Color(0xFFDCC3A1),
    onSecondary = Color(0xFF3D2E16),
    secondaryContainer = Color(0xFF55442A),
    onSecondaryContainer = Color(0xFFF6E7CE),
    tertiary = Color(0xFFB2CCAB),
    onTertiary = Color(0xFF1E351B),
    tertiaryContainer = Color(0xFF344C2F),
    onTertiaryContainer = Color(0xFFCEEAC6),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF17130C), // 暖炭黑
    onBackground = Color(0xFFEDE1CF),
    surface = Color(0xFF17130C),
    onSurface = Color(0xFFEDE1CF),
    surfaceVariant = Color(0xFF4F4539),
    onSurfaceVariant = Color(0xFFD3C4B4),
    outline = Color(0xFF9C8F80),
    outlineVariant = Color(0xFF3B3428),
)

/** MD3 主题：品牌深浅两套配色，跟随系统深浅色 */
@Composable
fun YiLangTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        content = content,
    )
}

private data class TabItem(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

/** 底部导航（项目 / 论坛 / 我的）+ 编辑器路由 */
@Composable
fun MainNavHost() {
    val nav = rememberNavController()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var reloadKey by rememberSaveable { mutableIntStateOf(0) }

    // 首次启动引导：prefs「onboarding_done」未写入时先显示（纯展示，不建示例项目）
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("yilang_app", Context.MODE_PRIVATE) }
    var showOnboarding by remember {
        mutableStateOf(!prefs.getBoolean("onboarding_done", false))
    }
    if (showOnboarding) {
        OnboardingScreen(onDone = {
            prefs.edit().putBoolean("onboarding_done", true).apply()
            showOnboarding = false
        })
        return
    }

    val tabs = listOf(
        TabItem("projects", "项目", Icons.Filled.Home),
        TabItem("ai", "AI 助手", Icons.AutoMirrored.Filled.Send),
        TabItem("forum", "论坛", Icons.AutoMirrored.Filled.List),
        TabItem("profile", "我的", Icons.Filled.Person),
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { idx, tab ->
                    NavigationBarItem(
                        selected = selectedTab == idx,
                        onClick = {
                            selectedTab = idx
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = "projects",
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = padding.calculateBottomPadding()),
            // 全局默认：tab 切换瞬时完成（不动画）。进出编辑器/手册的转场由各自路由单独定义。
            // 之前每次切 tab 都交叉淡化 220ms，两张屏要同时渲染，调试构建下显得又卡又肉。
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {
            composable("projects") {
                ProjectsScreen(
                    onOpenProject = { p: Project ->
                        val encoded = URLEncoder.encode(p.dir.absolutePath, "UTF-8")
                        nav.navigate("editor/$encoded") { launchSingleTop = true }
                    },
                    reloadKey = reloadKey,
                )
            }
            composable("ai") { AiChatScreen() }
            composable("forum") { ForumPlaceholder() }
            composable("profile") { ProfilePlaceholder(onOpenManual = { nav.navigate("manual") }) }
            composable(
                "manual",
                enterTransition = { slideInVertically(tween(300)) { it / 5 } + fadeIn(tween(200)) },
                popExitTransition = { slideOutVertically(tween(260)) { it / 5 } + fadeOut(tween(160)) },
            ) {
                ManualListScreen(onOpenPage = { nav.navigate("manual/$it") })
            }
            composable(
                "manual/{id}",
                enterTransition = { slideInHorizontally(tween(300)) { it / 4 } + fadeIn(tween(200)) },
                popExitTransition = { slideOutHorizontally(tween(260)) { it / 4 } + fadeOut(tween(160)) },
            ) { entry ->
                ManualPageScreen(
                    pageId = entry.arguments?.getString("id") ?: "hello",
                    onBack = { nav.popBackStack() },
                )
            }
            composable(
                "editor/{dir}",
                enterTransition = { slideInHorizontally(tween(300)) { it / 4 } + fadeIn(tween(200)) },
                popExitTransition = { slideOutHorizontally(tween(260)) { it / 4 } + fadeOut(tween(160)) },
            ) { entry ->
                val dir = entry.arguments?.getString("dir")?.let { URLDecoder.decode(it, "UTF-8") } ?: ""
                EditorScreen(
                    projectDir = dir,
                    onBack = {
                        reloadKey++
                        nav.popBackStack()
                    },
                    onOpenManual = { nav.navigate("manual") },
                )
            }
        }
    }
}
