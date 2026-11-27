@file:OptIn(ExperimentalForeignApi::class)

package com.openjm.reader.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileHandle
import platform.Foundation.NSInputStream
import platform.Foundation.NSURL

/**
 * QQ 唤起时拿到的是 security-scoped URL，必须在权限窗口内读完。
 * 这里用 `NSInputStream` 顺序读，不做 seek，也不落 `.jm` 副本。
 *
 * ⚠️ 尚未在 Xcode 侧编译验证。
 */
class NSInputStreamSource(private val stream: NSInputStream) : JmSource {
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        buffer.usePinned { pinned ->
            val target = pinned.addressOf(offset).reinterpret<UByteVar>()
            stream.read(target, length.toULong()).toInt()
        }

    fun close() {
        stream.close()
    }
}

class NSFileHandleSink(private val handle: NSFileHandle) : JmSink {
    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        if (length == 0) return
        val chunk = buffer.copyOfRange(offset, offset + length)
        chunk.usePinned { pinned ->
            val data = NSData.create(bytes = pinned.addressOf(0), length = length.toULong()) ?: return
            handle.writeData(data)
        }
    }

    fun close() {
        runCatching { handle.closeFile() }
    }
}

/**
 * 打开顺序读取源。调用方负责在读完前保持 security-scoped 访问，
 * 读完后调用 `startAccessingSecurityScopedResource` 的对应关闭。
 */
fun NSURL.openJmSource(): NSInputStreamSource {
    val stream = NSInputStream.inputStreamWithURL(this)
        ?: throw JmError.Format("无法打开文件流（URL 已失效）")
    stream.open()
    return NSInputStreamSource(stream)
}
