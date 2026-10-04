package com.yilang.ui.common

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yilang.ui.projects.Project
import java.io.File

/**
 * 保存代码到项目：选择项目 + 输入文件名（无后缀自动补默认后缀）。
 * 供 AI 助手、语法手册等场景复用。
 */
@Composable
fun SaveToProjectDialog(
    ctx: Context,
    code: String,
    defaultExt: String,
    onDismiss: () -> Unit,
) {
    val projects = remember { Project.loadAll(ctx) }
    var projectIdx by remember { mutableStateOf(if (projects.isEmpty()) -1 else 0) }
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("存到项目") },
        text = {
            if (projects.isEmpty()) {
                Text("还没有项目，请先在「项目」页新建。")
            } else {
                Column {
                    Text(
                        "选择项目：",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(top = 4.dp),
                    ) {
                        projects.forEachIndexed { i, p ->
                            FilterChip(
                                selected = projectIdx == i,
                                onClick = { projectIdx = i },
                                label = { Text(p.name) },
                                modifier = Modifier.padding(end = 6.dp),
                            )
                        }
                    }
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("文件名（自动加$defaultExt）") },
                        singleLine = true,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = projects.isNotEmpty() && name.isNotBlank(),
                onClick = {
                    val p = projects[projectIdx]
                    var n = name.trim()
                    if (!n.contains('.')) n += defaultExt
                    runCatching { File(p.dir, n).writeText(code, Charsets.UTF_8) }
                        .onSuccess {
                            Toast.makeText(ctx, "已保存到「${p.name}/$n」", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        }
                        .onFailure {
                            Toast.makeText(ctx, "保存失败：${it.message}", Toast.LENGTH_SHORT).show()
                        }
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
