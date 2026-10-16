package com.vikaranet.smartcast.core

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Tiny HTTP server that runs on the phone. Any TV on the same Wi-Fi (its web browser,
 * the Smart Cast TV app, or a DLNA renderer) talks to it:
 *
 *   /            receiver page (full-screen player for TV browsers)
 *   /state       JSON describing what the phone is casting
 *   /stream.mjpg live screen mirror as an MJPEG stream (works in virtually every browser)
 *   /frame.jpg   the latest mirror frame (fallback for browsers without MJPEG)
 *   /media/...   the photo / video / song chosen on the phone, with byte-range support
 */
class HttpServer(
    private val hub: FrameHub,
    private val state: CastState,
    private val page: () -> String,
    private val preferredPort: Int = 8080,
) {
    private var server: ServerSocket? = null
    private var pool: ExecutorService? = null
    @Volatile private var running = false

    val port: Int get() = server?.localPort ?: -1
    val isRunning: Boolean get() = running

    /** Starts on [preferredPort], or the next free port up to +20, or any free port. */
    @Synchronized
    fun start(): Int {
        if (running) return port
        val socket = bindFirstFree()
        server = socket
        pool = Executors.newCachedThreadPool { r -> Thread(r, "smartcast-http").apply { isDaemon = true } }
        running = true
        thread(name = "smartcast-accept", isDaemon = true) {
            while (running) {
                try {
                    val client = socket.accept()
                    pool?.execute { serve(client) }
                } catch (e: IOException) {
                    if (!running) break
                }
            }
        }
        return socket.localPort
    }

    @Synchronized
    fun stop() {
        running = false
        try { server?.close() } catch (_: IOException) {}
        server = null
        pool?.shutdownNow()
        pool = null
        hub.clear() // wakes any stream loops so they can exit
    }

    private fun bindFirstFree(): ServerSocket {
        for (p in (preferredPort..preferredPort + 20) + 0) {
            try {
                return ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(p))
                }
            } catch (_: IOException) {
            }
        }
        throw IOException("No free port")
    }

    private class Request(val method: String, val path: String, val headers: Map<String, String>)

    private fun serve(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = 15_000
                s.tcpNoDelay = true
                val input = BufferedInputStream(s.getInputStream())
                val req = readRequest(input) ?: return
                s.soTimeout = 0
                val out = s.getOutputStream()
                route(req, out)
                out.flush()
            } catch (_: SocketException) {
                // TV closed the connection - normal when it switches content
            } catch (_: IOException) {
            } catch (_: InterruptedException) {
            }
        }
    }

    private fun readRequest(input: InputStream): Request? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            if (headers.size > 100) break
        }
        val path = parts[1].substringBefore('?').substringBefore('#')
        return Request(parts[0].uppercase(), path, headers)
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 8192) throw IOException("Header line too long")
        }
        return sb.toString()
    }

    private fun route(req: Request, out: OutputStream) {
        if (req.method != "GET" && req.method != "HEAD") {
            if (req.method == "OPTIONS") {
                writeHead(out, 204, "No Content", mapOf("Allow" to "GET, HEAD, OPTIONS", "Content-Length" to "0"))
            } else {
                sendText(out, req, 405, "Method Not Allowed", "text/plain", "Method not allowed")
            }
            return
        }
        when {
            req.path == "/" || req.path == "/tv" || req.path == "/index.html" ->
                sendText(out, req, 200, "OK", "text/html; charset=utf-8", page())
            req.path == "/state" ->
                sendText(out, req, 200, "OK", "application/json; charset=utf-8", state.toJson())
            req.path == "/stream.mjpg" -> streamMjpeg(out, req)
            req.path == "/frame.jpg" -> {
                val f = hub.latest()
                if (f == null) sendText(out, req, 503, "Service Unavailable", "text/plain", "No frame yet")
                else sendBytes(out, req, "image/jpeg", f)
            }
            req.path.startsWith("/media") -> sendMedia(out, req)
            req.path == "/favicon.ico" -> writeHead(out, 204, "No Content", mapOf("Content-Length" to "0"))
            else -> sendText(out, req, 404, "Not Found", "text/plain", "Not found")
        }
    }

    private fun baseHeaders(): MutableMap<String, String> = linkedMapOf(
        "Server" to "SmartCast/1.0",
        "Connection" to "close",
        "Access-Control-Allow-Origin" to "*",
        "Cache-Control" to "no-store, no-cache, must-revalidate",
        "Pragma" to "no-cache",
    )

    private fun writeHead(out: OutputStream, code: Int, reason: String, headers: Map<String, String>) {
        val sb = StringBuilder("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
        val all = baseHeaders().apply { putAll(headers) }
        for ((k, v) in all) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    private fun sendText(out: OutputStream, req: Request, code: Int, reason: String, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        writeHead(out, code, reason, mapOf("Content-Type" to type, "Content-Length" to bytes.size.toString()))
        if (req.method != "HEAD") out.write(bytes)
    }

    private fun sendBytes(out: OutputStream, req: Request, type: String, bytes: ByteArray) {
        writeHead(out, 200, "OK", mapOf("Content-Type" to type, "Content-Length" to bytes.size.toString()))
        if (req.method != "HEAD") out.write(bytes)
    }

    private fun streamMjpeg(out: OutputStream, req: Request) {
        if (state.mode != Mode.MIRROR) {
            sendText(out, req, 503, "Service Unavailable", "text/plain", "Not mirroring")
            return
        }
        writeHead(out, 200, "OK", mapOf("Content-Type" to "multipart/x-mixed-replace; boundary=$BOUNDARY"))
        if (req.method == "HEAD") return
        var last = -1L
        while (running && state.mode == Mode.MIRROR) {
            val next = hub.awaitNext(last, 1_000) ?: continue
            last = next.first
            val jpeg = next.second
            out.write(
                ("--$BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n")
                    .toByteArray(Charsets.ISO_8859_1)
            )
            out.write(jpeg)
            out.write(CRLF)
            out.flush()
        }
    }

    private fun sendMedia(out: OutputStream, req: Request) {
        val media = state.media
        if (media == null) {
            sendText(out, req, 404, "Not Found", "text/plain", "Nothing is being cast")
            return
        }
        val length = media.length
        val headers = linkedMapOf(
            "Content-Type" to media.mime,
            "transferMode.dlna.org" to if (media.kind == "image") "Interactive" else "Streaming",
            "contentFeatures.dlna.org" to DLNA_FEATURES,
        )
        if (length < 0) {
            // Unknown size: stream it once, no seeking.
            writeHead(out, 200, "OK", headers)
            if (req.method != "HEAD") media.open().use { copy(it, out, Long.MAX_VALUE) }
            return
        }
        headers["Accept-Ranges"] = "bytes"
        val range = parseRange(req.headers["range"], length)
        if (range == null && req.headers["range"] != null) {
            headers["Content-Range"] = "bytes */$length"
            headers["Content-Length"] = "0"
            writeHead(out, 416, "Range Not Satisfiable", headers)
            return
        }
        val start = range?.first ?: 0L
        val end = range?.second ?: (length - 1)
        val count = end - start + 1
        headers["Content-Length"] = count.toString()
        if (range != null) {
            headers["Content-Range"] = "bytes $start-$end/$length"
            writeHead(out, 206, "Partial Content", headers)
        } else {
            writeHead(out, 200, "OK", headers)
        }
        if (req.method == "HEAD") return
        media.open().use { input ->
            skipFully(input, start)
            copy(input, out, count)
        }
    }

    companion object {
        private const val BOUNDARY = "smartcastframe"
        private val CRLF = "\r\n".toByteArray(Charsets.ISO_8859_1)
        const val DLNA_FEATURES = "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"

        /** Parses a single "bytes=" range. Returns inclusive (start, end) or null if absent/invalid. */
        fun parseRange(header: String?, length: Long): Pair<Long, Long>? {
            if (header == null || length <= 0) return null
            val spec = header.trim()
            if (!spec.startsWith("bytes=", ignoreCase = true)) return null
            val first = spec.substring(6).split(',')[0].trim()
            val dash = first.indexOf('-')
            if (dash < 0) return null
            val a = first.substring(0, dash).trim()
            val b = first.substring(dash + 1).trim()
            return try {
                if (a.isEmpty()) {
                    val suffix = b.toLong()
                    if (suffix <= 0) null else Pair(maxOf(0L, length - suffix), length - 1)
                } else {
                    val start = a.toLong()
                    val end = if (b.isEmpty()) length - 1 else minOf(b.toLong(), length - 1)
                    if (start < 0 || start >= length || end < start) null else Pair(start, end)
                }
            } catch (_: NumberFormatException) {
                null
            }
        }

        private fun skipFully(input: InputStream, n: Long) {
            var left = n
            val scratch = ByteArray(64 * 1024)
            while (left > 0) {
                val skipped = input.skip(left)
                if (skipped > 0) {
                    left -= skipped
                } else {
                    val r = input.read(scratch, 0, minOf(scratch.size.toLong(), left).toInt())
                    if (r < 0) throw IOException("Unexpected end of file")
                    left -= r
                }
            }
        }

        private fun copy(input: InputStream, out: OutputStream, max: Long) {
            val buf = ByteArray(64 * 1024)
            var left = max
            while (left > 0) {
                val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (r < 0) break
                out.write(buf, 0, r)
                left -= r
            }
        }
    }
}
