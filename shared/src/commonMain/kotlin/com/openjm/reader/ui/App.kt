package com.openjm.reader.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openjm.reader.data.Book

/**
 * 书架页：最近阅读倒序、显示进度、右上角导入入口。
 *
 * 封面缩略图、口令弹窗、导入进度条都还没接，见 README 的 TODO。
 */
// material3 1.9.0 里 TopAppBar 还没转正，用稳定版就得显式 opt-in
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(
    books: List<Book>,
    coverBaseDir: String,
    onImport: () -> Unit,
    onOpen: (Book) -> Unit,
) {
    MaterialTheme {
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
                            BookRow(book = book, coverBaseDir = coverBaseDir, onClick = { onOpen(book) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BookRow(book: Book, coverBaseDir: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
