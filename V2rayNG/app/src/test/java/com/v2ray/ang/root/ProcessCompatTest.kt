package com.v2ray.ang.root

import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import org.junit.Assert.*
import org.junit.Test

class ProcessCompatTest {
    @Test fun reportsCompletedExitStatus() {
        val process = ProcessBuilder("sh", "-c", "exit 7").start()
        try {
            assertTrue(process.waitForCompat(2, TimeUnit.SECONDS))
            assertEquals(7, process.exitValue())
        } finally { process.destroy() }
    }

    @Test fun unresponsiveProcessIsBounded() {
        // Waiting for stdin keeps the fixture alive without a child process.
        val process = ProcessBuilder("sh", "-c", "read value").start()
        try {
            assertFalse(process.waitForCompat(50, TimeUnit.MILLISECONDS))
            process.outputStream.close()
            assertTrue(process.waitForCompat(2, TimeUnit.SECONDS))
        } finally { process.destroy() }
    }

    @Test(timeout = 10000) fun drainsVerboseMergedOutputWithoutBlockingOrGrowing() {
        val process = ProcessBuilder("sh", "-c", """
            i=0
            while [ "${'$'}i" -lt 20000 ]; do
                printf 'diagnostic-0123456789\n'
                i=${'$'}((i + 1))
            done
            printf 'stderr retained\n' >&2
            printf '0\n'
        """.trimIndent()).redirectErrorStream(true).start()
        val completed = process.collectOutputCompat(5, TimeUnit.SECONDS, maxOutputBytes = 1024)
        assertEquals(0, completed.code)
        assertTrue(completed.truncated)
        assertTrue(completed.output.toByteArray().size <= 1024)
        assertTrue(completed.output.endsWith("stderr retained\n0\n"))
        assertEquals(0, process.exitValue())
    }

    @Test(timeout = 5000) fun preservesCompleteOutputAndExitStatus() {
        val process = ProcessBuilder("sh", "-c", "printf 'diagnostic'; exit 7")
            .redirectErrorStream(true).start()
        val completed = process.collectOutputCompat(2, TimeUnit.SECONDS)
        assertEquals(7, completed.code)
        assertEquals("diagnostic", completed.output)
        assertFalse(completed.truncated)
    }

    @Test(timeout = 5000) fun timeoutTerminatesAndReapsTheProcess() {
        val process = ProcessBuilder("sh", "-c", "while :; do :; done")
            .redirectErrorStream(true).start()
        try {
            val completed = process.collectOutputCompat(50, TimeUnit.MILLISECONDS)
            assertNull(completed.code)
            assertTrue(process.waitForCompat(1, TimeUnit.SECONDS))
        } finally { process.destroy() }
    }

    @Test(timeout = 5000) fun interruptionTerminatesAndReapsTheProcess() {
        val process = ProcessBuilder("sh", "-c", "while :; do :; done")
            .redirectErrorStream(true).start()
        val entered = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            try {
                entered.countDown()
                process.collectOutputCompat(30, TimeUnit.SECONDS)
                failure.set(AssertionError("Interrupted command returned normally"))
            } catch (_: InterruptedException) {
                // Interruption must propagate after cleanup.
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        try {
            worker.start()
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            worker.interrupt()
            worker.join(2000)
            assertFalse(worker.isAlive)
            assertNull(failure.get())
            assertTrue(process.waitForCompat(1, TimeUnit.SECONDS))
        } finally {
            process.destroy()
            worker.interrupt()
            worker.join(1000)
        }
    }

    @Test(timeout = 5000) fun coroutineCancellationTerminatesTheProcess() = runBlocking {
        val process = ProcessBuilder("sh", "-c", "while :; do :; done")
            .redirectErrorStream(true).start()
        val entered = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.IO) {
            runInterruptible {
                entered.complete(Unit)
                process.collectOutputCompat(30, TimeUnit.SECONDS)
            }
        }
        try {
            entered.await()
            job.cancelAndJoin()
            assertTrue(process.waitForCompat(1, TimeUnit.SECONDS))
        } finally {
            process.destroy()
            job.cancelAndJoin()
        }
    }
}
