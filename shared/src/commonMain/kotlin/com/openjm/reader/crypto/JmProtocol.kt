package com.openjm.reader.crypto

/** `.jm` v1 常量与 28 字节固定头解析。字节序一律大端。 */
object JmProtocol {
    const val HEADER_SIZE = 28
    const val MAC_SIZE = 32
    const val MIN_FILE_SIZE = HEADER_SIZE + MAC_SIZE

    const val VERSION = 0x01
    const val ALG_AES128_CTR_HMAC_SHA256 = 0x01
    const val FLAG_PASSWORD = 0x01

    const val MAX_META_LEN = 64 * 1024
    const val MAX_PAD_LEN = 4096
    const val PBKDF2_ITERATIONS = 100_000
    const val CHUNK_SIZE = 64 * 1024

    const val HKDF_INFO = "JM01-KEYS"

    /** 保留给跨端测试向量的公开测试密钥，禁止用于真实文档。 */
    const val KEY_ID_TEST_VECTOR = 254

    val MAGIC = byteArrayOf(0x4A, 0x4D, 0x30, 0x31) // "JM01"
}

class JmHeader(
    val version: Int,
    val alg: Int,
    val flags: Int,
    val keyId: Int,
    val nonce: ByteArray,
    val metaLen: Int,
) {
    val isPasswordProtected: Boolean get() = flags and JmProtocol.FLAG_PASSWORD != 0

    companion object {
        fun parse(raw: ByteArray): JmHeader {
            if (raw.size < JmProtocol.HEADER_SIZE) {
                throw JmError.Format("文件长度不足 28 字节，不是完整的 .jm 文件")
            }
            for (i in JmProtocol.MAGIC.indices) {
                if (raw[i] != JmProtocol.MAGIC[i]) {
                    throw JmError.Format("文件头魔数不匹配，不是 .jm 文件")
                }
            }

            val version = raw[4].toInt() and 0xFF
            if (version != JmProtocol.VERSION) throw JmError.Format("不支持的协议版本：$version")

            val alg = raw[5].toInt() and 0xFF
            if (alg != JmProtocol.ALG_AES128_CTR_HMAC_SHA256) throw JmError.Format("不支持的算法标识：$alg")

            val flags = raw[6].toInt() and 0xFF
            val keyId = raw[7].toInt() and 0xFF
            val nonce = raw.copyOfRange(8, 24)
            val metaLen = readU32(raw, 24)
            if (metaLen <= 0 || metaLen > JmProtocol.MAX_META_LEN) {
                throw JmError.Format("非法的元数据长度：$metaLen")
            }

            return JmHeader(version, alg, flags, keyId, nonce, metaLen)
        }
    }
}

internal fun readU32(bytes: ByteArray, offset: Int): Int {
    return ((bytes[offset].toInt() and 0xFF) shl 24) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
        (bytes[offset + 3].toInt() and 0xFF)
}
