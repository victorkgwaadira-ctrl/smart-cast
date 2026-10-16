package com.vikaranet.smartcast.core

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

/** A smart TV (or other DLNA/UPnP media renderer) that can play a URL. */
data class Renderer(
    val name: String,
    val manufacturer: String,
    val model: String,
    val location: String,
    val controlUrl: String,
    val serviceType: String,
)

/**
 * DLNA / UPnP "Play To" — the casting standard built into Samsung, LG, Sony, Philips,
 * Panasonic, Hisense, TCL and many Android TVs. We tell the TV a URL on the phone's
 * server and the TV plays it natively, full screen, with its own decoder and sound.
 */
object Dlna {
    private const val SSDP_ADDR = "239.255.255.250"
    private const val SSDP_PORT = 1900

    /** Finds renderers on the local network. [local] should be the phone's Wi-Fi address. */
    fun discover(local: InetAddress?, timeoutMs: Int = 3500): List<Renderer> {
        val locations = LinkedHashSet<String>()
        DatagramSocket(null).use { socket ->
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(local, 0))
            socket.soTimeout = 400
            val group = InetAddress.getByName(SSDP_ADDR)
            val targets = listOf(
                "urn:schemas-upnp-org:device:MediaRenderer:1",
                "urn:schemas-upnp-org:service:AVTransport:1",
            )
            fun search() {
                for (st in targets) {
                    val msg = "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: $SSDP_ADDR:$SSDP_PORT\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 2\r\n" +
                        "ST: $st\r\n" +
                        "USER-AGENT: Android UPnP/1.1 SmartCast/1.0\r\n\r\n"
                    val bytes = msg.toByteArray(Charsets.US_ASCII)
                    socket.send(DatagramPacket(bytes, bytes.size, group, SSDP_PORT))
                }
            }
            search()
            val deadline = System.currentTimeMillis() + timeoutMs
            var resent = false
            val buf = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                if (!resent && System.currentTimeMillis() > deadline - timeoutMs / 2) {
                    search() // UDP is lossy; ask twice
                    resent = true
                }
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val text = String(packet.data, 0, packet.length, Charsets.US_ASCII)
                    parseLocation(text)?.let { locations.add(it) }
                } catch (_: SocketTimeoutException) {
                }
            }
        }
        val result = ArrayList<Renderer>()
        for (loc in locations) {
            try {
                val xml = httpGet(loc)
                parseDescription(xml, loc)?.let { r ->
                    if (result.none { it.controlUrl == r.controlUrl }) result.add(r)
                }
            } catch (_: Exception) {
            }
        }
        return result.sortedBy { it.name.lowercase() }
    }

    fun parseLocation(ssdpResponse: String): String? =
        ssdpResponse.lineSequence()
            .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

    /** Reads a UPnP device description and finds its AVTransport control URL. */
    fun parseDescription(xml: String, location: String): Renderer? {
        val factory = DocumentBuilderFactory.newInstance()
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.isExpandEntityReferences = false }
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        val root = doc.documentElement
        val base = childText(root, "URLBase")?.takeIf { it.isNotBlank() } ?: location
        val services = root.getElementsByTagName("service")
        for (i in 0 until services.length) {
            val svc = services.item(i) as? Element ?: continue
            val type = childText(svc, "serviceType") ?: continue
            if (!type.contains(":service:AVTransport:")) continue
            val control = childText(svc, "controlURL") ?: continue
            val device = svc.parentNode?.parentNode as? Element ?: root
            val controlUrl = URL(URL(base), control.trim()).toString()
            return Renderer(
                name = childText(device, "friendlyName") ?: firstText(root, "friendlyName") ?: "TV",
                manufacturer = childText(device, "manufacturer") ?: "",
                model = childText(device, "modelName") ?: "",
                location = location,
                controlUrl = controlUrl,
                serviceType = type.trim(),
            )
        }
        return null
    }

    /** Stops whatever is playing, loads [url] and starts playback. Throws on failure. */
    fun play(r: Renderer, url: String, mime: String, title: String, stopFirst: Boolean = true) {
        // Stopping first helps TVs switch between videos; skip it for slides to avoid a black flash.
        if (stopFirst) runCatching { action(r, "Stop", "") }
        val meta = didl(url, mime, title)
        action(
            r, "SetAVTransportURI",
            "<CurrentURI>${xmlEscape(url)}</CurrentURI><CurrentURIMetaData>${xmlEscape(meta)}</CurrentURIMetaData>"
        )
        try {
            action(r, "Play", "<Speed>1</Speed>")
        } catch (e: IOException) {
            // Some TVs start automatically and reject an early Play; give them a moment and retry once.
            Thread.sleep(800)
            action(r, "Play", "<Speed>1</Speed>")
        }
    }

    fun pause(r: Renderer) = action(r, "Pause", "")
    fun resume(r: Renderer) = action(r, "Play", "<Speed>1</Speed>")
    fun stop(r: Renderer) = action(r, "Stop", "")

    fun didl(url: String, mime: String, title: String): String {
        val upnpClass = when {
            mime.startsWith("image/") -> "object.item.imageItem.photo"
            mime.startsWith("audio/") -> "object.item.audioItem.musicTrack"
            else -> "object.item.videoItem"
        }
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
            "<dc:title>${xmlEscape(title)}</dc:title>" +
            "<upnp:class>$upnpClass</upnp:class>" +
            "<res protocolInfo=\"http-get:*:${xmlEscape(mime)}:${HttpServer.DLNA_FEATURES}\">${xmlEscape(url)}</res>" +
            "</item></DIDL-Lite>"
    }

    fun soapEnvelope(serviceType: String, action: String, args: String): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
            "<u:$action xmlns:u=\"$serviceType\"><InstanceID>0</InstanceID>$args</u:$action>" +
            "</s:Body></s:Envelope>"

    private fun action(r: Renderer, action: String, args: String) {
        val body = soapEnvelope(r.serviceType, action, args).toByteArray(Charsets.UTF_8)
        val conn = URL(r.controlUrl).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 4000
            conn.readTimeout = 8000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            conn.setRequestProperty("SOAPACTION", "\"${r.serviceType}#$action\"")
            conn.setRequestProperty("Connection", "close")
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull().orEmpty()
                val upnpCode = Regex("<errorCode>(\\d+)</errorCode>").find(err)?.groupValues?.get(1)
                throw IOException("TV refused $action (HTTP $code${upnpCode?.let { ", UPnP error $it" } ?: ""})")
            }
            runCatching { conn.inputStream.use { it.readBytes() } }
        } finally {
            conn.disconnect()
        }
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 2500
            conn.readTimeout = 3000
            return conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    private fun childText(parent: Element, tag: String): String? {
        val kids = parent.childNodes
        for (i in 0 until kids.length) {
            val n = kids.item(i)
            if (n is Element && (n.tagName == tag || n.tagName.endsWith(":$tag"))) return n.textContent?.trim()
        }
        return null
    }

    private fun firstText(root: Element, tag: String): String? {
        val list = root.getElementsByTagName(tag)
        return if (list.length > 0) list.item(0).textContent?.trim() else null
    }

    fun xmlEscape(s: String): String = buildString(s.length + 16) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(c)
        }
    }
}
