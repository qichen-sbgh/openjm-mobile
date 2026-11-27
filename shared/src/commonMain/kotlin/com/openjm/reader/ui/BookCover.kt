package com.openjm.reader.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 书架封面：`coverPath` 是相对沙盒根目录的路径（`covers/{doc_id}.webp`），
 * 需要 `baseDir` 才能拼出真实文件——书架表里只存相对路径，换机/重装不会留死链。
 *
 * 拿不到封面时各平台退回一块纯色占位，不阻塞书架渲染。
 */
@Composable
expect fun BookCover(
    coverPath: String?,
    baseDir: String,
    modifier: Modifier = Modifier,
)
