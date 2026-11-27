@file:OptIn(ExperimentalForeignApi::class)

package com.openjm.reader.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.rawPtr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.ULongVar
import platform.CoreCrypto.CCKeyDerivationPBKDF
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_CTX
import platform.CoreCrypto.CC_SHA256_Final
import platform.CoreCrypto.CC_SHA256_Init
import platform.CoreCrypto.CC_SHA256_Update
import platform.CoreCrypto.CCCrypt
import platform.CoreCrypto.kCCAlgorithmAES
import platform.CoreCrypto.kCCOptionECBMode
import platform.CoreCrypto.kCCEncrypt
import platform.CoreCrypto.kCCPBKDF2
import platform.CoreCrypto.kCCPRFHmacAlgSHA256
import platform.CoreCrypto.kCCSuccess

/**
 * iOS 侧加密原语，只用系统自带的 CommonCrypto。
 *
 * ⚠️ 本文件尚未在 Xcode 侧编译验证（当前机器没有 JDK / Xcode 工具链）。
 * cinterop 的类型名若与预期不同，按 Xcode 补全修正即可，**语义以协议 §4/§6 为准**。
 *
 * 两个实现选择值得记一下：
 * 1. HMAC 是手工按 RFC 2104 拼的（ipad/opad + CC_SHA256），而不是 `CCHmacContext`——
 *    `CC_SHA256_CTX` 的绑定更稳定，且能保证流式更新；
 * 2. AES-CTR 没有用 `CCCryptorCreateWithMode(kCCModeOptionCTR_BE)`，而是
 *    **ECB 批量加密计数器块再异或**：ECB 每块独立，把 N 个计数器拼成一块输入即可一次生成
 *    N 块密钥流，天然是大端计数器，绕开了 CTR 选项踩错就全盘偏移的坑。
 *    性能上每 4KB 一次 `CCCrypt` 调用，和原生 CTR 同数量级。
 */

private const val SHA256_BLOCK = 64
private const val AES_BLOCK = 16
private const val COUNTER_BLOCKS_PER_BATCH = 256 // 4KB 密钥流

private fun sha256(data: ByteArray): ByteArray = memScoped {
    val digest = allocArray<UByteVar>(32)
    if (data.isEmpty()) {
        CC_SHA256(null, 0u, digest)
    } else {
        data.usePinned { CC_SHA256(it.addressOf(0), data.size.toUInt(), digest) }
    }
    ByteArray(32) { digest[it].toByte() }
}

private class IosHmac(key: ByteArray) : JmMac {
    private val keyBlock = ByteArray(SHA256_BLOCK)
    private val inner = nativeHeap.alloc<CC_SHA256_CTX>()
    private var finished = false

    init {
        val normalized = if (key.size > SHA256_BLOCK) sha256(key) else key
        normalized.copyInto(keyBlock)

        val ipad = ByteArray(SHA256_BLOCK) { (keyBlock[it].toInt() xor 0x36).toByte() }
        CC_SHA256_Init(inner.ptr)
        ipad.usePinned { CC_SHA256_Update(inner.ptr, it.addressOf(0), SHA256_BLOCK.toUInt()) }
    }

    override fun update(data: ByteArray, offset: Int, length: Int) {
        check(!finished) { "HMAC 已经 finalize" }
        if (length == 0) return
        data.usePinned { CC_SHA256_Update(inner.ptr, it.addressOf(offset), length.toUInt()) }
    }

    override fun doFinal(): ByteArray {
        check(!finished) { "HMAC 只能 finalize 一次" }
        finished = true

        val innerDigest = ByteArray(32)
        innerDigest.usePinned { CC_SHA256_Final(it.addressOf(0).reinterpret(), inner.ptr) }

        val outer = nativeHeap.alloc<CC_SHA256_CTX>()
        try {
            val opad = ByteArray(SHA256_BLOCK) { (keyBlock[it].toInt() xor 0x5c).toByte() }
            CC_SHA256_Init(outer.ptr)
            opad.usePinned { CC_SHA256_Update(outer.ptr, it.addressOf(0), SHA256_BLOCK.toUInt()) }
            innerDigest.usePinned { CC_SHA256_Update(outer.ptr, it.addressOf(0), 32u) }

            val result = ByteArray(32)
            result.usePinned { CC_SHA256_Final(it.addressOf(0).reinterpret(), outer.ptr) }
            return result
        } finally {
            nativeHeap.free(inner.rawPtr)
            nativeHeap.free(outer.rawPtr)
        }
    }
}

