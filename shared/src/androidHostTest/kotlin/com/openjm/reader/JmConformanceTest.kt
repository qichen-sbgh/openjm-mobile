package com.openjm.reader

import com.openjm.reader.crypto.InputStreamSource
import com.openjm.reader.crypto.JmError
import com.openjm.reader.crypto.JmImporter
import com.openjm.reader.crypto.JmSink
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 回放 openjm 仓库的冻结向量，证明 Android 侧（javax.crypto）与 Python 逐字节一致。
 *
 * 向量位置用 `OPENJM_TESTVECTORS` 指定；未设置时依次找相邻检出目录，
 * 都找不到就跳过——CI 里没有另一个仓库的检出，不该因此变红。
 */
class JmConformanceTest {

    private val vectorsDir: File? = locateVectors()

    @Test
    fun validVectorsProduceExpectedPlaintext() {
        val dir = vectorsDir ?: return skip()
        val manifest = Json.parseToJsonElement(File(dir, "manifest.json").readText()).jsonObject
        val keys = rootKeys(File(dir, manifest.string("keys_file")))

        var checked = 0
        manifest.vectors().filter { it.string("kind") == "valid" }.forEach { case ->
            val plain = ByteArraySink()
            jmFile(dir, case).inputStream().use { stream ->
                val source = InputStreamSource(stream)
                val (header, raw) = JmImporter.readHeader(source)
                val password = case["password"]?.jsonPrimitive?.content
                val result = JmImporter.import(source, header, raw, keys, password, plain)

                val expect = case["expect"]!!.jsonObject
                assertEquals(expect.string("doc_id"), result.meta.docId, "${case.string("id")} doc_id")
                assertEquals(expect.string("name"), result.meta.name, "${case.string("id")} name")
                assertEquals(expect.long("size"), result.meta.size, "${case.string("id")} size")
                assertEquals(expect.long("pad").toInt(), result.meta.pad, "${case.string("id")} pad")
                assertEquals(expect.string("sha256"), sha256(plain.bytes), "${case.string("id")} 明文 sha256")
            }
            checked++
        }
        assertTrue(checked >= 5, "至少应回放 5 个有效向量，实际 $checked")
    }

    @Test
    fun invalidVectorsRaiseExpectedError() {
        val dir = vectorsDir ?: return skip()
        val manifest = Json.parseToJsonElement(File(dir, "manifest.json").readText()).jsonObject
        val keys = rootKeys(File(dir, manifest.string("keys_file")))

        var checked = 0
        manifest.vectors().filter { it.string("kind") == "invalid" }.forEach { case ->
            val thrown = runCatching { import(jmFile(dir, case), keys, null) }.exceptionOrNull()
            val expectedName = case.string("error")
            val actualName = when (thrown) {
                is JmError.Format -> "JMFormatError"
                is JmError.Auth -> "JMAuthError"
                is JmError.PasswordRequired -> "JMPasswordRequiredError"
                else -> thrown?.let { it::class.simpleName } ?: "无异常"
            }
            assertEquals(expectedName, actualName, "${case.string("id")} 的错误类型")
            checked++
        }
        assertTrue(checked >= 6, "至少应回放 6 个反例向量，实际 $checked")
    }

    @Test
    fun wrongPasswordRaisesAuthError() {
        val dir = vectorsDir ?: return skip()
        val manifest = Json.parseToJsonElement(File(dir, "manifest.json").readText()).jsonObject
        val keys = rootKeys(File(dir, manifest.string("keys_file")))

        val cases = manifest.vectors().filter { it["wrong_password"] != null }
        assertTrue(cases.isNotEmpty(), "manifest 里应至少有一个 wrong_password 用例")

        cases.forEach { case ->
            val wrong = case["wrong_password"]!!.jsonPrimitive.content
            val thrown = runCatching { import(jmFile(dir, case), keys, wrong) }.exceptionOrNull()
            assertTrue(thrown is JmError.Auth, "${case.string("id")} 错误口令应抛 JMAuthError，实际 $thrown")
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun jmFile(dir: File, case: JsonObject) = File(dir, case.string("file"))

    private fun import(file: File, keys: Map<Int, ByteArray>, password: String?) {
        file.inputStream().use { stream ->
            val source = InputStreamSource(stream)
            val (header, raw) = JmImporter.readHeader(source)
            JmImporter.import(source, header, raw, keys, password, ByteArraySink())
        }
    }

    private fun rootKeys(keyFile: File): Map<Int, ByteArray> =
        Json.parseToJsonElement(keyFile.readText()).jsonObject
            .mapKeys { it.key.toInt() }
            .mapValues { (_, value) -> hex(value.jsonPrimitive.content) }

    private fun JsonObject.vectors(): List<JsonObject> =
        this["vectors"]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.string(key: String) = this[key]!!.jsonPrimitive.content

    private fun JsonObject.long(key: String) = this[key]!!.jsonPrimitive.content.toLong()

    private fun hex(value: String): ByteArray =
        ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun locateVectors(): File? {
        val candidates = listOfNotNull(
            System.getenv("OPENJM_TESTVECTORS")?.let(::File),
            File("../../openjm/testvectors"),
            File("../openjm/testvectors"),
            File("testvectors"),
        )
        return candidates.firstOrNull { File(it, "manifest.json").isFile }
    }

    private fun skip() {
        println("SKIP: 未找到 testvectors，设置 OPENJM_TESTVECTORS 后重跑")
    }

    private class ByteArraySink : JmSink {
        private val out = ByteArrayOutputStream()
        val bytes: ByteArray get() = out.toByteArray()

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            out.write(buffer, offset, length)
        }
    }
}
