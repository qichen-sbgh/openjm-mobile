package com.openjm.reader.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 底部页码条：显示「第几页 / 共几页」，拖滑块直接跳页。
 *
 * 几百页的书靠纯滑动翻页不现实——这是阅读器最常用的一条捷径。
 */
@Composable
fun PageJumpBar(
    page: Int,
    pageCount: Int,
    onSeek: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pageCount <= 1) return

    Surface(
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 3.dp,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 8.dp),
        ) {
            Text(
                text = "${page + 1}/$pageCount",
                style = MaterialTheme.typography.labelMedium,
            )
            Slider(
                value = page.toFloat(),
                onValueChange = { onSeek(it.roundToInt()) },
                valueRange = 0f..(pageCount - 1).toFloat(),
                modifier = Modifier.width(220.dp).padding(start = 12.dp),
            )
        }
    }
}