private class IosCtrCipher(key: ByteArray, iv: ByteArray) : JmCtrCipher {
    private val key = key.copyOf()
    private val counter = iv.copyOf()
    private var keystream = ByteArray(0)
    private var keystreamOffset = 0

    override fun update(data: ByteArray, offset: Int, length: Int): ByteArray {
        val out = ByteArray(length)
        var index = 0
        while (index < length) {
            if (keystreamOffset >= keystream.size) {
                keystream = encryptCounterBlocks(COUNTER_BLOCKS_PER_BATCH)
                keystreamOffset = 0
            }
            val take = minOf(length - index, keystream.size - keystreamOffset)
            for (i in 0 until take) {
                val k = keystream[keystreamOffset + i].toInt()
                out[index + i] = (data[offset + index + i].toInt() xor k).toByte()
            }
            index += take
            keystreamOffset += take
        }
        return out
    }

    override fun doFinal(): ByteArray = ByteArray(0)

    /** 把 N 个连续计数器块一次 ECB 加密，得到 N 块密钥流。 */
    private fun encryptCounterBlocks(blocks: Int): ByteArray {
        val counters = ByteArray(blocks * AES_BLOCK)
        for (block in 0 until blocks) {
            counter.copyInto(counters, block * AES_BLOCK)
            incrementCounter()
        }

        val keystreamOut = ByteArray(counters.size)
        counters.usePinned { countersPtr ->
            keystreamOut.usePinned { outPtr ->
                key.usePinned { keyPtr ->
                    memScoped {
                        val moved = alloc<ULongVar>()
                        val status = CCCrypt(
                            kCCEncrypt,
                            kCCAlgorithmAES,
                            kCCOptionECBMode,
                            keyPtr.addressOf(0),
                            key.size.toULong(),
                            null,
                            countersPtr.addressOf(0),
                            counters.size.toULong(),
                            outPtr.addressOf(0),
                            keystreamOut.size.toULong(),
                            moved.ptr,
                        )
                        if (status != kCCSuccess) {
                            throw IllegalStateException("CommonCrypto AES-ECB 失败：status=$status")
                        }
                    }
                }
            }
        }
        return keystreamOut
    }

    /** 大端 128-bit 计数器递增，与 Python / Android 一致。 */
    private fun incrementCounter() {
        for (i in AES_BLOCK - 1 downTo 0) {
            val next = (counter[i].toInt() + 1) and 0xFF
            counter[i] = next.toByte()
            if (next != 0) return
        }
    }
}

actual fun createHmacSha256(key: ByteArray): JmMac = IosHmac(key)

actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
    IosHmac(key).apply { update(data, 0, data.size) }.doFinal()

actual fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
    val derived = ByteArray(dkLen)
    password.usePinned { passwordPtr ->
        salt.usePinned { saltPtr ->
            derived.usePinned { derivedPtr ->
                val status = CCKeyDerivationPBKDF(
                    kCCPBKDF2,
                    passwordPtr.addressOf(0),
                    password.size.toULong(),
                    saltPtr.addressOf(0),
                    salt.size.toULong(),
                    kCCPRFHmacAlgSHA256,
                    iterations.toUInt(),
                    derivedPtr.addressOf(0),
                    dkLen.toULong(),
                )
                if (status != kCCSuccess) {
                    throw IllegalStateException("CommonCrypto PBKDF2 失败：status=$status")
                }
            }
        }
    }
    return derived
}

actual fun createAesCtrDecryptor(key: ByteArray, iv: ByteArray): JmCtrCipher = IosCtrCipher(key, iv)
