package com.openjm.reader.crypto

/** 派生结果：AES-128 密钥、CTR 初始计数器、HMAC 密钥。 */
class JmKeys(val encKey: ByteArray, val iv: ByteArray, val macKey: ByteArray)

/**
 * RFC 5869 HKDF-SHA256，与 Python SDK 逐字节对齐。
 *
 * 只有一处容易写错：`PRK = HMAC(key = nonce, msg = IKM)`，
 * 这里的 salt 是 nonce，不是常见的固定常量。
 */
object JmKeyDerivation {

    fun derive(
        nonce: ByteArray,
        flags: Int,
        keyId: Int,
        rootKeys: Map<Int, ByteArray>,
        password: String?,
    ): JmKeys {
        val ikm = if (flags and JmProtocol.FLAG_PASSWORD != 0) {
            if (password.isNullOrEmpty()) throw JmError.PasswordRequired()
            pbkdf2Sha256(
                password = password.encodeToByteArray(),
                salt = nonce,
                iterations = JmProtocol.PBKDF2_ITERATIONS,
                dkLen = 32,
            )
        } else {
            val key = rootKeys[keyId] ?: throw JmError.Format("未知的 key_id：$keyId")
            require(key.size == 32) { "根密钥必须为 32 字节" }
            key
        }

        val info = JmProtocol.HKDF_INFO.encodeToByteArray()
        val prk = hmacSha256(nonce, ikm)
        val t1 = hmacSha256(prk, info + byteArrayOf(0x01))
        val t2 = hmacSha256(prk, t1 + info + byteArrayOf(0x02))

        return JmKeys(
            encKey = t1.copyOfRange(0, 16),
            iv = t1.copyOfRange(16, 32),
            macKey = t2,
        )
    }
}
