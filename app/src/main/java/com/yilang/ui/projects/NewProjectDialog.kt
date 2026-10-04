package com.yilang.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 新建项目弹窗：项目名 + 类型（蚁语言/C++）+ 构建方式（ndk-build/Clang/CMake） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewProjectDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, type: ProjectType, build: BuildSystem) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(ProjectType.YILANG) }
    var build by remember { mutableStateOf(BuildSystem.CLANG) }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val n = name.trim()
        when {
            n.isEmpty() -> error = "项目名不能为空"
            n.contains(Regex("[\\\\/:*?\"<>| ]")) -> error = "项目名不能含空格或 \\/:*?\"<>| 字符"
            else -> onConfirm(n, type, build)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建项目") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text("项目名") },
                    singleLine = true,
                    supportingText = {
                        Text(
                            error ?: "将生成 <文件目录>/projects/<项目名>/",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("项目类型", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProjectType.entries.forEach { t ->
                        FilterChip(
                            selected = type == t,
                            onClick = { type = t },
                            label = { Text(t.label) },
                        )
                    }
                }
                Text("构建方式", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BuildSystem.entries.forEach { b ->
                        FilterChip(
                            selected = build == b,
                            onClick = { build = b },
                            label = { Text(b.label) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        modifier = Modifier.padding(4.dp),
    )
}
