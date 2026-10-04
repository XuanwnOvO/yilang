package com.yilang.ui.editor

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/** 可在编辑器中打开的文本源码扩展名 */
internal val OPENABLE_EXT = setOf("yl", "cpp", "c", "h", "hpp", "txt")

/** 图片扩展名（文件树里显示缩略图） */
private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

/**
 * 文件树抽屉内容：递归列出项目目录，文件夹点击折叠/展开，
 * 源码文件点击回调 onOpenFile；图片文件显示小缩略图。
 * 每个文件/文件夹行带 ⋮ 菜单（重命名/删除）；标题栏可新建文件夹。
 * tick 变化时重新扫描目录（如新建文件后刷新）。
 */
@Composable
fun FileTreeDrawerContent(
    root: File,
    tick: Int,
    expanded: MutableMap<String, Boolean>, // 路径 → 是否展开（外部持有：抽屉关闭重开不丢）
    onOpenFile: (File) -> Unit,
    onRename: (File) -> Unit = {},
    onDelete: (File) -> Unit = {},
    onCreateFolder: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "文件",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(16.dp),
            )
            Text(
                "＋ 文件夹",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .clickable(onClick = onCreateFolder)
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            // tick 变化时强制重建子树，重新扫描文件系统
            key(tick) {
                TreeNodes(dir = root, depth = 0, expanded = expanded, onOpenFile = onOpenFile, onRename = onRename, onDelete = onDelete)
            }
        }
    }
}

@Composable
private fun TreeNodes(
    dir: File,
    depth: Int,
    expanded: MutableMap<String, Boolean>,
    onOpenFile: (File) -> Unit,
    onRename: (File) -> Unit,
    onDelete: (File) -> Unit,
) {
    // 目录列表缓存：重组不重扫盘；tick/路径变化时（key 重建子树）才刷新
    val entries = remember(dir.absolutePath) {
        (dir.listFiles() ?: emptyArray())
            .filter { !it.name.startsWith(".") }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }
    entries.forEach { entry ->
        val indent = Modifier.padding(start = (12 + depth * 16).dp)
        if (entry.isDirectory) {
            val open = expanded[entry.absolutePath] == true
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded[entry.absolutePath] = !open }
                    .then(indent)
                    .padding(top = 8.dp, bottom = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (open) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    entry.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                // 行级 ⋮ 菜单：重命名 / 删除（删除整个文件夹）
                Box {
                    var menuOpen by remember { mutableStateOf(false) }
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = "文件夹操作",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("重命名") },
                            onClick = { menuOpen = false; onRename(entry) },
                        )
                        DropdownMenuItem(
                            text = { Text("删除") },
                            onClick = { menuOpen = false; onDelete(entry) },
                        )
                    }
                }
            }
            if (open) {
                TreeNodes(entry, depth + 1, expanded, onOpenFile, onRename, onDelete)
            }
        } else {
            val openable = entry.extension.lowercase() in OPENABLE_EXT
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = openable) { onOpenFile(entry) }
                    .then(indent)
                    .padding(top = 8.dp, bottom = 8.dp, start = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FileBadge(entry)
                Text(
                    entry.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                )
                // 行级 ⋮ 菜单：重命名 / 删除
                Box {
                    var menuOpen by remember { mutableStateOf(false) }
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = "文件操作",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("重命名") },
                            onClick = { menuOpen = false; onRename(entry) },
                        )
                        DropdownMenuItem(
                            text = { Text("删除") },
                            onClick = { menuOpen = false; onDelete(entry) },
                        )
                    }
                }
            }
        }
    }
}

/** 按扩展名渲染类型徽标；图片文件渲染缩略图 */
@Composable
private fun FileBadge(f: File) {
    val ext = f.extension.lowercase()
    when {
        ext in IMAGE_EXT -> FileThumb(f)
        ext == "yl" -> Badge("蚁", MaterialTheme.colorScheme.primary)
        ext == "cpp" -> Badge("C+", MaterialTheme.colorScheme.tertiary)
        ext == "c" -> Badge("C", MaterialTheme.colorScheme.tertiary)
        ext == "h" || ext == "hpp" -> Badge("H", MaterialTheme.colorScheme.secondary)
        else -> Badge("•", MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun Badge(label: String, color: Color) {
    Box(
        Modifier
            .size(24.dp)
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(5.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = color, fontSize = 11.sp)
    }
}

@Composable
private fun FileThumb(f: File) {
    val bmp = remember(f.absolutePath, f.lastModified()) {
        // 先只读尺寸，按 24px 目标算采样率再真正解码，避免大图占内存
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 24 && bounds.outHeight / (sample * 2) >= 24) sample *= 2
        BitmapFactory.decodeFile(
            f.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }?.asImageBitmap()
    if (bmp != null) {
        Image(bmp, null, Modifier.size(24.dp))
    } else {
        Badge("•", MaterialTheme.colorScheme.outline)
    }
}
