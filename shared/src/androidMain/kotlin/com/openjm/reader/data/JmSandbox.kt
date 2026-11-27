package com.openjm.reader.data

import com.openjm.reader.crypto.InputStreamSource
import com.openjm.reader.crypto.JmError
import com.openjm.reader.crypto.JmImporter
import com.openjm.reader.crypto.OutputStreamSink
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * 私有沙盒布局，对应 `docs/tech/mobile.md` §3：
 *
 * ```text
 * root/
 * ├── tmp/       uuid.tmp：校验没通过之前，明文只允许出现在这里
 * ├── books/     {doc_id}.{ext}：校验通过、原子改名之后的正文
 * └── covers/    封面缩略图（M2-B 才写）
 * ```
 *
 * `root` 传 `filesDir`：沙盒在应用私有目录内，文件管理器和相册都看不到，
 * 这是“不留 `.jm`、不导出明文”的实现基础（FR-12 / D7）。
 */
class JmSandbox(val root: File) {

    val tmpDir: File = File(root, "tmp")
    val booksDir: File = File(root, "books")
    val coversDir: File = File(root, "covers")

    fun ensureLayout() {
        for (dir in listOf(tmpDir, booksDir, coversDir)) {
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
                throw JmError.Format("无法创建沙盒目录：${dir.path}")
            }
        }
    }

    fun bookFile(docId: String, ext: String): File = File(booksDir, "$docId.$ext")

    /** 书架里存的相对路径，避免把绝对路径写进表里（换机/重装后失效）。 */
    fun relativeBookPath(docId: String, ext: String): String = "books/$docId.$ext"

    fun coverFile(docId: String): File = File(coversDir, "$docId.webp")

    fun relativeCoverPath(docId: String): String = "covers/$docId.webp"

    /** 从书架删书时连正文与封面一起删——不然会留下永远打不开的孤儿文件。 */
    fun deleteBook(docId: String, ext: String) {
        bookFile(docId, ext).delete()
        coverFile(docId).delete()
    }
}

/**
 * 单遍导入到沙盒：读流 → 校验 → 解密写 `.tmp` → 原子改名为 `books/{doc_id}.{ext}`。
 *
 * 两类职责刻意分开：
 * - 正确性靠 [JmImporter]（协议、HMAC、长度恒等式），本类不重复实现任何字节逻辑；
 * - 落盘顺序靠本类：**只有 import 正常返回才改名**，抛错一律删 `.tmp`，
 *   所以中断或损坏都不会留下“能打开的半个文档”（FR-11 / FR-12）。
 *
 * 本类不做线程调度，调用方负责放到后台线程；`rootKeys` 由上位提供（见 [com.openjm.reader.crypto.parseRootKeys]）。
 */
class JmSandboxImporter(
    private val sandbox: JmSandbox,
    private val rootKeys: Map<Int, ByteArray>,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * @param onBytesRead 每读一段就回调已读字节数，给 UI 画进度；传 null 就完全不计数。
     */
    fun import(
        input: InputStream,
        password: String? = null,
        onBytesRead: ((Long) -> Unit)? = null,
    ): Book {
        sandbox.ensureLayout()
        val tmp = File(sandbox.tmpDir, "${UUID.randomUUID()}.tmp")
        val stream = if (onBytesRead != null) ProgressInputStream(input, onBytesRead) else input
        val source = InputStreamSource(stream)
        try {
            val (header, headerBytes) = JmImporter.readHeader(source)
            val result = FileOutputStream(tmp).use { out ->
                JmImporter.import(
                    source = source,
                    header = header,
                    headerBytes = headerBytes,
                    rootKeys = rootKeys,
                    password = password,
                    sink = OutputStreamSink(out),
                )
            }

            val meta = result.meta
            promote(tmp, sandbox.bookFile(meta.docId, meta.ext))
            val importedAt = now()
            return Book(
                docId = meta.docId,
                title = meta.name,
                ext = meta.ext,
                localPath = sandbox.relativeBookPath(meta.docId, meta.ext),
                fileSize = meta.size,
                importedAt = importedAt,
                lastReadAt = importedAt,
            )
        } finally {
            source.close()
            if (tmp.exists()) tmp.delete()
        }
    }

    /**
     * 转正。先删旧文件再原子移动：同一 `doc_id` 重复导入时明文完全一致，
     * 删掉再移动不会让用户看到半个文件，也不会因 Windows/部分 ROM 的“目标已存在”而失败。
     */
    private fun promote(tmp: File, target: File) {
        if (target.exists() && !target.delete()) {
            throw IOException("无法覆盖旧文件：${target.name}")
        }
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
