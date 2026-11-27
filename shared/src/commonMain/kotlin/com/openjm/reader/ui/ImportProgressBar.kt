package com.openjm.reader.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 导入进度条（`progress == null` 时不占位）。
 *
 * `progress < 0` 表示"还不知道总大小"（QQ 的 `content://` 有时拿不到 SIZE），
 * 这时用不确定进度条——猜一个假百分比比空转更让人焦虑。
 */
@Composable
fun ImportProgressBar(progress: Float?, modifier: Modifier = Modifier) {
    if (progress == null) return

    Surface(tonalElevation = 3.dp, modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            val percent = (progress * 100).toInt().coerceIn(0, 100)
            Text(
                text = if (progress < 0f) "正在解密…" else "正在解密 $percent%",
                style = MaterialTheme.typography.bodySmall,
            )
            if (progress < 0f) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            } else {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
    }
}
