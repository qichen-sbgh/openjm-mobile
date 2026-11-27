package com.openjm.reader.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 解密后的元数据。`name` 已经在解析时去掉路径分隔符，
 * 消费端可以直接拿它当文件名，不必再防穿越。
 */
data class JmMeta(
    val docId: String,
    val name: String,
    val ext: String,
    val size: Long,
    val pad: Int,
    val ts: Long,
)

private val json = Json { ignoreUnknownKeys = true }

private fun JsonObject.requiredString(key: String): String =
    this[key]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
        ?: throw JmError.Format("元数据缺少字段：$key")

private fun JsonObject.requiredLong(key: String): Long =
    this[key]?.jsonPrimitive?.content?.toLongOrNull()
        ?: throw JmError.Format("元数据字段不是整数：$key")

fun parseJmMeta(text: String): JmMeta {
    val obj = try {
        json.parseToJsonElement(text).jsonObject
    } catch (e: Exception) {
        throw JmError.Format("元数据 JSON 解析失败")
    }

    val safeName = obj.requiredString("name")
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .trim()
    if (safeName.isEmpty()) throw JmError.Format("元数据里的文件名为空")

    val docId = obj.requiredString("doc_id")
    if (docId.length != 32 || docId.any { it !in "0123456789abcdef" }) {
        throw JmError.Format("doc_id 不是 32 位小写 hex")
    }

    val size = obj.requiredLong("size")
    if (size < 0) throw JmError.Format("元数据 size 为负")

    val pad = obj.requiredLong("pad")
    if (pad < 0 || pad > JmProtocol.MAX_PAD_LEN) throw JmError.Format("元数据 pad 越界：$pad")

    return JmMeta(
        docId = docId,
        name = safeName,
        ext = obj.requiredString("ext").lowercase(),
        size = size,
        pad = pad.toInt(),
        ts = obj.requiredLong("ts"),
    )
}
