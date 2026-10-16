package com.vikaranet.smartcast

import android.content.Context
import android.net.Uri
import com.vikaranet.smartcast.core.Dlna
import com.vikaranet.smartcast.core.Mode
import com.vikaranet.smartcast.core.Renderer
import java.util.concurrent.Executors

/**
 * Present mode: one presentation, a current slide, and the TVs showing it.
 * Slide changes come from the phone's buttons, its volume keys, a Bluetooth clicker
 * or keyboard paired to the phone, or the TV remote (through the TV page).
 */
object Presenter {
    private val state get() = CastService.state

    @Volatile var deck: PdfDeck? = null
        private set

    /** TV showing slides through its own photo viewer (DLNA), if chosen. */
    @Volatile var dlnaTarget: Renderer? = null
        private set
    @Volatile var lastError: String? = null

    private val dlnaWorker = Executors.newSingleThreadExecutor { r -> Thread(r, "smartcast-dlna-slides").apply { isDaemon = true } }
    private val renderWorker = Executors.newSingleThreadExecutor { r -> Thread(r, "smartcast-render").apply { isDaemon = true } }
    @Volatile private var dlnaShown = -1

    val index: Int get() = state.slideIndex
    val count: Int get() = deck?.count ?: 0
    val isActive: Boolean get() = deck != null && state.mode == Mode.PRESENT

    /** Opens a PDF and starts presenting it. Call off the main thread (copying can take a moment). */
    fun load(context: Context, uri: Uri, displayName: String) {
        val newDeck = PdfDeck.open(context.applicationContext, uri, displayName)
        if (newDeck.count == 0) {
            newDeck.close()
            throw IllegalArgumentException("That PDF has no pages")
        }
        val old = deck
        deck = newDeck
        dlnaShown = -1
        state.setSlides(newDeck)
        state.mode = Mode.PRESENT
        old?.close()
        prefetch(0)
        pushToDlna()
        CastService.notifyChanged()
    }

    fun handle(command: String) {
        when (command) {
            "next" -> next()
            "prev" -> prev()
            "first" -> go(0)
            "last" -> go(count - 1)
        }
    }

    fun next() = go(index + 1)
    fun prev() = go(index - 1)

    fun go(target: Int) {
        val d = deck ?: return
        val i = target.coerceIn(0, d.count - 1)
        if (state.mode != Mode.PRESENT) state.mode = Mode.PRESENT
        if (i == state.slideIndex) return
        state.slideIndex = i
        prefetch(i)
        pushToDlna()
        CastService.notifyChanged()
    }

    /** Draws the current slide and its neighbours ahead of time so changes are instant. */
    private fun prefetch(i: Int) {
        val d = deck ?: return
        renderWorker.execute {
            for (n in listOf(i, i + 1, i - 1, i + 2)) {
                if (deck !== d) return@execute
                if (n in 0 until d.count) d.slideJpeg(n)
            }
        }
    }

    /** Shows slides on a TV through its own photo viewer. */
    fun useDlna(target: Renderer?) {
        val old = dlnaTarget
        dlnaTarget = target
        dlnaShown = -1
        lastError = null
        if (target == null && old != null) dlnaWorker.execute { runCatching { Dlna.stop(old) } }
        pushToDlna()
        CastService.notifyChanged()
    }

    private fun pushToDlna() {
        if (dlnaTarget == null) return
        dlnaWorker.execute {
            // Several quick clicks collapse into one send of whatever slide is current now.
            val target = dlnaTarget ?: return@execute
            val d = deck ?: return@execute
            val i = state.slideIndex
            if (i == dlnaShown) return@execute
            val base = CastService.tvAddress() ?: return@execute
            try {
                d.slideJpeg(i) // make sure it's ready before the TV asks
                Dlna.play(target, base + state.slidePath(i), "image/jpeg", "${d.name} – slide ${i + 1}", stopFirst = false)
                dlnaShown = i
                lastError = null
            } catch (e: Exception) {
                lastError = "${target.name} didn't show the slide: ${e.message}"
                CastService.notifyChanged()
            }
        }
    }

    fun end() {
        val d = deck
        val target = dlnaTarget
        deck = null
        dlnaTarget = null
        dlnaShown = -1
        state.setSlides(null)
        if (state.mode == Mode.PRESENT) state.mode = state.restingMode()
        if (target != null) dlnaWorker.execute { runCatching { Dlna.stop(target) } }
        renderWorker.execute { d?.close() }
        CastService.notifyChanged()
    }
}
