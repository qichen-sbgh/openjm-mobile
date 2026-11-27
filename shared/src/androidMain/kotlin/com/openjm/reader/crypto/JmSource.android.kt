package com.openjm.reader.crypto

import android.content.ContentResolver
import android.net.Uri
import java.io.InputStream
import java.io.OutputStream

/** 把 `ContentResolver.openInputStream` 包装成顺序源：QQ 传来的流不支持 seek。 */
class InputStreamSource(private val input: InputStream) : JmSource {
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        input.read(buffer, offset, length)

    fun close() {
        runCatching { input.close() }
    }
}

class OutputStreamSink(private val output: OutputStream) : JmSink {
    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        output.write(buffer, offset, length)
    }

    fun close() {
        runCatching { output.close() }
    }
}

/**
 * 打开任意 `content://` / `file://` URI 的顺序读取源。
 * 返回 null 表示 URI 已失效（QQ 临时目录被清过），调用方提示重新下载。
 */
fun ContentResolver.openJmSource(uri: Uri): InputStreamSource? =
    openInputStream(uri)?.let { InputStreamSource(it) }
