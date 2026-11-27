package com.openjm.reader.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 书架仓库的 upsert 语义（FR-13 的“同一文件重复发来不丢进度”）。
 *
 * 这些是纯 common 逻辑，跑在 commonTest 里，Android 与将来到 iOS 都用同一份断言。
 */
class BookRepositoryTest {

    private fun book(
        docId: String,
        title: String = "doc-$docId.pdf",
        localPath: String = "books/$docId.pdf",
        fileSize: Long = 1024,
        lastPage: Int = 0,
        totalPages: Int = 0,
        scrollRatio: Float = 0f,
        importedAt: Long = 1_000,
        lastReadAt: Long = 1_000,
    ) = Book(
        docId = docId,
        title = title,
        ext = "pdf",
        localPath = localPath,
        fileSize = fileSize,
        lastPage = lastPage,
        totalPages = totalPages,
        scrollRatio = scrollRatio,
        importedAt = importedAt,
        lastReadAt = lastReadAt,
    )

    @Test
    fun upsertReturnsTrueOnlyForNewDocId() {
        val repo = InMemoryBookRepository()

        assertTrue(repo.upsert(book("a")))
        assertFalse(repo.upsert(book("a")), "同一 doc_id 第二次导入不算新增")
        assertEquals(1, repo.recent().size)
    }

    @Test
    fun reimportKeepsReadingProgressButRefreshesFileFacts() {
        val repo = InMemoryBookRepository(
            listOf(
                book(
                    docId = "a",
                    title = "深入理解计算机系统.pdf",
                    localPath = "books/a.pdf",
                    fileSize = 100,
                    lastPage = 10,
                    totalPages = 20,
                    scrollRatio = 0.5f,
                    importedAt = 1_000,
                    lastReadAt = 2_000,
                ),
            ),
        )

        val changed = repo.upsert(
            book(
                docId = "a",
                title = "doc_a.pdf",
                localPath = "books/a.pdf",
                fileSize = 200,
                importedAt = 5_000,
                lastReadAt = 5_000,
            ),
        )

        assertFalse(changed, "重复导入应命中已有条目")
        val stored = repo.recent().single()
        assertEquals(10, stored.lastPage, "页码不能被重新导入清掉")
        assertEquals(20, stored.totalPages, "总页数不能丢")
        assertEquals(0.5f, stored.scrollRatio, "滚动比例不能丢")
        assertEquals("深入理解计算机系统.pdf", stored.title, "用户可见标题以已有条目为准")
        assertEquals(200, stored.fileSize.toInt(), "文件大小以新文件为准")
        assertEquals(5_000, stored.lastReadAt, "最近阅读时间刷新")
        assertEquals(1_000, stored.importedAt, "首次导入时间保持不变")
    }

    @Test
    fun recentIsSortedByLastReadAtDescending() {
        val repo = InMemoryBookRepository()
        repo.upsert(book("old", lastReadAt = 100))
        repo.upsert(book("new", lastReadAt = 900))
        repo.upsert(book("mid", lastReadAt = 500))

        assertEquals(listOf("new", "mid", "old"), repo.recent().map { it.docId })
    }

    @Test
    fun deleteRemovesOnlyTheGivenDoc() {
        val repo = InMemoryBookRepository()
        repo.upsert(book("a"))
        repo.upsert(book("b"))

        repo.delete("a")

        assertEquals(listOf("b"), repo.recent().map { it.docId })
        assertTrue(repo.upsert(book("a")), "删掉之后同一个 doc_id 应算新增")
    }
}
