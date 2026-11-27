package com.openjm.reader.crypto

import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Android 侧加密原语，全部走 `javax.crypto`，不引第三方库。
 *
 * 两个必守点：
 * 1. `AES/CTR/NoPadding` 的计数器是**大端递增**，与 Python `cryptography` 一致；
 * 2. `PBEKeySpec` 内部按 UTF-8 编码 char[]，所以这里把口令的 UTF-8 字节
 *    还原成 String 再转 char[]，才能和 Python 的 `password.encode("utf-8")` 对齐。
 */

private class AndroidHmac(key: ByteArray) : JmMac {
    private val mac = Mac.getInstance("HmacSHA256").apply {
        init(SecretKeySpec(key, "HmacSHA256"))
    }

    override fun update(data: ByteArray, offset: Int, length: Int) {
        mac.update(data, offset, length)
    }

    override fun doFinal(): ByteArray = mac.doFinal()
}

private class AndroidCtrCipher(key: ByteArray, iv: ByteArray) : JmCtrCipher {
    private val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
        init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
    }

    override fun update(data: ByteArray, offset: Int, length: Int): ByteArray =
        cipher.update(data, offset, length) ?: ByteArray(0)

    override fun doFinal(): ByteArray = cipher.doFinal()
}

actual fun createHmacSha256(key: ByteArray): JmMac = AndroidHmac(key)

actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
    AndroidHmac(key).apply { update(data, 0, data.size) }.doFinal()

actual fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
    val chars = password.toString(Charsets.UTF_8).toCharArray()
    val spec = PBEKeySpec(chars, salt, iterations, dkLen * 8)
    try {
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    } finally {
        spec.clearPassword()
    }
}

actual fun createAesCtrDecryptor(key: ByteArray, iv: ByteArray): JmCtrCipher = AndroidCtrCipher(key, iv)
