package com.openjm.reader

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `openjm` 仓冻结向量在测试侧的入口。
 *
 * 向量不在本仓：按 `proto-v1` tag 冻结在总仓 `testvectors/`，由 `OPENJM_TESTVECTORS` 指过来。
 * 找不到时 [dir] 为 null，用例自己 skip —— CI 里没有另一个仓的检出，不该因此变红。
 */
object Vectors {

    val dir: File? by lazy {
        listOfNotNull(
            System.getenv("OPENJM_TESTVECTORS")?.let(::File),
            File("../../openjm/testvectors"),
            File("../openjm/testvectors"),
            File("testvectors"),
        ).firstOrNull { File(it, "manifest.json").isFile }
    }

    fun manifest(): JsonObject? = dir?.let {
        Json.parseToJsonElement(File(it, "manifest.json").readText()).jsonObject
    }

    fun rootKeys(manifest: JsonObject): Map<Int, ByteArray> {
        val vectorsDir = checkNotNull(dir) { "未找到 testvectors" }
        return Json.parseToJsonElement(File(vectorsDir, manifest.string("keys_file")).readText())
            .jsonObject
            .mapKeys { it.key.toInt() }
            .mapValues { (_, value) -> hex(value.jsonPrimitive.content) }
    }

    fun file(case: JsonObject): File = File(checkNotNull(dir) { "未找到 testvectors" }, case.string("file"))

    private fun hex(value: String): ByteArray =
        ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

fun JsonObject.vectors(): List<JsonObject> = this["vectors"]!!.jsonArray.map { it.jsonObject }

fun JsonObject.string(key: String): String = this[key]!!.jsonPrimitive.content

fun JsonObject.long(key: String): Long = this[key]!!.jsonPrimitive.content.toLong()
