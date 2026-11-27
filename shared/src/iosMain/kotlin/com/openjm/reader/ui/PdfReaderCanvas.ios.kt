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
 * TODO(ios)：用 `UIKitView` 嵌原生 `PDFKit`（`PDFView`），
 * 顺带用 `PDFDocument` 第一页生成封面缩略图。
 *
 * 现在只渲染占位，保证工程能编译。
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
            text = "PDF 渲染待接入（PDFKit）\n$filePath",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
