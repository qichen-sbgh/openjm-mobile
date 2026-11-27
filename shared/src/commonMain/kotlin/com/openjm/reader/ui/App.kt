package com.openjm.reader.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openjm.reader.data.Book

/**
 * 书架页：最近阅读倒序、显示进度与封面，右上角导入入口。
 *
 * 长按条目进「管理」：改书名、删书。删除会连正文和封面一起删（见 `JmSandbox.deleteBook`）。
 */
// material3 1.9.0 里 TopAppBar 还没转正，用稳定版就得显式 opt-in
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun App(
    books: List<Book>,
    coverBaseDir: String,
    onImport: () -> Unit,
    onOpen: (Book) -> Unit,
    onDelete: (Book) -> Unit = {},
    onRename: (Book, String) -> Unit = { _, _ -> },
) {
    var managing by remember { mutableStateOf<Book?>(null) }
    var draftTitle by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<Book?>(null) }

    OpenjmTheme {
        Scaffold(
            topBar = {
                TopAppBar(title = { Text("openjm 书架") })
            },
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                if (books.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text("书架还是空的", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "在 QQ 里点开一个 .jm 文件，或从这里手动导入",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                        )
                        Button(onClick = onImport) { Text("导入 .jm") }
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(books, key = { it.docId }) { book ->
                            BookRow(
                                book = book,
                                coverBaseDir = coverBaseDir,
                                onClick = { onOpen(book) },
                                onLongClick = {
                                    managing = book
                                    draftTitle = book.title
                                },
                            )
                        }
                    }
                }
            }
        }

        managing?.let { book ->
            RenameDialog(
                title = draftTitle,
                onTitleChange = { draftTitle = it },
                onDismiss = { managing = null },
                onDelete = {
                    managing = null
                    pendingDelete = book
                },
                onConfirm = {
                    onRename(book, draftTitle)
                    managing = null
                },
            )
        }

        pendingDelete?.let { book ->
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text("删除《${book.title}》？") },
                text = { Text("会一并删掉已解密的内容和封面，删了找不回来（只能让对方重发）。") },
                confirmButton = {
                    TextButton(onClick = {
                        onDelete(book)
                        pendingDelete = null
                    }) { Text("删除") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDelete = null }) { Text("取消") }
                },
            )
        }
    }
}

@Composable
private fun RenameDialog(
    title: String,
    onTitleChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("管理这本书") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = onTitleChange,
                    singleLine = true,
                    label = { Text("书名") },
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = onDelete, modifier = Modifier.padding(top = 8.dp)) {
                    Text("删除这本书")
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookRow(
    book: Book,
    coverBaseDir: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(
            coverPath = book.coverPath,
            baseDir = coverBaseDir,
            modifier = Modifier
                .size(width = 48.dp, height = 64.dp)
                .clip(RoundedCornerShape(4.dp)),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = progressLabel(book),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
            )
        }
    }
}

private fun progressLabel(book: Book): String {
    if (book.totalPages <= 0) return "尚未打开"
    val percent = ((book.lastPage + 1) * 100 / book.totalPages).coerceIn(0, 100)
    return "第 ${book.lastPage + 1}/${book.totalPages} 页 · $percent%"
}
