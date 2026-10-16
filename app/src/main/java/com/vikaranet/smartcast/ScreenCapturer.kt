package com.vikaranet.smartcast

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import com.vikaranet.smartcast.core.FitMode
import com.vikaranet.smartcast.core.FrameHub
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** Output resolution and quality chosen on the phone. */
data class CaptureSettings(
    val outWidth: Int = 1280,
    val outHeight: Int = 720,
    val fps: Int = 24,
    val jpegQuality: Int = 70,
)

/**
 * Captures the phone screen and turns every frame into a 16:9 TV-shaped picture
 * (1280x720 or 1920x1080), fitted according to [FitMode], then publishes it as JPEG.
 */
class ScreenCapturer(
    private val context: Context,
    private val projection: MediaProjection,
    private val hub: FrameHub,
    private val fit: () -> FitMode,
    private val settings: () -> CaptureSettings,
    private val onStopped: () -> Unit,
) {
    private val thread = HandlerThread("smartcast-capture").apply { start() }
    private val handler = Handler(thread.looper)

    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var captureW = 0
    private var captureH = 0
    @Volatile private var stopped = false

    /** Pictures sent to the TV so far, and the last capture problem (shown on the phone). */
    @Volatile var framesSent = 0L
        private set
    @Volatile var framesCaptured = 0L
        private set
    @Volatile var lastError: String? = null
        private set
    private var padded: ByteBuffer? = null

    private var scratch: Bitmap? = null
    private var out: Bitmap? = null
    private var tiny: Bitmap? = null
    private var soft: Bitmap? = null
    private val jpeg = ByteArrayOutputStream(512 * 1024)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dim = Paint().apply { color = Color.argb(140, 0, 0, 0) }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stop()
            onStopped()
        }
    }

    fun start() {
        // Android 14+ requires the callback before createVirtualDisplay.
        projection.registerCallback(projectionCallback, handler)
        val size = captureSize()
        captureW = size[0]; captureH = size[1]
        val r = newReader(captureW, captureH)
        reader = r
        display = projection.createVirtualDisplay(
            "SmartCast", captureW, captureH, size[2],
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, handler
        )
    }

    /** Called when the phone rotates: re-shape the capture so landscape stays landscape. */
    fun onDisplayChanged() {
        handler.post {
            if (stopped) return@post
            val size = captureSize()
            if (size[0] == captureW && size[1] == captureH) return@post
            val vd = display ?: return@post
            val old = reader
            val r = newReader(size[0], size[1])
            captureW = size[0]; captureH = size[1]
            vd.resize(captureW, captureH, size[2])
            vd.surface = r.surface
            reader = r
            old?.close()
        }
    }

    fun stop() {
        if (stopped) return
        stopped = true
        handler.post {
            runCatching { projection.unregisterCallback(projectionCallback) }
            runCatching { display?.release() }
            runCatching { reader?.close() }
            runCatching { projection.stop() }
            display = null; reader = null
            scratch?.recycle(); out?.recycle(); tiny?.recycle(); soft?.recycle()
            scratch = null; out = null; tiny = null; soft = null
            thread.quitSafely()
        }
    }

    /** Width, height, dpi for the virtual display: the real screen shape, scaled to the TV resolution. */
    private fun captureSize(): IntArray {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val w: Int
        val h: Int
        val dpi: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = wm.maximumWindowMetrics.bounds
            w = bounds.width(); h = bounds.height()
            dpi = context.resources.displayMetrics.densityDpi
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            w = dm.widthPixels; h = dm.heightPixels; dpi = dm.densityDpi
        }
        val target = settings().outWidth.toFloat()
        val scale = minOf(1f, target / maxOf(w, h))
        fun even(v: Float) = maxOf(2, (v.toInt() / 2) * 2)
        return intArrayOf(even(w * scale), even(h * scale), maxOf(120, (dpi * scale).toInt()))
    }

    private fun newReader(w: Int, h: Int): ImageReader =
        ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2).also { r ->
            r.setOnImageAvailableListener({ onImage(it) }, handler)
        }

    private fun onImage(r: ImageReader) {
        if (stopped || r !== reader) return
        val started = SystemClock.uptimeMillis()
        val image = try { r.acquireLatestImage() } catch (e: Exception) { lastError = "${e.javaClass.simpleName}: ${e.message}"; null } ?: return
        framesCaptured++
        try {
            val w = image.width
            val h = image.height
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val paddedW = rowStride / pixelStride
            var src = scratch
            if (src == null || src.width != paddedW || src.height != h) {
                src?.recycle()
                src = Bitmap.createBitmap(paddedW, h, Bitmap.Config.ARGB_8888)
                scratch = src
            }
            val bmp: Bitmap = src!!
            val buffer = plane.buffer
            buffer.rewind()
            // Many phones (Samsung especially) leave the last row unpadded, so the buffer is a few
            // bytes shorter than rowStride × height and copyPixelsFromBuffer refuses it.
            // Copy into a buffer of the full size first in that case.
            val needed = rowStride * h
            val pixels: ByteBuffer = if (buffer.remaining() >= needed) buffer else {
                var p = padded
                if (p == null || p.capacity() < needed) {
                    p = ByteBuffer.allocateDirect(needed)
                    padded = p
                }
                val full: ByteBuffer = p!!
                full.clear()
                full.put(buffer)
                full.rewind()
                full
            }
            bmp.copyPixelsFromBuffer(pixels)
            compose(bmp, w, h)
            framesSent++
            lastError = null
        } catch (e: Exception) {
            lastError = "${e.javaClass.simpleName}: ${e.message}"
        } finally {
            image.close()
        }
        // Keep to the chosen frame rate. Frames that arrive meanwhile wait in the reader,
        // so the newest screen is always picked up next (nothing gets lost on a still screen).
        val minGap = 1000L / settings().fps.coerceIn(5, 60)
        val spent = SystemClock.uptimeMillis() - started
        if (spent < minGap) SystemClock.sleep(minGap - spent)
    }

    private fun compose(src: Bitmap, w: Int, h: Int) {
        val s = settings()
        val ow = s.outWidth
        val oh = s.outHeight
        var o = out
        if (o == null || o.width != ow || o.height != oh) {
            o?.recycle()
            o = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
            out = o
        }
        val target: Bitmap = o!!
        val canvas = Canvas(target)
        canvas.drawColor(Color.BLACK)
        val region = Rect(0, 0, w, h)
        val tvAspect = ow.toFloat() / oh

        when (fit()) {
            FitMode.FILL -> canvas.drawBitmap(src, coverRect(w, h, tvAspect), Rect(0, 0, ow, oh), paint)
            FitMode.FIT -> canvas.drawBitmap(src, region, fitRect(w, h, ow, oh), paint)
            FitMode.SMART -> {
                val dst = fitRect(w, h, ow, oh)
                // Only bother with the blurred backdrop if there is visible empty space.
                if (dst.width() < ow - 4 || dst.height() < oh - 4) drawBlurredBackdrop(canvas, src, w, h, ow, oh, tvAspect)
                canvas.drawBitmap(src, region, dst, paint)
            }
        }
        jpeg.reset()
        target.compress(Bitmap.CompressFormat.JPEG, s.jpegQuality.coerceIn(30, 95), jpeg)
        hub.publish(jpeg.toByteArray())
    }

    /** Cheap, good-looking blur: shrink to a few pixels, enlarge twice with filtering, darken. */
    private fun drawBlurredBackdrop(canvas: Canvas, src: Bitmap, w: Int, h: Int, ow: Int, oh: Int, tvAspect: Float) {
        var t = tiny
        if (t == null) { t = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888); tiny = t }
        var m = soft
        if (m == null) { m = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888); soft = m }
        val small: Bitmap = t!!
        val mid: Bitmap = m!!
        Canvas(small).drawBitmap(src, coverRect(w, h, tvAspect), Rect(0, 0, small.width, small.height), paint)
        Canvas(mid).drawBitmap(small, null as Rect?, Rect(0, 0, mid.width, mid.height), paint)
        canvas.drawBitmap(mid, null as Rect?, Rect(0, 0, ow, oh), paint)
        canvas.drawRect(0f, 0f, ow.toFloat(), oh.toFloat(), dim)
    }

    private fun fitRect(w: Int, h: Int, ow: Int, oh: Int): RectF {
        val scale = minOf(ow.toFloat() / w, oh.toFloat() / h)
        val dw = w * scale
        val dh = h * scale
        val left = (ow - dw) / 2f
        val top = (oh - dh) / 2f
        return RectF(left, top, left + dw, top + dh)
    }

    /** The centered part of the source that has the TV's shape. */
    private fun coverRect(w: Int, h: Int, aspect: Float): Rect {
        val srcAspect = w.toFloat() / h
        return if (srcAspect > aspect) {
            val cw = (h * aspect).toInt()
            val x = (w - cw) / 2
            Rect(x, 0, x + cw, h)
        } else {
            val ch = (w / aspect).toInt()
            val y = (h - ch) / 2
            Rect(0, y, w, y + ch)
        }
    }
}
