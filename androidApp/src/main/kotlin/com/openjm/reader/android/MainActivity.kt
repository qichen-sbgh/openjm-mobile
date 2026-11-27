package com.openjm.reader.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.openjm.reader.crypto.JmError
import com.openjm.reader.crypto.parseRootKeys
import com.openjm.reader.data.Book
import com.openjm.reader.data.BookRepository
import com.openjm.reader.data.CoverStore
import com.openjm.reader.data.JmSandbox
import com.openjm.reader.data.JmSandboxImporter
import com.openjm.reader.data.JsonBookRepository
import com.openjm.reader.ui.App
import com.openjm.reader.ui.BookReader
import com.openjm.reader.ui.ImportProgressBar
import com.openjm.reader.ui.PasswordPrompt
import com.openjm.reader.ui.PdfPageRenderer
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

    private lateinit var repository: BookRepository
    private val shelf = mutableStateOf<List<Book>>(emptyList())
    private val openBook = mutableStateOf<Book?>(null)

    /** 正在等口令的文件；非空时弹输入框（FR-04）。 */
    private val passwordPromptUri = mutableStateOf<Uri?>(null)
    private val passwordError = mutableStateOf<String?>(null)

    /** 导入进度：null = 没在导入；< 0 = 总大小未知（不确定进度条）；否则 0..1。 */
    private val importProgress = mutableStateOf<Float?>(null)
    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var importer: JmSandboxImporter
    private lateinit var sandbox: JmSandbox
    private lateinit var coverStore: CoverStore
    private var hasRootKeys = false

    /** 阅读进度节流：滚动是逐像素回调的，落库按 500ms 节流（`docs/tech/mobile.md` §6）。 */
    private var lastProgressWriteAt = 0L
    private var pendingProgress: Pair<Int, Float>? = null

    /** 阅读页报上来的总页数；写入进度时一并回填，书架才能显示「第 x/y 页」。 */
    private var readerTotalPages = 0

    private val pickJm = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::importFrom)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootKeys = loadRootKeys()
        hasRootKeys = rootKeys.isNotEmpty()
        sandbox = JmSandbox(filesDir)
        importer = JmSandboxImporter(sandbox, rootKeys)
        coverStore = CoverStore(sandbox)
        // 书架落盘在私有目录的 books/index.json（已被沙盒目录结构覆盖，无需额外权限）
        repository = JsonBookRepository(File(filesDir, "books/index.json"))
        shelf.value = repository.recent()

        setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                val book = openBook.value
                if (book == null) {
                    App(
                        books = shelf.value,
                        coverBaseDir = filesDir.absolutePath,
                        onImport = { pickJm.launch(arrayOf("*/*")) },
                        onOpen = { opened ->
                            readerTotalPages = opened.totalPages
                            openBook.value = opened
                        },
                        onRename = { renamed, title ->
                            repository.rename(renamed.docId, title)
                            shelf.value = repository.recent()
                        },
                        onDelete = { removed ->
                            // 正文与封面一起删；文件 IO 放后台，别堵主线程
                            worker.execute {
                                sandbox.deleteBook(removed.docId, removed.ext)
                                repository.delete(removed.docId)
                                runOnUiThread {
                                    shelf.value = repository.recent()
                                    toast("已删除：${removed.title}")
                                }
                            }
                        },
                    )
                } else {
                    BookReader(
                        book = book,
                        filePath = File(filesDir, book.localPath).absolutePath,
                        onBack = {
                            flushProgress()
                            openBook.value = null
                        },
                        onDocumentLoaded = { pages -> readerTotalPages = pages },
                        onProgress = ::onReaderProgress,
                    )
                }

                ImportProgressBar(
                    progress = importProgress.value,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }

            val promptUri = passwordPromptUri.value
            if (promptUri != null) {
                PasswordPrompt(
                    onDismiss = {
                        passwordPromptUri.value = null
                        passwordError.value = null
                    },
                    onSubmit = { entered -> importFrom(promptUri, entered) },
                    errorMessage = passwordError.value,
                )
            }
        }

        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data?.let(::importFrom)
            Intent.ACTION_SEND -> sharedUri(intent)?.let(::importFrom)
        }
    }

    /**
     * 已经在前台时再收到 QQ 的 VIEW / SEND：`singleTop` 会把请求送到这里，
     * 不走 onCreate。缺了这一段，用户"点第二份文件没反应"，还以为是文件坏了。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        when (intent.action) {
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
        repository.updateProgress(book.docId, page, ratio, readerTotalPages)
        shelf.value = repository.recent()
    }

    /**
     * 后台单遍导入。错误按 [JmError] 的类型分文案，不做字符串匹配：
     * 格式不对 / 需要口令 / 不完整或损坏，三种提示见 `docs/tech/protocol.md` §7。
     */
    private fun importFrom(uri: Uri, password: String? = null) {
        if (!hasRootKeys) {
            toast("缺少根密钥：把 keys.json 放进 androidApp/src/main/assets/")
            return
        }
        worker.execute {
            importProgress.value = -1f
            var lastPercent = -1
            try {
                val stream = contentResolver.openInputStream(uri)
                    ?: throw IOException("文件已失效，请在 QQ 重新下载")
                val totalBytes = queryTotalBytes(uri)
                val imported = stream.use {
                    importer.import(it, password) { read ->
                        // 只在百分比变化时写 state，避免每 64KB 就触发一次重组
                        if (totalBytes != null && totalBytes > 0) {
                            val percent = (read * 100 / totalBytes).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                importProgress.value = percent / 100f
                            }
                        }
                    }
                }
                // 封面失败不影响导入：书先进书架，缩略图退回占位块。
                val coverPath = buildCover(imported)
                val book = if (coverPath != null) imported.copy(coverPath = coverPath) else imported
                val isNew = repository.upsert(book)
                runOnUiThread {
                    passwordPromptUri.value = null
                    passwordError.value = null
                    shelf.value = repository.recent()
                    toast(if (isNew) "已导入：${book.title}" else "已更新：${book.title}（阅读进度保留）")
                }
            } catch (e: JmError.PasswordRequired) {
                // 口令文件：不报错，弹输入框重试（FR-04）。
                runOnUiThread {
                    passwordError.value = null
                    passwordPromptUri.value = uri
                }
            } catch (e: JmError.Auth) {
                // 口令错与文件损坏在协议层不可区分（D2），文案不猜是哪一种。
                if (password != null) {
                    runOnUiThread { passwordError.value = "口令不对，或文件已损坏" }
                } else {
                    toastOnUi(e.message ?: "文件不完整或已损坏，请在 QQ 重新下载")
                }
            } catch (e: JmError) {
                toastOnUi(e.message ?: "无法打开这个文件")
            } catch (e: Exception) {
                toastOnUi(e.message ?: "导入失败")
            } finally {
                importProgress.value = null
            }
        }
    }

    /** 拿 URI 对应文件的声明大小；`content://` 不给 SIZE、`file://` 走文件长度，都没有就返回 null。 */
    private fun queryTotalBytes(uri: Uri): Long? {
        val fromProvider = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            }
        }.getOrNull()
        if (fromProvider != null && fromProvider > 0) return fromProvider

        return runCatching {
            if (uri.scheme == "file") File(uri.path!!).length().takeIf { it > 0 } else null
        }.getOrNull()
    }

    /**
     * 生成封面：PDFium 渲染首帧 → WebP 落 `covers/{doc_id}.webp`。
     * 失败返回 null（书架用占位块），绝不因为封面失败把整次导入判负。
     */
    private fun buildCover(book: Book): String? {
        val renderer = PdfPageRenderer(File(filesDir, book.localPath))
        return try {
            if (renderer.open(this) <= 0) return null
            val widthPx = (COVER_WIDTH_DP * resources.displayMetrics.density).toInt().coerceIn(120, 720)
            val bitmap = renderer.render(0, widthPx) ?: return null
            coverStore.save(book.docId, bitmap)
        } catch (e: Exception) {
            null
        } finally {
            renderer.close()
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

        /** 封面渲染宽度（dp）：书架缩略图 48dp 宽，取 160dp 保证高密度屏也清晰。 */
        const val COVER_WIDTH_DP = 160f
    }
}
