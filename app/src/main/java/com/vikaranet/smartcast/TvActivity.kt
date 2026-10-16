package com.vikaranet.smartcast

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Receiver mode for Android TV, Google TV, Fire TV, TV boxes, projectors and tablets.
 * Finds phones running Smart Cast on the Wi-Fi automatically and shows them full screen.
 */
class TvActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var overlay: View
    private lateinit var status: TextView
    private lateinit var phoneList: LinearLayout
    private lateinit var manual: EditText

    private val main = Handler(Looper.getMainLooper())
    private var nsd: NsdManager? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private val phones = LinkedHashMap<String, String>() // url -> name
    private var connectedUrl: String? = null
    private var autoConnect = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())
        hideSystemBars()
        nsd = getSystemService(NSD_SERVICE) as NsdManager
        val last = getSharedPreferences("smartcast", MODE_PRIVATE).getString("lastPhone", null)
        if (last != null) manual.setText(last.removePrefix("http://"))
    }

    override fun onStart() {
        super.onStart()
        startDiscovery()
    }

    override fun onStop() {
        stopDiscovery()
        super.onStop()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        web.destroy()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            isFocusable = true
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) onConnectionLost()
                }
            }
            visibility = View.GONE
        }
        root.addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val panel = Ui.card(this).apply {
            val p = Ui.dp(this@TvActivity, 28)
            setPadding(p, p, p, p)
        }
        panel.addView(Ui.text(this, "Smart Cast", 34f, bold = true))
        panel.addView(Ui.text(this, "Receiver", 18f, Ui.ACCENT, bold = true))
        status = Ui.text(this, "Looking for phones on your Wi-Fi…", 18f, Ui.MUTED).apply {
            setPadding(0, Ui.dp(this@TvActivity, 16), 0, Ui.dp(this@TvActivity, 6))
        }
        panel.addView(status)
        phoneList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(phoneList)

        panel.addView(Ui.text(this, "Or type the address shown on the phone:", 15f, Ui.MUTED).apply {
            setPadding(0, Ui.dp(this@TvActivity, 18), 0, 0)
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        manual = EditText(this).apply {
            hint = "192.168.1.5:8080"
            setHintTextColor(Ui.MUTED)
            setTextColor(Ui.TEXT)
            textSize = 20f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> connectManual(); true }
        }
        row.addView(manual, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val go = Ui.button(this, "Connect") { connectManual() }
        go.layoutParams = LinearLayout.LayoutParams(Ui.dp(this, 160), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = Ui.dp(this@TvActivity, 12)
        }
        row.addView(go)
        panel.addView(row)
        panel.addView(Ui.text(this, "On your phone: open Smart Cast, then tap Start mirroring or choose a photo or video.", 15f, Ui.MUTED).apply {
            setPadding(0, Ui.dp(this@TvActivity, 18), 0, 0)
        })

        val panelWidth = minOf(Ui.dp(this, 620), resources.displayMetrics.widthPixels - Ui.dp(this, 32))
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(FrameLayout(this@TvActivity).apply {
                val pad = Ui.dp(this@TvActivity, 16)
                setPadding(0, pad, 0, pad)
                addView(panel, FrameLayout.LayoutParams(panelWidth, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            })
        }
        overlay = FrameLayout(this).apply {
            setBackgroundColor(Ui.BG)
            addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        root.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        return root
    }

    // ----------------------------------------------------------------- connect

    private fun connect(baseUrl: String) {
        connectedUrl = baseUrl
        getSharedPreferences("smartcast", MODE_PRIVATE).edit().putString("lastPhone", baseUrl).apply()
        web.visibility = View.VISIBLE
        overlay.visibility = View.GONE
        web.loadUrl("$baseUrl/tv?app=1")
        web.requestFocus()
    }

    private fun connectManual() {
        var text = manual.text.toString().trim().removePrefix("http://").removeSuffix("/")
        if (text.isEmpty()) return
        if (!text.contains(':')) text += ":8080"
        autoConnect = false
        connect("http://$text")
    }

    private fun onConnectionLost() {
        val url = connectedUrl ?: return
        showPicker("Lost the phone. Reconnecting…")
        // Try again shortly: the phone may have just locked or switched networks.
        main.postDelayed({
            if (connectedUrl == url && overlay.visibility == View.VISIBLE) connect(url)
        }, 4000)
    }

    private fun showPicker(message: String) {
        overlay.visibility = View.VISIBLE
        web.visibility = View.GONE
        web.loadUrl("about:blank")
        status.text = message
        (phoneList.getChildAt(0) ?: manual).requestFocus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && overlay.visibility != View.VISIBLE) {
            connectedUrl = null
            autoConnect = false
            showPicker(if (phones.isEmpty()) "Looking for phones on your Wi-Fi…" else "Choose a phone")
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ----------------------------------------------------------------- discovery

    private fun startDiscovery() {
        val manager = nsd ?: return
        if (discovery != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                main.post { status.text = "Automatic search isn't available — type the phone's address below." }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                main.post { resolveQueue.addLast(info); resolveNext() }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                main.post {
                    val gone = phones.entries.firstOrNull { it.value == info.serviceName }?.key
                    if (gone != null) { phones.remove(gone); renderPhones() }
                }
            }
        }
        try {
            manager.discoverServices(CastService.NSD_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
            discovery = l
        } catch (_: Exception) {
            status.text = "Automatic search isn't available — type the phone's address below."
        }
    }

    private fun stopDiscovery() {
        val l = discovery ?: return
        discovery = null
        runCatching { nsd?.stopServiceDiscovery(l) }
        resolveQueue.clear()
        resolving = false
    }

    /** NsdManager can only resolve one service at a time on older Android versions. */
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving) return
        val info = resolveQueue.removeFirstOrNull() ?: return
        val manager = nsd ?: return
        resolving = true
        try {
            manager.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    main.post { resolving = false; resolveNext() }
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val host = serviceInfo.host?.hostAddress
                    val port = serviceInfo.port
                    main.post {
                        resolving = false
                        if (host != null && !host.contains(':')) { // IPv4 only, works on every TV browser engine
                            val url = "http://$host:$port"
                            if (!phones.containsKey(url)) {
                                phones[url] = serviceInfo.serviceName
                                renderPhones()
                                if (autoConnect && connectedUrl == null) connect(url)
                            }
                        }
                        resolveNext()
                    }
                }
            })
        } catch (_: Exception) {
            resolving = false
        }
    }

    private fun renderPhones() {
        phoneList.removeAllViews()
        for ((url, name) in phones) {
            val label = name.removePrefix("Smart Cast - ")
            phoneList.addView(Ui.button(this, "📱  $label") { autoConnect = false; connect(url) })
        }
        if (overlay.visibility == View.VISIBLE) {
            status.text = if (phones.isEmpty()) "Looking for phones on your Wi-Fi…" else "Choose a phone"
        }
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }
}
