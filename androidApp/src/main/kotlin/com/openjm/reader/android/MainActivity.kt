package com.openjm.reader.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.openjm.reader.crypto.JmImporter
import com.openjm.reader.crypto.openJmSource
import com.openjm.reader.data.InMemoryBookRepository
import com.openjm.reader.ui.App

/**
 * Android 入口。
 *
 * 当前完成的事：QQ 唤起（VIEW / SEND）与 SAF 兜底入口都能拿到 URI，
 * 并读 28 字节头判断“是不是 .jm、要不要口令”。
 *
 * TODO(android) 完整导入链路：
 *  1. 后台线程单遍读流：`JmImporter.import(...)` 直接写 `filesDir/tmp/{uuid}.tmp`；
 *  2. 成功后 `rename` 到 `filesDir/books/{doc_id}.pdf`；
 *  3. 异步渲染首页封面到 `filesDir/covers/{doc_id}.webp`；
 *  4. 写 Room KMP 书架表（doc_id 去重、保留阅读进度）。
 */
class MainActivity : ComponentActivity() {

    private val repository = InMemoryBookRepository()

    private val pickJm = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::inspectHeader)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            App(
                books = repository.recent(),
                onImport = { pickJm.launch(arrayOf("*/*")) },
                onOpen = { /* TODO(android)：跳到 PdfReaderCanvas */ },
            )
        }

        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let(::inspectHeader)
        }
    }

    private fun inspectHeader(uri: Uri) {
        // 只读 28 字节，主线程开销可忽略；完整导入必须挪到后台。
        runCatching {
            val source = contentResolver.openJmSource(uri)
                ?: throw IllegalStateException("文件已失效，请在 QQ 重新下载")
            try {
                JmImporter.readHeader(source).first
            } finally {
                source.close()
            }
        }.onSuccess { header ->
            val passwordHint = if (header.isPasswordProtected) "需要口令" else "无需口令"
            Toast.makeText(
                this,
                "检测到 .jm v1（key_id=${header.keyId}，$passwordHint）",
                Toast.LENGTH_LONG,
            ).show()
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "无法打开这个文件", Toast.LENGTH_LONG).show()
        }
    }
}
