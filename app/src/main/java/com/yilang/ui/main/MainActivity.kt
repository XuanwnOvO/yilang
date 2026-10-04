package com.yilang.ui.main

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 应用入口：单 Activity + Compose（项目 / 论坛 / 我的 + 编辑器路由见 MainNavHost.kt） */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashLogger()
        enableEdgeToEdge()
        setContent {
            YiLangTheme {
                MainNavHost()
            }
        }
    }

    /** 崩溃日志落盘：未捕获异常追加写入 files/crash_log.txt（超 512KB 清空重写），随后交回系统默认处理 */
    private fun installCrashLogger() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val f = File(filesDir, "crash_log.txt")
                if (f.length() > 512 * 1024) f.writeText("")
                f.appendText(
                    buildString {
                        append("\n===== ")
                        append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date()))
                        append(" thread=").append(t.name).append(" =====\n")
                        append(Log.getStackTraceString(e))
                    },
                )
            }
            prev?.uncaughtException(t, e)
        }
    }
}
