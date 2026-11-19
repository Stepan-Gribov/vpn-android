package com.v2ray.ang.root

import java.util.concurrent.TimeUnit

/** Timed Process.waitFor and destroyForcibly are unavailable on Android 7. */
internal fun Process.waitForCompat(timeout: Long, unit: TimeUnit): Boolean {
    val budget = unit.toNanos(timeout)
    val started = System.nanoTime()
    while (true) {
        try {
            exitValue()
            return true
        } catch (_: IllegalThreadStateException) {
            if (System.nanoTime() - started >= budget) return false
            Thread.sleep(25)
        }
    }
}

internal fun Process.destroyCompat() {
    destroy()
    // Look up the newer method without linking it on Android 7. This helper
    // also stays usable with real subprocesses in host-side JVM tests.
    try {
        Process::class.java.getMethod("destroyForcibly").invoke(this)
    } catch (_: ReflectiveOperationException) {
        // Android 7's Process implementation is terminated by destroy().
    } catch (_: SecurityException) {
        // Fall back to the public destroy() already called above.
    }
}

internal data class ProcessOutput(val code: Int?, val output: String, val truncated: Boolean)

/**
 * Drain merged stdout/stderr while waiting, retaining at most [maxOutputBytes].
 * Never wait for pipe EOF: a background child may inherit stdout after its shell
 * exits. Polling available bytes also lets interruption close everything without
 * leaving a reader thread blocked on that child.
 */
internal fun Process.collectOutputCompat(
    timeout: Long,
    unit: TimeUnit,
    maxOutputBytes: Int = 64 * 1024
): ProcessOutput {
    try {
        require(timeout >= 0 && maxOutputBytes > 0)
        val output = OutputTail(maxOutputBytes)
        val buffer = ByteArray(8192)
        val started = System.nanoTime()
        val budget = unit.toNanos(timeout)
        // These commands receive their complete script via su -c, not stdin.
        outputStream.close()
        while (true) {
            if (Thread.interrupted()) throw InterruptedException("Root command interrupted")
            val available = inputStream.available()
            if (available > 0) {
                val count = inputStream.read(buffer, 0, minOf(buffer.size, available))
                if (count > 0) output.append(buffer, count)
            }
            val code = try { exitValue() } catch (_: IllegalThreadStateException) { null }
            if (code != null && inputStream.available() == 0) {
                return ProcessOutput(code, output.text(), output.truncated)
            }
            val remaining = budget - (System.nanoTime() - started)
            if (remaining <= 0) return ProcessOutput(null, output.text(), output.truncated)
            if (available == 0) TimeUnit.NANOSECONDS.sleep(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(25)))
        }
    } finally {
        // Complete cleanup even if cancellation interrupts the bounded reap.
        var interrupted = Thread.interrupted()
        try {
            destroyCompat()
            try {
                waitForCompat(1, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                interrupted = true
            }
        } finally {
            runCatching { outputStream.close() }
            runCatching { inputStream.close() }
            runCatching { errorStream.close() }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}

/** A byte ring keeps final diagnostics and id -u's last line without growing. */
private class OutputTail(capacity: Int) {
    private val bytes = ByteArray(capacity)
    private var size = 0
    private var next = 0
    var truncated = false
        private set

    fun append(source: ByteArray, count: Int) {
        if (count > bytes.size - size) truncated = true
        val retained = minOf(count, bytes.size)
        val offset = count - retained
        val first = minOf(retained, bytes.size - next)
        source.copyInto(bytes, next, offset, offset + first)
        source.copyInto(bytes, 0, offset + first, offset + retained)
        next = (next + retained) % bytes.size
        size = minOf(bytes.size, size + retained)
    }

    fun text(): String {
        if (size < bytes.size) return String(bytes, 0, size, Charsets.UTF_8)
        return String(bytes.copyOfRange(next, bytes.size) + bytes.copyOfRange(0, next), Charsets.UTF_8)
    }
}
