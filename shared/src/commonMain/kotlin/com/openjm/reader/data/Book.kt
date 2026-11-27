package com.openjm.reader.data

import kotlinx.serialization.Serializable

/**
 * 书架条目。字段与 `docs/tech/protocol.md` 的 Meta 对齐，
 * 主键用加密容器里的 `doc_id`——同一个文件从不同群重复发来也能命中同一条。
 *
 * `@Serializable` 是为了 JSON 落盘（见 `tech/mobile.md` §5）；字段只增不改名，
 * 旧版本 App 读到新字段会忽略（`ignoreUnknownKeys`），新版本读到旧文件缺字段走默认值。
 */
@Serializable
data class Book(
    val docId: String,
    val title: String,
    val ext: String,
    val localPath: String,
    val coverPath: String? = null,
    val fileSize: Long,
    val lastPage: Int = 0,
    val totalPages: Int = 0,
    val scrollRatio: Float = 0f,
    val importedAt: Long,
    val lastReadAt: Long,
)

/**
 * 书架仓库。实现有两份：内存版（测试用）与 JSON 落盘版（Android 用）。
 * 换 Room / SQLDelight 时只需要再写一个实现，调用方不动。
 */
interface BookRepository {
    /** 返回 true 表示新增；false 表示已存在（保留原阅读进度，只刷新 lastReadAt）。 */
    fun upsert(book: Book): Boolean

    /**
     * 写入阅读进度。`totalPages` 传 0 表示"未知/不更新"，避免阅读器还没拿到页数时把已有值冲掉。
     * 条目不存在时静默忽略：进度永远不该凭空造出一条书架记录。
     */
    fun updateProgress(docId: String, lastPage: Int, scrollRatio: Float, totalPages: Int = 0)

    fun recent(): List<Book>

    fun delete(docId: String)

    /** 改书架上的显示名。只动 `title`，正文路径与阅读进度都不受影响。 */
    fun rename(docId: String, title: String)
}

/**
 * 重复导入的合并规则：**用户可见的元信息与阅读进度以已有条目为准**，
 * 只刷新与新文件相关的字段。这条规则两个实现共用，避免各写一遍慢慢跑偏。
 */
internal fun Book.mergedWith(incoming: Book): Book = copy(
    title = title,
    localPath = incoming.localPath,
    fileSize = incoming.fileSize,
    lastReadAt = incoming.lastReadAt,
)

internal fun Book.withProgress(lastPage: Int, scrollRatio: Float, totalPages: Int): Book = copy(
    lastPage = lastPage.coerceAtLeast(0),
    scrollRatio = scrollRatio.coerceIn(0f, 1f),
    totalPages = if (totalPages > 0) totalPages else this.totalPages,
    lastReadAt = System.currentTimeMillis(),
)

class InMemoryBookRepository(initial: List<Book> = emptyList()) : BookRepository {
    private val books = LinkedHashMap<String, Book>().apply {
        initial.forEach { put(it.docId, it) }
    }

    override fun upsert(book: Book): Boolean {
        val existing = books[book.docId]
        books[book.docId] = existing?.mergedWith(book) ?: book
        return existing == null
    }

    override fun recent(): List<Book> = books.values.sortedByDescending { it.lastReadAt }

    override fun updateProgress(docId: String, lastPage: Int, scrollRatio: Float, totalPages: Int) {
        val existing = books[docId] ?: return
        books[docId] = existing.withProgress(lastPage, scrollRatio, totalPages)
    }

    override fun delete(docId: String) {
        books.remove(docId)
    }

    override fun rename(docId: String, title: String) {
        val existing = books[docId] ?: return
        val name = title.trim().ifEmpty { return }
        books[docId] = existing.copy(title = name)
    }
}
