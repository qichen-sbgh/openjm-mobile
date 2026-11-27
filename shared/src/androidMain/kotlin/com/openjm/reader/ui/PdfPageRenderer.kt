package com.openjm.reader.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.ParcelFileDescriptor
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfiumCore
import java.io.Closeable
import java.io.File

/**
 * PDFium 的薄封装：一个实例只开一个文档，按需把单页渲染成 [Bitmap]。
 *
 * 两条约束来自库本身：
 * 1. 原生层不是线程安全的（库内部用 `PdfiumCore.lock` 串行），所以这里所有方法都 `synchronized`，
 *    调用方可以放心在 IO 线程渲染；
 * 2. 文档必须显式 `close()`，否则 native 内存不会回收 —— 由 Compose 的 `DisposableEffect` 兜住。
 *
 * 用 PDFium 而不是平台 `PdfRenderer` 的理由见 `openjm/docs/tech/decisions.md` D6。
 */
class PdfPageRenderer(private val file: File) : Closeable {

    private var core: PdfiumCore? = null
    private var document: PdfDocument? = null
    private var pageCountState = 0
    private var firstPageRatio: Float? = null
    private var closed = false

    val pageCount: Int get() = synchronized(this) { pageCountState }

    /** 打开文档并返回页数。幂等：重复调用不会重复打开。 */
    fun open(context: Context): Int = synchronized(this) {
        document?.let { return pageCountState }
        check(!closed) { "渲染器已关闭" }

        val pdfium = PdfiumCore(context.applicationContext)
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val doc = try {
            pdfium.newDocument(fd)
        } catch (e: Exception) {
            runCatching { fd.close() }
            throw e
        }
        core = pdfium
        document = doc
        pageCountState = doc.getPageCount()
        pageCountState
    }

    /** 首页的「高 / 宽」比例，用来在渲染完成前给列表项占位，避免滚动时跳动。 */
    fun aspectRatio(): Float = synchronized(this) {
        firstPageRatio?.let { return it }
        val doc = document ?: return DEFAULT_RATIO
        if (closed || doc.isClosed || pageCountState <= 0) return DEFAULT_RATIO
        val ratio = try {
            doc.openPage(0).use { page ->
                val width = page.getPageWidthPoint()
                val height = page.getPageHeightPoint()
                if (width > 0 && height > 0) height.toFloat() / width else DEFAULT_RATIO
            }
        } catch (e: Exception) {
            DEFAULT_RATIO
        }
        firstPageRatio = ratio
        ratio
    }

    /** 把第 [index] 页渲染成宽度为 [widthPx] 的位图；文档未打开或渲染失败返回 null。 */
    fun render(index: Int, widthPx: Int): Bitmap? = synchronized(this) {
        val doc = document ?: return null
        if (closed || doc.isClosed || widthPx <= 0 || index !in 0 until pageCountState) return null

        return try {
            doc.openPage(index).use { page ->
                val pageWidth = page.getPageWidthPoint()
                val pageHeight = page.getPageHeightPoint()
                if (pageWidth <= 0 || pageHeight <= 0) return null

                val heightPx = (widthPx.toLong() * pageHeight / pageWidth).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
                // 先铺白底：PDF 页本身可能是透明背景，深色主题下直接看会"没有纸"。
                Canvas(bitmap).drawColor(Color.WHITE)
                page.renderPageBitmap(bitmap, 0, 0, widthPx, heightPx)
                bitmap
            }
        } catch (e: Exception) {
            // 单页渲染失败不该让整本书打不开：交给 UI 显示占位。
            null
        }
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            runCatching { document?.close() }
            document = null
            core = null
            pageCountState = 0
        }
    }

    private companion object {
        /** A4 的高/宽，拿不到真实页面尺寸时用它占位。 */
        const val DEFAULT_RATIO = 1.4142f
    }
}
