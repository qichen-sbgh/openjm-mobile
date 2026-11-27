package com.openjm.reader.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * TODO(android)：接 PDFium 社区封装（如 `io.legere:pdfiumandroid`），用 `AndroidView` 挂载。
 * 接入时要做三件事：首帧封面渲染、总页数回调、滚动/翻页进度回调（500ms 防抖由上层做）。
 *
 * 现在只渲染占位，保证工程能编译、书架能跳到阅读页。
 */
@Composable
actual fun PdfReaderCanvas(
    filePath: String,
    initialPage: Int,
    initialScrollRatio: Float,
    onDocumentLoaded: (totalPages: Int) -> Unit,
    onProgressChanged: (page: Int, scrollRatio: Float) -> Unit,
    modifier: Modifier,
) {
    Box(modifier = modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text = "PDF 渲染待接入（PDFium）\n$filePath",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
