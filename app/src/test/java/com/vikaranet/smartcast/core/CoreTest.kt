package com.vikaranet.smartcast.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

class CoreTest {

    @Test
    fun rangeParsing() {
        assertEquals(Pair(0L, 99L), HttpServer.parseRange("bytes=0-99", 1000))
        assertEquals(Pair(500L, 999L), HttpServer.parseRange("bytes=500-", 1000))
        assertEquals(Pair(900L, 999L), HttpServer.parseRange("bytes=-100", 1000))
        assertEquals(Pair(900L, 999L), HttpServer.parseRange("bytes=900-5000", 1000))
        assertNull(HttpServer.parseRange("bytes=1000-", 1000))
        assertNull(HttpServer.parseRange("junk", 1000))
    }

    @Test
    fun stateJsonEscapes() {
        val s = CastState().apply { deviceName = "Victor's \"Phone\"" }
        val json = s.toJson()
        assertTrue(json, json.contains("\"mode\":\"idle\""))
        assertTrue(json, json.contains("\\\"Phone\\\""))
    }

    @Test
    fun servesMediaWithRanges() {
        val data = ByteArray(50_000) { (it % 251).toByte() }
        val state = CastState()
        state.setMedia(MediaInfo("clip.mp4", "video/mp4", data.size.toLong()) { ByteArrayInputStream(data) })
        state.mode = Mode.MEDIA
        val server = HttpServer(FrameHub(), state, { "<html></html>" }, 18181)
        val port = server.start()
        try {
            val c = URL("http://127.0.0.1:$port${state.mediaPath()}").openConnection() as HttpURLConnection
            c.setRequestProperty("Range", "bytes=100-199")
            assertEquals(206, c.responseCode)
            assertArrayEquals(data.copyOfRange(100, 200), c.inputStream.readBytes())
        } finally {
            server.stop()
        }
    }

    @Test
    fun parsesDlnaDescription() {
        val xml = """<?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0"><device>
            <friendlyName>Living Room TV</friendlyName><manufacturer>LG Electronics</manufacturer>
            <serviceList><service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
            <controlURL>/AVTransport/control</controlURL></service></serviceList></device></root>"""
        val r = Dlna.parseDescription(xml, "http://192.168.1.30:1528/desc.xml")!!
        assertEquals("Living Room TV", r.name)
        assertEquals("http://192.168.1.30:1528/AVTransport/control", r.controlUrl)
    }

    @Test
    fun servesSlidesAndRemoteControl() {
        val state = CastState()
        state.setSlides(object : SlideSource {
            override val name = "Deck"
            override val count = 3
            override fun slideJpeg(index: Int) = byteArrayOf(index.toByte(), 9, 9)
        })
        state.mode = Mode.PRESENT
        state.onControl = { if (it == "next") state.slideIndex++ }
        val server = HttpServer(FrameHub(), state, { "" }, 18182)
        val port = server.start()
        try {
            val slide = URL("http://127.0.0.1:$port${state.slidePath(1)}").readBytes()
            assertArrayEquals(byteArrayOf(1, 9, 9), slide)
            URL("http://127.0.0.1:$port/control/next").readBytes()
            assertEquals(1, state.slideIndex)
            val stale = (URL("http://127.0.0.1:$port/slide/999-0.jpg").openConnection() as HttpURLConnection).responseCode
            assertEquals(404, stale)
        } finally {
            server.stop()
        }
    }
}
