package com.yilang.ui.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 工具链首启弹窗状态机：询问 → 下载中 → 完成 / 失败 */
sealed interface ToolchainSetupState {
    data object Ask : ToolchainSetupState
    data class Downloading(
        val message: String,
        val downloaded: Long,
        val total: Long,
    ) : ToolchainSetupState
    data object Done : ToolchainSetupState
    data class Failed(val message: String) : ToolchainSetupState
}

/**
 * 工具链安装弹窗：
 * - Ask：说明用途与体积，用户决定是否立即下载
 * - Downloading：进度条 + 已下载/总量 + 百分比（下载中不可关闭）
 * - Done：就绪提示；Failed：错误 + 重试
 */
@Composable
fun ToolchainSetupDialog(
    state: ToolchainSetupState,
    onDismiss: () -> Unit,
    onStart: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {
            // 下载进行中不允许误触关闭（否则易产生半装状态）
            if (state !is ToolchainSetupState.Downloading) onDismiss()
        },
        title = {
            Text(
                when (state) {
                    is ToolchainSetupState.Ask -> "需要下载编译工具链"
                    is ToolchainSetupState.Downloading -> "正在准备工具链"
                    is ToolchainSetupState.Done -> "工具链就绪"
                    is ToolchainSetupState.Failed -> "下载失败"
                },
            )
        },
        text = {
            when (state) {
                is ToolchainSetupState.Ask -> Text(
                    "运行蚁语言程序需要 C++ 编译工具链（约 69MB，仅首次下载，之后完全离线）。\n\n建议在 Wi-Fi 环境下下载。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                is ToolchainSetupState.Downloading -> {
                    val fraction =
                        if (state.total > 0) (state.downloaded.toFloat() / state.total).coerceIn(0f, 1f) else 0f
                    Column {
                        Text(state.message, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "%.1f / %.1f MB（%d%%）".format(
                                state.downloaded / 1048576f,
                                state.total / 1048576f,
                                (fraction * 100).toInt(),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "下载期间请保持应用在前台",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                is ToolchainSetupState.Done -> Text(
                    "C++ 编译工具链安装完成，现在可以运行蚁语言程序了。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                is ToolchainSetupState.Failed -> Text(
                    "${state.message}\n\n请检查网络后重试（官方源与清华镜像会自动切换）。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            when (state) {
                is ToolchainSetupState.Ask -> TextButton(onClick = onStart) { Text("立即下载") }
                is ToolchainSetupState.Downloading -> {}
                is ToolchainSetupState.Done -> TextButton(onClick = onDismiss) { Text("开始使用") }
                is ToolchainSetupState.Failed -> TextButton(onClick = onStart) { Text("重试") }
            }
        },
        dismissButton = {
            when (state) {
                is ToolchainSetupState.Ask -> TextButton(onClick = onDismiss) { Text("暂不") }
                is ToolchainSetupState.Failed -> TextButton(onClick = onDismiss) { Text("取消") }
                else -> {}
            }
        },
    )
}
