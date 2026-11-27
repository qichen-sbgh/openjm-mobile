package com.openjm.reader.data

import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 封面落盘：把渲染好的首帧编码成 WebP 存进 `covers/{doc_id}.webp`。
 *
 * 格式在 `openjm/docs/tech/decisions.md` 的待定项里已定为 WebP（`WEBP_LOSSY`，质量 80）：
 * 同样的封面比 PNG 小一半以上，而书架缩略图对画质不敏感。
 *
 * 写盘顺序和正文一致：先写 `.tmp`，再原子改名——封面损坏可以重生成，
 * 但**不能出现"文件在、解码失败"的半张图**，那会让书架每次滚动都闪一下占位。
 */
class CoverStore(private val sandbox: JmSandbox) {

    /** 返回书架用的相对路径（`covers/{doc_id}.webp`）；失败返回 null，让 UI 退回占位块。 */
    fun save(docId: String, bitmap: Bitmap): String? {
        sandbox.ensureLayout()
        val target = sandbox.coverFile(docId)
        val tmp = File(target.parentFile, "${target.name}.tmp")
        return try {
            FileOutputStream(tmp).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, QUALITY, out)) {
                    throw IOException("WebP 编码失败")
                }
            }
            if (target.exists() && !target.delete()) throw IOException("无法覆盖旧封面")
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            sandbox.relativeCoverPath(docId)
        } catch (e: Exception) {
            tmp.delete()
            null
        }
    }

    private companion object {
        const val QUALITY = 80
    }
}
