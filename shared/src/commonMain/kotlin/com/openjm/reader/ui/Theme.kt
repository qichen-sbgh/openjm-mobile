package com.openjm.reader.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * 跟随系统的明暗主题。
 *
 * 阅读器尤其需要：白底 PDF 在深色环境里刺眼，深色外壳至少让周边不扎眼
 * （PDF 本身不反色——反色会把扫描件搞成负片，那是产品事故）。
 */
@Composable
fun OpenjmTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}
