package com.openjm.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** iOS 侧封面（M4 之前冻结）：先给占位块，等 PDFKit 出图后再接 WebP 解码。 */
@Composable
actual fun BookCover(coverPath: String?, baseDir: String, modifier: Modifier) {
    Box(modifier = modifier.background(Color(0xFFE8E8E8)))
}
