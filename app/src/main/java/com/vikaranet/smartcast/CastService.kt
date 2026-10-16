package com.vikaranet.smartcast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.OpenableColumns
import com.vikaranet.smartcast.core.CastState
import com.vikaranet.smartcast.core.FrameHub
import com.vikaranet.smartcast.core.HttpServer
import com.vikaranet.smartcast.core.MediaInfo
import com.vikaranet.smartcast.core.Mode
import com.vikaranet.smartcast.core.NetUtil

/**
 * Keeps the phone's cast server running in the background (also while the screen is
 * being mirrored), and announces the phone to Smart Cast TV apps on the network.
 */
class CastService : Service() {

    companion object {
        const val ACTION_START = "com.vikaranet.smartcast.START"
        const val ACTION_MIRROR = "com.vikaranet.smartcast.MIRROR"
        const val ACTION_STOP_MIRROR = "com.vikaranet.smartcast.STOP_MIRROR"
        const val ACTION_STOP = "com.vikaranet.smartcast.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val NSD_TYPE = "_smartcast._tcp."
        private const val CHANNEL = "cast"
        private const val NOTIFICATION_ID = 7

        val hub = FrameHub()
        val state = CastState()
        @Volatile var settings = CaptureSettings()
        @Volatile var port: Int = -1
            private set
        @Volatile var running = false
            private set
        @Volatile var lastError: String? = null

        /** The running screen capture, for the live frame counter on the phone. */
        @Volatile var capture: ScreenCapturer? = null
            private set

        /** UI callback; always invoked on the main thread. */
        var listener: (() -> Unit)? = null
        private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

        /** Asks the open screen to refresh (safe from any thread). */
        fun notifyChanged() {
            mainHandler.post { listener?.invoke() }
        }

        fun tvAddress(): String? {
            if (port <= 0) return null
            val ip = NetUtil.localIpv4()?.hostAddress ?: return null
            return "http://$ip:$port"
        }

        fun start(ctx: Context, action: String = ACTION_START, extras: Intent? = null) {
            val i = Intent(ctx, CastService::class.java).setAction(action)
            extras?.let { i.putExtras(it) }
            ctx.startForegroundService(i)
        }

        /** Makes [uri] the photo/video/song every TV shows. */
        fun setMedia(ctx: Context, uri: Uri) {
            val cr = ctx.applicationContext.contentResolver
            var name = "media"
            var size = -1L
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
            val mime = cr.getType(uri) ?: guessMime(name)
            state.setMedia(MediaInfo(name, mime, size) {
                cr.openInputStream(uri) ?: throw java.io.FileNotFoundException(uri.toString())
            })
            if (state.mode != Mode.MIRROR) state.mode = Mode.MEDIA
        }

        init {
            // TV remote buttons (sent from the TV page) drive the presentation.
            state.onControl = { cmd -> Presenter.handle(cmd) }
        }

        private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "webp" -> "image/webp"; "gif" -> "image/gif"
            "mp4", "m4v" -> "video/mp4"; "webm" -> "video/webm"; "mkv" -> "video/x-matroska"; "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"; "m4a", "aac" -> "audio/mp4"; "wav" -> "audio/wav"
            else -> "application/octet-stream"
        }
    }

    private var server: HttpServer? = null
    private var capturer: ScreenCapturer? = null
    private var nsdRegistration: NsdManager.RegistrationListener? = null
    private val main by lazy { android.os.Handler(mainLooper) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        state.deviceName = Build.MODEL ?: "Phone"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Casting", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows while Smart Cast is sending to your TV"
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_MIRROR -> startMirroring(intent)
            ACTION_STOP_MIRROR -> {
                stopMirroring()
                goForeground(mirroring = false)
            }
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            else -> {
                goForeground(mirroring = capturer != null)
                ensureServer()
            }
        }
        notifyUi()
        return START_NOT_STICKY
    }

    private fun ensureServer() {
        if (server?.isRunning == true) return
        val s = HttpServer(hub, state, { receiverPage() })
        try {
            port = s.start()
            server = s
            running = true
            lastError = null
            registerNsd(port)
        } catch (e: Exception) {
            lastError = "Couldn't start the cast server: ${e.message}"
        }
    }

    private var pageCache: String? = null
    private fun receiverPage(): String =
        pageCache ?: assets.open("receiver.html").bufferedReader().use { it.readText() }.also { pageCache = it }

    private fun startMirroring(intent: Intent) {
        // On Android 10+ the foreground service must be of type "mediaProjection" before capture starts.
        goForeground(mirroring = true)
        ensureServer()
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (data == null) {
            lastError = "Screen capture permission was not given"
            goForeground(mirroring = false)
            return
        }
        stopMirroring()
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = mpm.getMediaProjection(code, data)
                ?: throw IllegalStateException("Screen capture was refused")
            val c = ScreenCapturer(
                context = this,
                projection = projection,
                hub = hub,
                fit = { state.fit },
                settings = { settings },
                onStopped = {
                    main.post {
                        if (capturer != null) {
                            capturer = null
                            capture = null
                            state.mode = state.restingMode()
                            goForeground(mirroring = false)
                            notifyUi()
                        }
                    }
                },
            )
            c.start()
            capturer = c
            capture = c
            state.mode = Mode.MIRROR
            lastError = null
        } catch (e: Exception) {
            lastError = "Couldn't start mirroring: ${e.message}"
            capturer = null
            state.mode = state.restingMode()
            goForeground(mirroring = false)
        }
    }

    private fun stopMirroring() {
        val c = capturer ?: return
        capturer = null
        capture = null
        c.stop()
        hub.clear()
        state.mode = state.restingMode()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        capturer?.onDisplayChanged()
    }

    private fun goForeground(mirroring: Boolean) {
        val n = buildNotification(mirroring)
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val type = if (mirroring) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                startForeground(NOTIFICATION_ID, n, type)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
        } catch (e: Exception) {
            lastError = "Android blocked the background service: ${e.message}"
        }
    }

    private fun buildNotification(mirroring: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CastService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val addr = tvAddress()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_cast)
            .setContentTitle(if (mirroring) "Mirroring your screen to TV" else "Smart Cast is ready")
            .setContentText(addr?.let { "On the TV open $it" } ?: "Connect to Wi-Fi to cast")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Stop", stop).build())
            .build()
    }

    private fun registerNsd(serverPort: Int) {
        unregisterNsd()
        val nsd = getSystemService(Context.NSD_SERVICE) as NsdManager
        val info = NsdServiceInfo().apply {
            serviceName = "Smart Cast - ${Build.MODEL}"
            serviceType = NSD_TYPE
            setPort(serverPort)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            nsdRegistration = l
        } catch (_: Exception) {
        }
    }

    private fun unregisterNsd() {
        val l = nsdRegistration ?: return
        nsdRegistration = null
        runCatching { (getSystemService(Context.NSD_SERVICE) as NsdManager).unregisterService(l) }
    }

    private fun shutdown() {
        stopMirroring()
        Presenter.end()
        unregisterNsd()
        server?.stop()
        server = null
        port = -1
        running = false
        state.mode = Mode.IDLE
        state.setMedia(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        notifyUi()
    }

    override fun onDestroy() {
        stopMirroring()
        unregisterNsd()
        server?.stop()
        server = null
        port = -1
        running = false
        notifyUi()
        super.onDestroy()
    }

    private fun notifyUi() {
        main.post { listener?.invoke() }
    }
}
