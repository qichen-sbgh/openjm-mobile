package com.openjm.reader.crypto

/**
 * 平台加密原语。Android 落 `javax.crypto`，iOS 落 `CommonCrypto`，
 * 两边都只用系统自带能力，不引第三方密码学库。
 *
 * 流式接口是刻意的：`.jm` 动辄几十 MB，且来源（QQ 传来的 `content://` 流）
 * 通常不支持 seek，所以校验 + 解密必须单遍完成。
 */

/** 增量 HMAC-SHA256。 */
interface JmMac {
    fun update(data: ByteArray, offset: Int, length: Int)
    fun doFinal(): ByteArray
}

/** 增量 AES-128-CTR 解密器（CTR 加解密同一套操作）。 */
interface JmCtrCipher {
    fun update(data: ByteArray, offset: Int, length: Int): ByteArray
    fun doFinal(): ByteArray
}

/** 顺序字节源。EOF 返回 -1，与 `InputStream.read` 语义一致。 */
interface JmSource {
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
}

/** 顺序字节汇。 */
interface JmSink {
    fun write(buffer: ByteArray, offset: Int, length: Int)
}

expect fun createHmacSha256(key: ByteArray): JmMac

expect fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray

expect fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray

expect fun createAesCtrDecryptor(key: ByteArray, iv: ByteArray): JmCtrCipher

/** 常量时间比较，避免按字节提前返回泄露 MAC 前缀。 */
internal fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (i in a.indices) {
        diff = diff or (a[i].toInt() xor b[i].toInt())
    }
    return diff == 0
}
