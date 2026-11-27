package com.openjm.reader.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import java.io.File

/**
 * 封面缩略图：直接在合成里解码本地 WebP。
 *
 * 封面是固定小图（240px 宽量级），解码开销可以忽略，所以不引图片库（Coil 等），
 * 也不做内存缓存——离开可视区就随组合一起释放。
 */
@Composable
actual fun BookCover(coverPath: String?, baseDir: String, modifier: Modifier) {
    val bitmap = remember(coverPath, baseDir) {
        val file = coverPath?.let { File(baseDir, it) }
        if (file != null && file.isFile) BitmapFactory.decodeFile(file.absolutePath) else null
    }

    if (bitmap == null) {
        Box(modifier = modifier.background(PLACEHOLDER))
    } else {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    }
}

private val PLACEHOLDER = Color(0xFFE8E8E8)
