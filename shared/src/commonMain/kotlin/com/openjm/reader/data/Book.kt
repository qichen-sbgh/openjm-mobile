package com.openjm.reader.data

/**
 * 书架条目。字段与 `docs/tech/protocol.md` 的 Meta 对齐，
 * 主键用加密容器里的 `doc_id`——同一个文件从不同群重复发来也能命中同一条。
 */
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
 * 书架仓库。首版只有内存实现：Room KMP 与 KSP 的接入留到下一步，
 * 先把协议链路和 UI 骨架跑通，避免构建配置和加密核心同时变动。
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
}

class InMemoryBookRepository(initial: List<Book> = emptyList()) : BookRepository {
    private val books = LinkedHashMap<String, Book>().apply {
        initial.forEach { put(it.docId, it) }
    }

    override fun upsert(book: Book): Boolean {
        val existing = books[book.docId]
        val merged = if (existing == null) {
            book
        } else {
            existing.copy(
                title = existing.title,
                localPath = book.localPath,
                fileSize = book.fileSize,
                lastReadAt = book.lastReadAt,
            )
        }
        books[book.docId] = merged
        return existing == null
    }

    override fun recent(): List<Book> = books.values.sortedByDescending { it.lastReadAt }

    override fun updateProgress(docId: String, lastPage: Int, scrollRatio: Float, totalPages: Int) {
        val existing = books[docId] ?: return
        books[docId] = existing.copy(
            lastPage = lastPage.coerceAtLeast(0),
            scrollRatio = scrollRatio.coerceIn(0f, 1f),
            totalPages = if (totalPages > 0) totalPages else existing.totalPages,
            lastReadAt = System.currentTimeMillis(),
        )
    }

    override fun delete(docId: String) {
        books.remove(docId)
    }
}
