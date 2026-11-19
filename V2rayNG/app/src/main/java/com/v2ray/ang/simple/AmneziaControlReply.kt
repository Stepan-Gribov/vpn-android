package com.v2ray.ang.simple

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A late acknowledgement of an earlier network must not complete a newer migration. */
internal class AmneziaControlReply(val id: Long) {
    private val latch = CountDownLatch(1)
    @Volatile private var success = false
    fun accept(line: String) {
        val pieces = line.split(' ', limit = 2)
        if (pieces.size != 2 || pieces[1].toLongOrNull() != id) return
        when (pieces[0]) {
            "TNDTUN_AMNEZIA_REBOUND" -> success = true
            "TNDTUN_AMNEZIA_REBIND_FAILED" -> success = false
            else -> return
        }
        latch.countDown()
    }
    fun await(timeoutMillis: Long, wanted: () -> Boolean = { true }): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (wanted()) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return false
            if (latch.await(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS)) {
                return success && wanted()
            }
        }
        return false
    }
}
