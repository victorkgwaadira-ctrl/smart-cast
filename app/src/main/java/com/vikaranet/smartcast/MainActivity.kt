package com.vikaranet.smartcast

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.vikaranet.smartcast.core.Dlna
import com.vikaranet.smartcast.core.FitMode
import com.vikaranet.smartcast.core.Mode
import com.vikaranet.smartcast.core.NetUtil
import com.vikaranet.smartcast.core.Renderer
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private companion object {
        const val REQ_CAPTURE = 1
        const val REQ_PICK = 2
        const val REQ_NOTIFY = 3
        const val PREFS = "smartcast"
    }

    private lateinit var addressText: TextView
    private lateinit var addressHint: TextView
    private lateinit var errorText: TextView
    private lateinit var mirrorButton: Button
    private lateinit var mirrorStatus: TextView
    private lateinit var fitPicker: Ui.Segmented
    private lateinit var qualityPicker: Ui.Segmented
    private lateinit var mediaLabel: TextView
    private lateinit var playPauseButton: Button
    private lateinit var tvPlayerButton: Button
    private lateinit var stopMediaButton: Button

    /** TV currently playing our media through its own DLNA player, if any. */
    @Volatile private var dlnaTarget: Renderer? = null
    private var searching = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        setContentView(buildUi())
        restoreSettings()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
        }
        CastService.start(this)
    }

    override fun onResume() {
        super.onResume()
        CastService.listener = { refresh() }
        if (!CastService.running) CastService.start(this)
        refresh()
    }

    override fun onPause() {
        super.onPause()
        CastService.listener = null
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(this@MainActivity, 18)
            setPadding(p, Ui.dp(this@MainActivity, 28), p, p)
        }

        root.addView(Ui.text(this, "Smart Cast", 28f, bold = true))
        root.addView(Ui.text(this, "Your phone on the big screen — fitted to your TV.", 15f, Ui.MUTED).apply {
            setPadding(0, Ui.dp(this@MainActivity, 4), 0, Ui.dp(this@MainActivity, 18))
        })

        // --- Connect card -------------------------------------------------------
        val connect = Ui.card(this)
        connect.addView(Ui.text(this, "1 · CONNECT YOUR TV", 12f, Ui.ACCENT, bold = true))
        addressText = Ui.text(this, "Starting…", 24f, bold = true).apply {
            setPadding(0, Ui.dp(this@MainActivity, 8), 0, Ui.dp(this@MainActivity, 4))
            setOnClickListener { copyAddress() }
        }
        connect.addView(addressText)
        addressHint = Ui.text(this, "", 14f, Ui.MUTED)
        connect.addView(addressHint)
        errorText = Ui.text(this, "", 14f, Ui.DANGER).apply { visibility = View.GONE }
        connect.addView(errorText)
        root.addView(connect)

        // --- Mirror card --------------------------------------------------------
        val mirror = Ui.card(this)
        mirror.addView(Ui.text(this, "2 · MIRROR YOUR SCREEN", 12f, Ui.ACCENT, bold = true))
        mirrorStatus = Ui.text(this, "", 14f, Ui.MUTED).apply { setPadding(0, Ui.dp(this@MainActivity, 6), 0, 0) }
        mirror.addView(mirrorStatus)
        mirror.addView(Ui.text(this, "Fit to TV", 13f, Ui.MUTED).apply { setPadding(0, Ui.dp(this@MainActivity, 12), 0, 0) })
        fitPicker = Ui.Segmented(this, listOf("Smart fill", "Fit", "Fill (crop)")) { i ->
            CastService.state.fit = FitMode.values()[i]
            saveSettings()
        }
        mirror.addView(fitPicker)
        mirror.addView(Ui.text(this, "Picture quality", 13f, Ui.MUTED).apply { setPadding(0, Ui.dp(this@MainActivity, 12), 0, 0) })
        qualityPicker = Ui.Segmented(this, listOf("HD 720p · smoother", "Full HD 1080p")) { i ->
            CastService.settings = if (i == 0) CaptureSettings(1280, 720, 24, 70) else CaptureSettings(1920, 1080, 20, 75)
            saveSettings()
            if (CastService.state.mode == Mode.MIRROR) toast("Restart mirroring to apply the new quality")
        }
        mirror.addView(qualityPicker)
        mirrorButton = Ui.button(this, "Start mirroring") { toggleMirror() }
        mirror.addView(mirrorButton)
        mirror.addView(Ui.text(this, "Tip: turn your phone sideways for videos and games — it fills the TV edge to edge. Sound stays on the phone while mirroring.", 13f, Ui.MUTED).apply {
            setPadding(0, Ui.dp(this@MainActivity, 10), 0, 0)
        })
        root.addView(mirror)

        // --- Media card ---------------------------------------------------------
        val media = Ui.card(this)
        media.addView(Ui.text(this, "3 · CAST PHOTOS, VIDEOS & MUSIC", 12f, Ui.ACCENT, bold = true))
        mediaLabel = Ui.text(this, "Nothing chosen yet", 15f).apply { setPadding(0, Ui.dp(this@MainActivity, 6), 0, 0) }
        media.addView(mediaLabel)
        media.addView(Ui.button(this, "Choose photo, video or song") { pickMedia() })
        playPauseButton = Ui.button(this, "Pause", Ui.CARD_BORDER) { togglePause() }
        media.addView(playPauseButton)
        tvPlayerButton = Ui.button(this, "Play with the TV's own player (DLNA)", Ui.CARD_BORDER) { findTvs() }
        media.addView(tvPlayerButton)
        stopMediaButton = Ui.button(this, "Stop casting", Ui.DANGER) { stopMedia() }
        media.addView(stopMediaButton)
        media.addView(Ui.text(this, "The TV's own player gives full sound and 4K playback on Samsung, LG, Sony, Hisense, TCL, Philips and most Android TVs.", 13f, Ui.MUTED).apply {
            setPadding(0, Ui.dp(this@MainActivity, 10), 0, 0)
        })
        root.addView(media)

        // --- Receiver mode ------------------------------------------------------
        val other = Ui.card(this)
        other.addView(Ui.text(this, "THIS DEVICE IS THE SCREEN?", 12f, Ui.ACCENT, bold = true))
        other.addView(Ui.text(this, "Use a tablet, projector box or Android TV as the receiver.", 14f, Ui.MUTED).apply {
            setPadding(0, Ui.dp(this@MainActivity, 6), 0, 0)
        })
        other.addView(Ui.button(this, "Open receiver mode", Ui.CARD_BORDER) {
            startActivity(Intent(this, TvActivity::class.java))
        })
        root.addView(other)

        return ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            isFillViewport = true
            fitsSystemWindows = true // keeps content clear of the status/navigation bars (Android 15 edge-to-edge)
            addView(root)
        }
    }

    private fun refresh() {
        val state = CastService.state
        val addr = CastService.tvAddress()
        if (addr != null) {
            addressText.text = addr.removePrefix("http://")
            addressHint.text = "Type this in your TV's web browser (Samsung Internet, LG Web Browser, Chrome…) — or open Smart Cast on an Android TV / Google TV and it connects by itself. Phone and TV must be on the same Wi-Fi (your phone's hotspot works too). Tap the address to copy it."
        } else if (CastService.running && NetUtil.localIpv4() == null) {
            addressText.text = "Not on Wi-Fi"
            addressHint.text = "Connect your phone to the same Wi-Fi as your TV, or turn on your phone's hotspot and join it from the TV."
        } else {
            addressText.text = "Starting…"
            addressHint.text = ""
        }
        val err = CastService.lastError
        errorText.text = err ?: ""
        errorText.visibility = if (err == null) View.GONE else View.VISIBLE

        val mirroring = state.mode == Mode.MIRROR
        mirrorButton.text = if (mirroring) "Stop mirroring" else "Start mirroring"
        mirrorButton.background = Ui.pressable(this, if (mirroring) Ui.DANGER else Ui.ACCENT)
        mirrorStatus.text = if (mirroring) "● Live on your TV" else "Shows everything on your phone, shaped for a widescreen TV."
        mirrorStatus.setTextColor(if (mirroring) Ui.ACCENT else Ui.MUTED)
        fitPicker.select(state.fit.ordinal)
        qualityPicker.select(if (CastService.settings.outWidth >= 1920) 1 else 0)

        val m = state.media
        mediaLabel.text = if (m == null) "Nothing chosen yet" else "${kindIcon(m.kind)} ${m.name}" +
            (dlnaTarget?.let { "\nPlaying on ${it.name}" } ?: "")
        val playable = m != null && (m.kind == "video" || m.kind == "audio")
        playPauseButton.visibility = if (playable) View.VISIBLE else View.GONE
        playPauseButton.text = if (state.paused) "▶ Play" else "❚❚ Pause"
        tvPlayerButton.visibility = if (m != null) View.VISIBLE else View.GONE
        tvPlayerButton.text = if (searching) "Looking for TVs…" else "Play with the TV's own player (DLNA)"
        stopMediaButton.visibility = if (m != null) View.VISIBLE else View.GONE
    }

    private fun kindIcon(kind: String) = when (kind) {
        "video" -> "🎬"; "audio" -> "🎵"; "image" -> "🖼"; else -> "📄"
    }

    // ---------------------------------------------------------------- mirroring

    private fun toggleMirror() {
        if (CastService.state.mode == Mode.MIRROR) {
            CastService.start(this, CastService.ACTION_STOP_MIRROR)
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
        } catch (e: Exception) {
            toast("This phone doesn't allow screen capture")
        }
    }

    // ---------------------------------------------------------------- media

    private fun pickMedia() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*", "audio/*"))
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_PICK)
        } catch (e: Exception) {
            toast("No file picker found on this phone")
        }
    }

    private fun togglePause() {
        val state = CastService.state
        state.paused = !state.paused
        val target = dlnaTarget
        if (target != null) {
            val pause = state.paused
            thread { runCatching { if (pause) Dlna.pause(target) else Dlna.resume(target) } }
        }
        refresh()
    }

    private fun stopMedia() {
        val target = dlnaTarget
        dlnaTarget = null
        if (target != null) thread { runCatching { Dlna.stop(target) } }
        val state = CastService.state
        state.setMedia(null)
        if (state.mode == Mode.MEDIA) state.mode = Mode.IDLE
        refresh()
    }

    private fun findTvs() {
        if (searching) return
        val local = NetUtil.localIpv4()
        if (local == null) {
            toast("Connect to Wi-Fi first")
            return
        }
        searching = true
        refresh()
        thread {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = wifi.createMulticastLock("smartcast-dlna").apply { setReferenceCounted(false) }
            val found = try {
                lock.acquire()
                Dlna.discover(local)
            } catch (e: Exception) {
                emptyList()
            } finally {
                runCatching { lock.release() }
            }
            runOnUiThread {
                searching = false
                refresh()
                if (isFinishing) return@runOnUiThread
                if (found.isEmpty()) {
                    AlertDialog.Builder(this)
                        .setTitle("No TV found")
                        .setMessage("Make sure the TV is on and on the same Wi-Fi. On some TVs you need to switch on \"Screen sharing\", \"DLNA\" or \"Media renderer\" in the network settings.\n\nYou can always use the TV's web browser instead: open ${CastService.tvAddress()?.removePrefix("http://") ?: "the address above"}.")
                        .setPositiveButton("OK", null)
                        .show()
                } else {
                    val names = found.map { r -> if (r.manufacturer.isBlank()) r.name else "${r.name}\n${r.manufacturer} ${r.model}".trim() }
                    AlertDialog.Builder(this)
                        .setTitle("Play on which TV?")
                        .setItems(names.toTypedArray()) { _, which -> playOn(found[which]) }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
        }
    }

    private fun playOn(r: Renderer) {
        val state = CastService.state
        val m = state.media ?: return
        val base = CastService.tvAddress() ?: return toast("Connect to Wi-Fi first")
        val url = base + (state.mediaPath() ?: return)
        toast("Sending to ${r.name}…")
        thread {
            val result = runCatching { Dlna.play(r, url, m.mime, m.name) }
            runOnUiThread {
                if (result.isSuccess) {
                    dlnaTarget = r
                    state.paused = false
                    toast("Playing on ${r.name}")
                } else {
                    toast("${r.name} didn't accept it: ${result.exceptionOrNull()?.message ?: "unknown error"}. Try the TV's web browser instead.")
                }
                refresh()
            }
        }
    }

    // ---------------------------------------------------------------- results

    @Deprecated("Uses the framework API so the app needs no extra libraries")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_CAPTURE -> {
                if (resultCode == RESULT_OK && data != null) {
                    val extras = Intent()
                        .putExtra(CastService.EXTRA_RESULT_CODE, resultCode)
                        .putExtra(CastService.EXTRA_RESULT_DATA, data)
                    CastService.start(this, CastService.ACTION_MIRROR, extras)
                } else {
                    toast("Mirroring needs permission to capture the screen")
                }
            }
            REQ_PICK -> {
                val uri = data?.data
                if (resultCode == RESULT_OK && uri != null) {
                    runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    try {
                        CastService.setMedia(this, uri)
                        dlnaTarget = null
                        if (CastService.state.mode == Mode.MIRROR) CastService.start(this, CastService.ACTION_STOP_MIRROR)
                        CastService.state.mode = Mode.MEDIA
                    } catch (e: Exception) {
                        toast("Couldn't open that file")
                    }
                    refresh()
                }
            }
        }
    }

    // ---------------------------------------------------------------- misc

    private fun copyAddress() {
        val addr = CastService.tvAddress() ?: return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("TV address", addr))
        toast("Address copied")
    }

    private fun saveSettings() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putInt("fit", CastService.state.fit.ordinal)
            .putBoolean("fullhd", CastService.settings.outWidth >= 1920)
            .apply()
    }

    private fun restoreSettings() {
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        CastService.state.fit = FitMode.values().getOrElse(p.getInt("fit", 0)) { FitMode.SMART }
        CastService.settings = if (p.getBoolean("fullhd", false)) CaptureSettings(1920, 1080, 20, 75) else CaptureSettings()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).also { it.setGravity(Gravity.BOTTOM, 0, Ui.dp(this, 48)) }.show()
    }
}
