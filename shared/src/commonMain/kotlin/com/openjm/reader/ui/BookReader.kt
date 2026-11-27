package com.openjm.reader.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.openjm.reader.data.Book

/**
 * 阅读页：标题栏 + 分页渲染。
 *
 * 只做"看"这一件事——进度写入由调用方决定节奏（500ms 防抖 + 退出补写），
 * 这样共享层不依赖任何存储实现。
 */
// material3 1.9.0 里 TopAppBar 还没转正
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookReader(
    book: Book,
    filePath: String,
    onBack: () -> Unit,
    onDocumentLoaded: (totalPages: Int) -> Unit,
    onProgress: (page: Int, scrollRatio: Float) -> Unit,
) {
    OpenjmTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = book.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Text("返回") }
                    },
                )
            },
        ) { padding ->
            PdfReaderCanvas(
                filePath = filePath,
                initialPage = book.lastPage,
                initialScrollRatio = book.scrollRatio,
                onDocumentLoaded = onDocumentLoaded,
                onProgressChanged = onProgress,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }
}
