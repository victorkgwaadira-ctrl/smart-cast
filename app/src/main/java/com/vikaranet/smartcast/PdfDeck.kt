package com.vikaranet.smartcast

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.vikaranet.smartcast.core.SlideSource
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File

/**
 * A presentation saved as PDF (PowerPoint, Google Slides, Keynote and Canva can all export one).
 * Each page is drawn as a sharp 1920x1080 picture, centered on black if the slide isn't 16:9.
 */
class PdfDeck private constructor(
    override val name: String,
    private val file: File,
) : SlideSource, Closeable {

    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(pfd)
    override val count: Int = renderer.pageCount

    /** Small cache: the current slide, its neighbours and a few recent ones. */
    private val cache = object : LinkedHashMap<Int, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ByteArray>?): Boolean = size > 10
    }
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    @Volatile private var closed = false

    companion object {
        const val WIDTH = 1920
        const val HEIGHT = 1080

        /** Copies the chosen PDF into app storage (PdfRenderer needs a seekable file) and opens it. */
        fun open(context: Context, uri: Uri, displayName: String): PdfDeck {
            val dir = File(context.cacheDir, "decks").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() } // keep only the current presentation
            val file = File(dir, "deck-${System.currentTimeMillis()}.pdf")
            val input = context.contentResolver.openInputStream(uri)
                ?: throw java.io.FileNotFoundException("Couldn't open the file")
            input.use { inp -> file.outputStream().use { inp.copyTo(it) } }
            try {
                return PdfDeck(displayName.removeSuffix(".pdf").removeSuffix(".PDF"), file)
            } catch (e: Exception) {
                file.delete()
                throw e
            }
        }
    }

    @Synchronized
    override fun slideJpeg(index: Int): ByteArray? {
        if (closed || index !in 0 until count) return null
        cache[index]?.let { return it }
        return try {
            val bmp = render(index)
            val out = ByteArrayOutputStream(400 * 1024)
            bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
            bmp.recycle()
            out.toByteArray().also { cache[index] = it }
        } catch (_: Exception) {
            null
        }
    }

    /** Renders the slide sized to fill a 16:9 TV frame. */
    private fun render(index: Int): Bitmap {
        val page = renderer.openPage(index)
        try {
            val scale = minOf(WIDTH.toFloat() / page.width, HEIGHT.toFloat() / page.height)
            val w = maxOf(1, (page.width * scale).toInt())
            val h = maxOf(1, (page.height * scale).toInt())
            val slide = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            slide.eraseColor(Color.WHITE) // PDFs assume a white page
            page.render(slide, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            if (w == WIDTH && h == HEIGHT) return slide
            val frame = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
            val c = Canvas(frame)
            c.drawColor(Color.BLACK)
            val left = (WIDTH - w) / 2
            val top = (HEIGHT - h) / 2
            c.drawBitmap(slide, null as Rect?, Rect(left, top, left + w, top + h), paint)
            slide.recycle()
            return frame
        } finally {
            page.close()
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        cache.clear()
        runCatching { renderer.close() }
        runCatching { pfd.close() }
        file.delete()
    }
}
