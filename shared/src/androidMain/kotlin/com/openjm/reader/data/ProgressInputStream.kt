package com.openjm.reader.data

import java.io.InputStream

/**
 * 只读计数流：给导入过程报进度用。
 *
 * 放在流这一层而不是改 `JmImporter` 的循环，是因为"读了多少字节"本来就是流的事实，
 * 加密层不该为 UI 的进度条多一个参数。
 */
internal class ProgressInputStream(
    private val delegate: InputStream,
    private val onBytesRead: (Long) -> Unit,
) : InputStream() {

    private var total = 0L

    override fun read(): Int {
        val value = delegate.read()
        if (value >= 0) {
            total += 1
            onBytesRead(total)
        }
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val read = delegate.read(b, off, len)
        if (read > 0) {
            total += read
            onBytesRead(total)
        }
        return read
    }

    override fun available(): Int = delegate.available()

    override fun close() = delegate.close()
}
