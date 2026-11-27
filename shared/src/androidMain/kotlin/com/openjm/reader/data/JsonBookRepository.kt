package com.openjm.reader.data

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.Json

/**
 * 书架落盘：一整个书架序列化成 `books/index.json`。
 *
 * 为什么先不用 Room（决策见 `openjm/docs/tech/mobile.md` §5「持久化选型」）：
 * 数据量是"一个人几十本书"，字段十来个，Room KMP 要带 KSP 与 schema 迁移，
 * 成本远大于收益；而 `BookRepository` 接口已经隔好了，将来换实现只动这一个类。
 *
 * 写入用 `tmp` + 原子改名：书架表宁可回到上一版，也不能被写成半截 JSON。
 */
class JsonBookRepository(private val storeFile: File) : BookRepository {

    private val books = LinkedHashMap<String, Book>()

    init {
        load()
    }

    override fun upsert(book: Book): Boolean {
        val existing = books[book.docId]
        books[book.docId] = existing?.mergedWith(book) ?: book
        persist()
        return existing == null
    }

    override fun updateProgress(docId: String, lastPage: Int, scrollRatio: Float, totalPages: Int) {
        val existing = books[docId] ?: return
        books[docId] = existing.withProgress(lastPage, scrollRatio, totalPages)
        persist()
    }

    override fun recent(): List<Book> = books.values.sortedByDescending { it.lastReadAt }

    override fun delete(docId: String) {
        if (books.remove(docId) != null) persist()
    }

    private fun load() {
        if (!storeFile.isFile) return
        val text = runCatching { storeFile.readText() }.getOrNull() ?: return
        val decoded = runCatching { JSON.decodeFromString<List<Book>>(text) }.getOrNull() ?: return
        decoded.forEach { books[it.docId] = it }
    }

    private fun persist() {
        val dir = storeFile.parentFile
        if (dir != null && !dir.isDirectory && !dir.mkdirs()) return
        val tmp = File(dir, "${storeFile.name}.tmp")
        val payload = JSON.encodeToString(books.values.toList())
        try {
            tmp.writeText(payload)
            try {
                Files.move(tmp.toPath(), storeFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), storeFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            // 落盘失败不该让导入/阅读失败：内存里的书架仍然可用，下次写入会再试。
            tmp.delete()
        }
    }

    private companion object {
        val JSON = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
        }
    }
}
