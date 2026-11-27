package com.openjm.reader.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 平台 PDF 渲染桥。
 *
 * Android 用 PDFium 社区封装（`AndroidView`），iOS 用原生 `PDFKit`（`UIKitView`）——
 * 避开 Android 厂商 ROM 的字体与 `PdfRenderer` 缩放模糊问题。
 *
 * 进度回调由调用方做 500ms 防抖后再落库，页面退出时补写一次。
 */
@Composable
expect fun PdfReaderCanvas(
    filePath: String,
    initialPage: Int,
    initialScrollRatio: Float,
    onDocumentLoaded: (totalPages: Int) -> Unit,
    onProgressChanged: (page: Int, scrollRatio: Float) -> Unit,
    modifier: Modifier = Modifier,
)
