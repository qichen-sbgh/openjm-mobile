package com.openjm.reader

import com.openjm.reader.crypto.JmError
import com.openjm.reader.data.Book
import com.openjm.reader.data.JmSandbox
import com.openjm.reader.data.JmSandboxImporter
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 导入落盘链路：流 → 校验 → `tmp/{uuid}.tmp` → `books/{doc_id}.ext`（FR-11 / FR-12）。
 *
 * 断言的是**文件系统上发生了什么**，不只是返回值：因为“中断不留半个文档”
 * 这条需求只有看目录才能证明。真机沙盒仍需按验收清单 §3.1 手工确认。
 */
class JmSandboxImportTest {

    @Test
    fun validVectorLandsInBooksAndLeavesNoTmp() = withSandbox { sandbox, importer, manifest, _ ->
        val case = case(manifest, "v01-plain-no-pad")
        val expect = case["expect"]!!.jsonObject

        val book = importVector(importer, case)

        assertEquals(expect.string("doc_id"), book.docId)
        assertEquals(expect.string("name"), book.title)
        assertEquals(expect.long("size"), book.fileSize)
        assertEquals("books/${book.docId}.pdf", book.localPath)

        val stored = File(sandbox.root, book.localPath)
        assertTrue(stored.isFile, "明文应落在 books/ 下：${stored.path}")
        assertEquals(expect.long("size"), stored.length(), "落盘大小应等于 Meta 里的 size（填充已剥离）")
        assertEquals(expect.string("sha256"), sha256(stored), "落盘内容应与冻结向量声明的明文一致")

        assertEquals(
            listOf("${book.docId}.pdf"),
            sandbox.booksDir.list()?.toList(),
            "books/ 下只应有这一份明文",
        )
        assertEquals(0, sandbox.tmpDir.list()?.size ?: 0, "校验通过后不应残留 .tmp")
    }

    @Test
    fun truncatedVectorLeavesNothingBehind() = withSandbox { sandbox, importer, manifest, _ ->
        val thrown = assertFailsWith<JmError.Auth> {
            importVector(importer, case(manifest, "v90-truncated"))
        }

        assertTrue(thrown.message!!.isNotEmpty(), "错误要带可展示的文案")
        assertEquals(0, sandbox.booksDir.list()?.size ?: 0, "校验失败不能有任何明文进 books/")
        assertEquals(0, sandbox.tmpDir.list()?.size ?: 0, "失败必须删掉 .tmp")
    }

    @Test
    fun reimportOfSameDocKeepsSingleFile() = withSandbox { sandbox, importer, manifest, _ ->
        val first = importVector(importer, case(manifest, "v01-plain-no-pad"))
        val second = importVector(importer, case(manifest, "v02-same-source-new-nonce"))

        assertEquals(first.docId, second.docId, "同一份 PDF 两次封装 doc_id 相同")
        assertEquals(
            listOf("${first.docId}.pdf"),
            sandbox.booksDir.list()?.toList(),
            "重复导入应覆盖同名文件，不产生第二份明文",
        )
        assertEquals(0, sandbox.tmpDir.list()?.size ?: 0)
    }

    @Test
    fun passwordVectorWithoutPasswordIsRejected() = withSandbox { sandbox, importer, manifest, _ ->
        assertFailsWith<JmError.PasswordRequired> {
            importVector(importer, case(manifest, "v03-password"))
        }

        assertEquals(0, sandbox.booksDir.list()?.size ?: 0)
        assertEquals(0, sandbox.tmpDir.list()?.size ?: 0)
    }

    @Test
    fun passwordVectorWithCorrectPasswordImports() = withSandbox { sandbox, importer, manifest, _ ->
        val case = case(manifest, "v03-password")
        val expect = case["expect"]!!.jsonObject

        val book = importVector(importer, case, password = case.string("password"))

        val stored = File(sandbox.root, book.localPath)
        assertEquals(expect.string("sha256"), sha256(stored))
        assertEquals(0, sandbox.tmpDir.list()?.size ?: 0)
    }

    @Test
    fun wrongPasswordIsAuthErrorAndLeavesNothing() = withSandbox { sandbox, importer, manifest, _ ->
        val case = case(manifest, "v03-password")

        assertFailsWith<JmError.Auth> {
            importVector(importer, case, password = case.string("wrong_password"))
        }

        assertEquals(0, sandbox.booksDir.list()?.size ?: 0)
        assertEquals(0, sandbox.tmpDir.list()?.size ?: 0)
    }

    // ------------------------------------------------------------------ helpers

    private fun withSandbox(
        block: (JmSandbox, JmSandboxImporter, JsonObject, Map<Int, ByteArray>) -> Unit,
    ) {
        val manifest = Vectors.manifest() ?: return
        val keys = Vectors.rootKeys(manifest)
        val root = Files.createTempDirectory("openjm-sandbox").toFile()
        try {
            val sandbox = JmSandbox(root)
            val importer = JmSandboxImporter(sandbox, keys) { FIXED_NOW }
            block(sandbox, importer, manifest, keys)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun case(manifest: JsonObject, id: String): JsonObject =
        manifest.vectors().single { it.string("id") == id }

    private fun importVector(importer: JmSandboxImporter, case: JsonObject, password: String? = null): Book =
        Vectors.file(case).inputStream().use { importer.import(it, password) }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private companion object {
        const val FIXED_NOW = 1_700_000_000_000L
    }
}
