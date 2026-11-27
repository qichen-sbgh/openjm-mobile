package com.openjm.reader.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 根密钥装载：`{"1": "<64 位 hex>", "254": "<64 位 hex>"}`，与 SDK 的 `keys.json` 同格式。
 *
 * 本函数只负责“解析 + 校验”，不决定密钥从哪来：生产密钥的注入方式还没定，
 * 见 `openjm` 仓库 [`key-registry.md`](https://github.com/qichen-sbgh/openjm/blob/main/docs/tech/key-registry.md)
 * 与 `decisions.md` 的待定项。调用方拿到的 map 直接喂给 [JmImporter]。
 *
 * `key_id = 0` 拒绝：它只表示口令模式，不是密钥槽位。
 */
fun parseRootKeys(text: String): Map<Int, ByteArray> {
    val obj = try {
        Json.parseToJsonElement(text).jsonObject
    } catch (e: Exception) {
        throw JmError.Format("根密钥不是合法 JSON 对象")
    }

    val keys = LinkedHashMap<Int, ByteArray>(obj.size)
    for ((rawKeyId, value) in obj) {
        val keyId = rawKeyId.toIntOrNull()
            ?: throw JmError.Format("根密钥的 key_id 不是整数：$rawKeyId")
        if (keyId !in 1..255) throw JmError.Format("根密钥的 key_id 越界：$keyId")
        val hex = value.jsonPrimitive.content
        if (hex.length != 64) throw JmError.Format("key_id=$keyId 的根密钥不是 32 字节")
        keys[keyId] = hexToBytes(hex, keyId)
    }
    if (keys.isEmpty()) throw JmError.Format("根密钥文件里没有任何 key_id")
    return keys
}

private fun hexToBytes(hex: String, keyId: Int): ByteArray {
    val bytes = ByteArray(hex.length / 2)
    for (i in bytes.indices) {
        val high = hex[i * 2].digitToIntOrNull(16)
        val low = hex[i * 2 + 1].digitToIntOrNull(16)
        if (high == null || low == null) throw JmError.Format("key_id=$keyId 的根密钥含非 hex 字符")
        bytes[i] = ((high shl 4) or low).toByte()
    }
    return bytes
}
