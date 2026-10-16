package com.vikaranet.smartcast.core

import java.io.InputStream

enum class Mode { IDLE, MIRROR, MEDIA }

/**
 * How the picture is fitted to a 16:9 TV.
 * SMART – whole picture visible, empty space filled with a blurred copy (no black bars, nothing cut off)
 * FIT   – whole picture visible with black bars
 * FILL  – fills the whole TV, edges are cropped
 */
enum class FitMode { SMART, FIT, FILL }

class MediaInfo(
    val name: String,
    val mime: String,
    /** Size in bytes, or -1 when unknown. */
    val length: Long,
    val open: () -> InputStream,
) {
    val kind: String
        get() = when {
            mime.startsWith("video/") -> "video"
            mime.startsWith("audio/") -> "audio"
            mime.startsWith("image/") -> "image"
            else -> "file"
        }

    val extension: String
        get() = name.substringAfterLast('.', "").lowercase().takeIf { it.length in 1..5 && it.all(Char::isLetterOrDigit) }
            ?: when (mime) {
                "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/webp" -> "webp"; "image/gif" -> "gif"
                "video/mp4" -> "mp4"; "video/webm" -> "webm"; "video/x-matroska" -> "mkv"
                "audio/mpeg" -> "mp3"; "audio/mp4" -> "m4a"
                else -> "bin"
            }
}

/** Shared state the phone controls and every TV reads through /state. */
class CastState {
    @Volatile var mode: Mode = Mode.IDLE
    @Volatile var media: MediaInfo? = null
        private set
    @Volatile var fit: FitMode = FitMode.SMART
    @Volatile var paused: Boolean = false
    @Volatile var mediaVersion: Int = 0
        private set
    @Volatile var deviceName: String = "Phone"

    fun setMedia(info: MediaInfo?) {
        media = info
        mediaVersion++
        paused = false
    }

    /** Path TVs use to fetch the current media; the version and extension help DLNA renderers. */
    fun mediaPath(): String? = media?.let { "/media/v$mediaVersion.${it.extension}" }

    fun toJson(): String {
        val m = media
        return buildString {
            append("{\"mode\":\"").append(mode.name.lowercase()).append('"')
            append(",\"fit\":\"").append(fit.name.lowercase()).append('"')
            append(",\"paused\":").append(paused)
            append(",\"device\":").append(jsonString(deviceName))
            append(",\"mediaVersion\":").append(mediaVersion)
            if (m != null) {
                append(",\"media\":{\"name\":").append(jsonString(m.name))
                append(",\"kind\":\"").append(m.kind).append('"')
                append(",\"mime\":").append(jsonString(m.mime))
                append(",\"url\":").append(jsonString(mediaPath() ?: ""))
                append('}')
            }
            append('}')
        }
    }

    companion object {
        fun jsonString(s: String): String = buildString {
            append('"')
            for (c in s) {
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    '<' -> append("\\u003c")
                    else -> if (c < ' ') append(String.format("\\u%04x", c.code)) else append(c)
                }
            }
            append('"')
        }
    }
}
