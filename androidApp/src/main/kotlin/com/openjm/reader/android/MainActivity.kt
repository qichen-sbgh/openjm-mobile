package com.openjm.reader.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import com.openjm.reader.crypto.JmError
import com.openjm.reader.crypto.parseRootKeys
import com.openjm.reader.data.Book
import com.openjm.reader.data.InMemoryBookRepository
import com.openjm.reader.data.JmSandbox
import com.openjm.reader.data.JmSandboxImporter
import com.openjm.reader.ui.App
import com.openjm.reader.ui.BookReader
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Android 入口：QQ 唤起（VIEW / SEND）与 SAF 兜底共用同一条导入链路。
 *
 * 链路：`content://` 流 → [JmSandboxImporter]（单遍校验 + 解密写 `.tmp`）→
 * 校验通过才 rename 成 `files/books/{doc_id}.pdf` → 写书架。
 * 导入跑在单线程 worker 上，主线程只负责刷 UI：大文件也不会有 ANR 风险。
 *
 * 还没接（见 README 待办）：口令弹窗、Room KMP 持久化、封面、进度落盘。
 */
class MainActivity : ComponentActivity() {

    private val repository = InMemoryBookRepository()
    private val shelf = mutableStateOf<List<Book>>(emptyList())
    private val openBook = mutableStateOf<Book?>(null)
    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var importer: JmSandboxImporter
    private var hasRootKeys = false

    /** 阅读进度节流：滚动是逐像素回调的，落库按 500ms 节流（`docs/tech/mobile.md` §6）。 */
    private var lastProgressWriteAt = 0L
    private var pendingProgress: Pair<Int, Float>? = null

    private val pickJm = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::importFrom)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootKeys = loadRootKeys()
        hasRootKeys = rootKeys.isNotEmpty()
        importer = JmSandboxImporter(JmSandbox(filesDir), rootKeys)

        setContent {
            val book = openBook.value
            if (book == null) {
                App(
                    books = shelf.value,
                    onImport = { pickJm.launch(arrayOf("*/*")) },
                    onOpen = { openBook.value = it },
                )
            } else {
                BookReader(
                    book = book,
                    filePath = File(filesDir, book.localPath).absolutePath,
                    onBack = {
                        flushProgress()
                        openBook.value = null
                    },
                    onProgress = ::onReaderProgress,
                )
            }
        }

        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data?.let(::importFrom)
            Intent.ACTION_SEND -> sharedUri(intent)?.let(::importFrom)
        }
    }

    override fun onDestroy() {
        flushProgress()
        worker.shutdown()
        super.onDestroy()
    }

    /** 阅读页回调：每滚一下就记下最新值，但最多每 [PROGRESS_THROTTLE_MS] 落一次。 */
    private fun onReaderProgress(page: Int, scrollRatio: Float) {
        pendingProgress = page to scrollRatio
        val now = SystemClock.elapsedRealtime()
        if (now - lastProgressWriteAt >= PROGRESS_THROTTLE_MS) {
            lastProgressWriteAt = now
            flushProgress()
        }
    }

    /** 退出阅读页 / 退到后台前补写一次，保证"最后翻到哪儿"不丢。 */
    private fun flushProgress() {
        val (page, ratio) = pendingProgress ?: return
        val book = openBook.value ?: return
        pendingProgress = null
        repository.updateProgress(book.docId, page, ratio)
        shelf.value = repository.recent()
    }

    /**
     * 后台单遍导入。错误按 [JmError] 的类型分文案，不做字符串匹配：
     * 格式不对 / 需要口令 / 不完整或损坏，三种提示见 `docs/tech/protocol.md` §7。
     */
    private fun importFrom(uri: Uri) {
        if (!hasRootKeys) {
            toast("缺少根密钥：把 keys.json 放进 androidApp/src/main/assets/")
            return
        }
        worker.execute {
            try {
                val stream = contentResolver.openInputStream(uri)
                    ?: throw IOException("文件已失效，请在 QQ 重新下载")
                val book = stream.use { importer.import(it) }
                val isNew = repository.upsert(book)
                runOnUiThread {
                    shelf.value = repository.recent()
                    toast(if (isNew) "已导入：${book.title}" else "已更新：${book.title}（阅读进度保留）")
                }
            } catch (e: JmError.PasswordRequired) {
                // 口令弹窗属于 M2 后续：现在只把“它需要口令”这件事说清楚。
                toastOnUi("这份文档需要口令，口令输入还没接")
            } catch (e: JmError) {
                toastOnUi(e.message ?: "无法打开这个文件")
            } catch (e: Exception) {
                toastOnUi(e.message ?: "导入失败")
            }
        }
    }

    /**
     * 根密钥从 `assets/keys.json` 读（格式同 SDK，`.gitignore` 已挡 `keys.json`，不会入库）。
     * 生产密钥怎么注入还没定，见 openjm `docs/tech/key-registry.md` 与 `decisions.md` 待定项；
     * 缺失时 App 仍能起来，只是导入前会明确提示，不会用一个空密钥表去猜。
     */
    private fun loadRootKeys(): Map<Int, ByteArray> = runCatching {
        assets.open(KEYS_ASSET).bufferedReader().use { parseRootKeys(it.readText()) }
    }.getOrNull() ?: emptyMap()

    @Suppress("DEPRECATION")
    private fun sharedUri(intent: Intent): Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri

    private fun toast(text: String) = runOnUiThread {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    private fun toastOnUi(text: String) = toast(text)

    private companion object {
        const val KEYS_ASSET = "keys.json"
        const val PROGRESS_THROTTLE_MS = 500L
    }
}
