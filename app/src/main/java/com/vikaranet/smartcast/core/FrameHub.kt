package com.vikaranet.smartcast.core

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Holds the most recent JPEG frame of the mirrored screen and lets any number of
 * TV viewers wait for the next one. Producers never block on slow viewers: each
 * viewer simply skips to whatever frame is newest when it is ready again.
 */
class FrameHub {
    private val lock = ReentrantLock()
    private val newFrame = lock.newCondition()
    private var frame: ByteArray? = null
    private var seq = 0L

    fun publish(jpeg: ByteArray) = lock.withLock {
        frame = jpeg
        seq++
        newFrame.signalAll()
    }

    fun latest(): ByteArray? = lock.withLock { frame }

    fun clear() = lock.withLock {
        frame = null
        newFrame.signalAll()
    }

    /**
     * Waits for a frame newer than [lastSeq] (use -1 for "any frame").
     * Returns (sequence, jpeg) or null if nothing new arrived within [timeoutMs].
     */
    fun awaitNext(lastSeq: Long, timeoutMs: Long): Pair<Long, ByteArray>? = lock.withLock {
        var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (frame == null || seq == lastSeq) {
            if (remaining <= 0L) return null
            remaining = newFrame.awaitNanos(remaining)
        }
        Pair(seq, frame!!)
    }
}
