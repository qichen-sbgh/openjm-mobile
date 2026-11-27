package com.openjm.reader.crypto

/** 导入成功的产物。 */
class JmImportResult(val meta: JmMeta, val totalBytes: Long)

/**
 * 单遍流式导入：一边算 HMAC，一边解密写 sink。
 *
 * 调用约定（很重要）：
 * 1. [readHeader] 先把 28 字节头读完并解析，此时就能决定要不要弹口令；
 * 2. [import] 从当前位置继续读到 EOF，明文写进 `sink`——但 **此时还没校验通过**；
 * 3. 只有 [import] 正常返回后才允许把临时文件转正，抛错就删掉临时文件。
 *
 * 这样即使 QQ 下载中断，也绝不会留下一个能打开的“半个文档”。
 */
object JmImporter {

    fun readHeader(source: JmSource): Pair<JmHeader, ByteArray> {
        val raw = ByteArray(JmProtocol.HEADER_SIZE)
        var offset = 0
        while (offset < raw.size) {
            val read = source.read(raw, offset, raw.size - offset)
            if (read <= 0) throw JmError.Format("文件长度不足 28 字节，不是完整的 .jm 文件")
            offset += read
        }
        return JmHeader.parse(raw) to raw
    }

    fun import(
        source: JmSource,
        header: JmHeader,
        headerBytes: ByteArray,
        rootKeys: Map<Int, ByteArray>,
        password: String?,
        sink: JmSink,
    ): JmImportResult {
        val keys = JmKeyDerivation.derive(header.nonce, header.flags, header.keyId, rootKeys, password)
        val cipher = createAesCtrDecryptor(keys.encKey, keys.iv)
        val mac = createHmacSha256(keys.macKey)
        mac.update(headerBytes, 0, headerBytes.size)

        var total = headerBytes.size.toLong()
        var remainingMeta = header.metaLen
        val metaBuffer = ByteArray(header.metaLen)
        var metaOffset = 0
        var meta: JmMeta? = null
        var remainingPlain = -1L

        fun emit(plain: ByteArray, length: Int) {
            var offset = 0
            var remaining = length

            if (remainingMeta > 0) {
                val take = minOf(remainingMeta, remaining)
                plain.copyInto(metaBuffer, metaOffset, offset, offset + take)
                metaOffset += take
                remainingMeta -= take
                offset += take
                remaining -= take
                if (remainingMeta == 0) {
                    val parsed = parseJmMeta(metaBuffer.decodeToString())
                    meta = parsed
                    remainingPlain = parsed.size
                }
            }

            if (remaining > 0 && remainingPlain > 0) {
                val take = minOf(remainingPlain, remaining.toLong()).toInt()
                sink.write(plain, offset, take)
                remainingPlain -= take
                offset += take
                remaining -= take
            }
            // 余下的是随机填充：密文必须喂进 cipher 保持密钥流连续，明文直接丢弃。
        }

        val chunk = ByteArray(JmProtocol.CHUNK_SIZE)
        var held = ByteArray(0)

        while (true) {
            val read = source.read(chunk, 0, chunk.size)
            if (read < 0) break
            if (read == 0) continue
            total += read

            val combined = if (held.isEmpty()) {
                chunk.copyOf(read)
            } else {
                ByteArray(held.size + read).also {
                    held.copyInto(it, 0, 0, held.size)
                    chunk.copyInto(it, held.size, 0, read)
                }
            }

            // 尾部 32B 是 HMAC，不能参与 HMAC 本身的计算，所以永远“留一手”。
            if (combined.size <= JmProtocol.MAC_SIZE) {
                held = combined
                continue
            }

            val bodyLength = combined.size - JmProtocol.MAC_SIZE
            val body = combined.copyOfRange(0, bodyLength)
            held = combined.copyOfRange(bodyLength, combined.size)

            mac.update(body, 0, bodyLength)
            emit(cipher.update(body, 0, bodyLength), bodyLength)
        }

        cipher.doFinal()

        if (held.size != JmProtocol.MAC_SIZE) {
            throw JmError.Auth("文件没有读完：尾部缺少认证标签，请在 QQ 重新下载")
        }
        if (!constantTimeEquals(mac.doFinal(), held)) {
            throw JmError.Auth()
        }

        val parsed = meta ?: throw JmError.Format("缺少元数据段")
        val claimed = JmProtocol.HEADER_SIZE +
            header.metaLen +
            parsed.size +
            parsed.pad +
            JmProtocol.MAC_SIZE
        if (claimed != total) {
            throw JmError.Format("元数据声明长度（$claimed）与实际文件大小（$total）不一致")
        }

        return JmImportResult(parsed, total)
    }
}
