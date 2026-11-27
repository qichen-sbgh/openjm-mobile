package com.openjm.reader.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Android 侧 PDF 渲染：PDFium（[PdfPageRenderer]）+ 纵向分页列表。
 *
 * 几个刻意的选择：
 * - **一页一个 Bitmap**，离屏就被 LazyColumn 回收，内存与窗口大小相关、与文档大小无关（NFR-02）；
 * - **占位用首页比例**，渲染完成前先撑开同样高度，滚动时列表不跳；
 * - **进度只报"第几页 + 页内比例"**，500ms 防抖与落库交给上层（`docs/tech/mobile.md` §6）。
 *
 * `initialScrollRatio` 会在目标页渲染完成、拿到真实页高之后再滚一次——
 * 没有高度基准的话，比例没有意义（这也是它排在持久化之后的理由）。
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
    val context = LocalContext.current
    val renderer = remember(filePath) { PdfPageRenderer(File(filePath)) }
    var pageCount by remember(filePath) { mutableStateOf(0) }
    var pageRatio by remember(filePath) { mutableStateOf(DEFAULT_RATIO) }
    var error by remember(filePath) { mutableStateOf<String?>(null) }

    DisposableEffect(filePath) {
        onDispose { renderer.close() }
    }

    LaunchedEffect(filePath) {
        val opened = withContext(Dispatchers.IO) { runCatching { renderer.open(context) } }
        opened.onSuccess { count ->
            pageCount = count
            pageRatio = withContext(Dispatchers.IO) { renderer.aspectRatio() }
            onDocumentLoaded(count)
        }.onFailure { failure ->
            error = failure.message ?: "PDF 打不开"
        }
    }

    when {
        error != null -> Box(
            modifier = modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("无法渲染这份 PDF：$error", style = MaterialTheme.typography.bodyMedium)
        }

        pageCount <= 0 -> Box(
            modifier = modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }

        else -> {
            val heights = remember(pageCount) { mutableStateMapOf<Int, Int>() }
            val listState = rememberLazyListState(
                initialFirstVisibleItemIndex = initialPage.coerceIn(0, pageCount - 1),
            )

            // 恢复页内偏移：页高要等该页渲染完才知道，所以等 heights 里出现目标页再滚。
            LaunchedEffect(pageCount, initialPage, initialScrollRatio) {
                if (initialScrollRatio <= 0f) return@LaunchedEffect
                val height = snapshotFlow { heights[initialPage] }.filterNotNull().first()
                if (height > 0) {
                    listState.scrollToItem(
                        index = initialPage,
                        scrollOffset = (initialScrollRatio * height).toInt().coerceIn(0, height),
                    )
                }
            }

            LaunchedEffect(listState, pageCount) {
                snapshotFlow {
                    val page = listState.firstVisibleItemIndex
                    val offset = listState.firstVisibleItemScrollOffset
                    val height = heights[page] ?: 0
                    page to if (height > 0) (offset.toFloat() / height).coerceIn(0f, 1f) else 0f
                }.collect { (page, ratio) -> onProgressChanged(page, ratio) }
            }

            LazyColumn(state = listState, modifier = modifier.fillMaxSize()) {
                items(pageCount) { index ->
                    PdfPageItem(
                        renderer = renderer,
                        index = index,
                        placeholderRatio = pageRatio,
                        modifier = Modifier.fillMaxWidth(),
                        onMeasured = { height -> heights[index] = height },
                    )
                }
            }
        }
    }
}

@Composable
private fun PdfPageItem(
    renderer: PdfPageRenderer,
    index: Int,
    placeholderRatio: Float,
    modifier: Modifier,
    onMeasured: (Int) -> Unit,
) {
    BoxWithConstraints(modifier) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }.toInt()
        var bitmap by remember(index, widthPx) { mutableStateOf<Bitmap?>(null) }

        LaunchedEffect(index, widthPx) {
            val rendered = withContext(Dispatchers.IO) { renderer.render(index, widthPx) }
            bitmap = rendered
            rendered?.let { onMeasured(it.height) }
        }

        val current = bitmap
        if (current != null) {
            Image(
                bitmap = current.asImageBitmap(),
                contentDescription = "第 ${index + 1} 页",
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // aspectRatio 要的是宽/高，页面比例存的是高/宽
                    .aspectRatio(1f / placeholderRatio)
                    .background(Color(0xFFF2F2F2)),
            )
        }
    }
}

private const val DEFAULT_RATIO = 1.4142f
