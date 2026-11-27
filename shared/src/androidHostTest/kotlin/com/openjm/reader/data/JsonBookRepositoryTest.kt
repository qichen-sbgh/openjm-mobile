package com.openjm.reader.data

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * JSON 书架落盘（FR-15 的持久化部分）。
 *
 * 断言的核心不是"写进去了"，而是**换一个实例读回来还在**——
 * 进程重启后正是这个路径，内存实现永远不会暴露的问题都在这里。
 */
class JsonBookRepositoryTest {

    private val root: File = Files.createTempDirectory("openjm-books").toFile()
    private val storeFile = File(root, "books/index.json")

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun book(docId: String, lastReadAt: Long = 1_000) = Book(
        docId = docId,
        title = "doc-$docId.pdf",
        ext = "pdf",
        localPath = "books/$docId.pdf",
        coverPath = "covers/$docId.webp",
        fileSize = 1024,
        importedAt = 900,
        lastReadAt = lastReadAt,
    )

    @Test
    fun booksSurviveNewInstance() {
        val first = JsonBookRepository(storeFile)
        assertTrue(first.upsert(book("a", lastReadAt = 100)))
        assertTrue(first.upsert(book("b", lastReadAt = 200)))

        val reopened = JsonBookRepository(storeFile)

        assertEquals(listOf("b", "a"), reopened.recent().map { it.docId }, "按最近阅读倒序，且两条都在")
        val restored = reopened.recent().first { it.docId == "a" }
        assertEquals("doc-a.pdf", restored.title)
        assertEquals("books/a.pdf", restored.localPath)
        assertEquals("covers/a.webp", restored.coverPath)
        assertEquals(1024, restored.fileSize.toInt())
        assertEquals(900, restored.importedAt)
    }

    @Test
    fun progressSurvivesNewInstance() {
        JsonBookRepository(storeFile).apply {
            upsert(book("a"))
            updateProgress("a", lastPage = 12, scrollRatio = 0.35f, totalPages = 40)
        }

        val reopened = JsonBookRepository(storeFile)
        val restored = reopened.recent().single()

        assertEquals(12, restored.lastPage)
        assertEquals(0.35f, restored.scrollRatio)
        assertEquals(40, restored.totalPages)
    }

    @Test
    fun reimportOfSameDocKeepsProgressOnDisk() {
        val repo = JsonBookRepository(storeFile)
        repo.upsert(book("a"))
        repo.updateProgress("a", lastPage = 7, scrollRatio = 0.5f, totalPages = 20)

        val changed = repo.upsert(book("a", lastReadAt = 5_000).copy(title = "doc_a.pdf", fileSize = 2048))

        assertFalse(changed, "同一 doc_id 第二次导入不算新增")
        val reopened = JsonBookRepository(storeFile).recent().single()
        assertEquals(7, reopened.lastPage, "重复导入不能把页码冲掉")
        assertEquals(20, reopened.totalPages)
        assertEquals("doc-a.pdf", reopened.title, "标题以已有条目为准")
        assertEquals(2048, reopened.fileSize.toInt(), "文件大小以新文件为准")
        assertEquals(5_000, reopened.lastReadAt)
    }

    @Test
    fun deletePersists() {
        JsonBookRepository(storeFile).apply {
            upsert(book("a"))
            upsert(book("b"))
            delete("a")
        }

        assertEquals(listOf("b"), JsonBookRepository(storeFile).recent().map { it.docId })
        assertFalse(storeFile.readText().contains("\"a\""), "被删条目不应还留在 json 里")
    }

    @Test
    fun brokenFileDegradesToEmptyShelfInsteadOfCrashing() {
        storeFile.parentFile?.mkdirs()
        storeFile.writeText("{ 这不是合法 json")

        val repo = JsonBookRepository(storeFile)

        assertEquals(emptyList(), repo.recent(), "坏文件不能让 App 起不来")
        assertTrue(repo.upsert(book("a")), "坏文件之后仍可正常写入")
        assertEquals(listOf("a"), JsonBookRepository(storeFile).recent().map { it.docId })
    }
}
