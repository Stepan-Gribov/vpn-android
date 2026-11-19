package com.v2ray.ang.simple

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ManualRealDelayTest {
    /** The synthetic hostname is handed to SOCKS, so a direct/system-DNS path cannot pass. */
    private fun proxy(real: Boolean = false, delay: Long = 0, status: Int = 204, block: (Int) -> Unit) {
        ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { listener ->
            listener.soTimeout = 10_000
            val worker = Executors.newSingleThreadExecutor()
            val task = worker.submit {
                listener.accept().use { socket ->
                    socket.soTimeout = 10_000
                    val input = DataInputStream(socket.getInputStream())
                    val out = socket.getOutputStream()
                    assertEquals(5, input.readUnsignedByte())
                    input.readFully(ByteArray(input.readUnsignedByte()))
                    out.write(byteArrayOf(5, 0)); out.flush()
                    assertEquals(5, input.readUnsignedByte()); assertEquals(1, input.readUnsignedByte())
                    input.readUnsignedByte(); assertEquals(3, input.readUnsignedByte())
                    val host = ByteArray(input.readUnsignedByte()).also(input::readFully).toString(Charsets.US_ASCII)
                    val port = input.readUnsignedShort()
                    assertEquals(if (real) "www.gstatic.com" else "unresolved.invalid", host)
                    assertEquals(if (real) 443 else 80, port)
                    out.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 1)); out.flush()
                    if (real) {
                        Socket().use { remote ->
                            remote.connect(java.net.InetSocketAddress(host, port), 5_000)
                            remote.soTimeout = 10_000
                            val upload = Thread {
                                runCatching { input.copyTo(remote.getOutputStream()); remote.shutdownOutput() }
                            }.apply { isDaemon = true; start() }
                            runCatching { remote.getInputStream().copyTo(out) }
                            socket.close(); upload.join(1_000)
                        }
                    } else {
                        val reader = input.bufferedReader()
                        assertEquals("GET /generate_204 HTTP/1.1", reader.readLine())
                        while (!reader.readLine().isNullOrEmpty()) { }
                        Thread.sleep(delay)
                        runCatching { out.write("HTTP/1.1 $status Test\r\nContent-Length: 0\r\nConnection: close\r\nLocation: /other\r\n\r\n".toByteArray()); out.flush() }
                    }
                }
            }
            try { block(listener.localPort); task.get(12, TimeUnit.SECONDS) }
            finally { worker.shutdownNow() }
        }
    }

    @Test fun oneResponseThroughSocksMeasuresOnlyTheExchange() = proxy(delay = 200) { port ->
        val client = ManualRealDelay.client(port).build()
        try {
            Thread.sleep(300) // Client preparation/waiting must not enter the displayed number.
            val started = System.nanoTime()
            val measured = ManualRealDelay.exchange(client, "http://unresolved.invalid/generate_204")
            val wall = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue(measured >= 150)
            assertTrue(measured <= wall && wall - measured < 200)
        } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }

    @Test fun unexpectedResponseAndTimeoutDoNotProduceLatency() {
        proxy(status = 302) { port ->
            val client = ManualRealDelay.client(port).build()
            try { assertThrows(IllegalStateException::class.java) { ManualRealDelay.exchange(client, "http://unresolved.invalid/generate_204") } }
            finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        }
        proxy(delay = 400) { port ->
            val client = ManualRealDelay.client(port).callTimeout(100, TimeUnit.MILLISECONDS).build()
            val started = System.nanoTime()
            try { assertThrows(IOException::class.java) { ManualRealDelay.exchange(client, "http://unresolved.invalid/generate_204") } }
            finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_000)
        }
        assertThrows(IllegalArgumentException::class.java) { ManualRealDelay.measure(0) }
    }

    @Test fun optionalRealHttpsResponseThroughRecordingSocks() {
        assumeTrue(System.getenv("TNDTUN_REAL_DELAY_NETWORK_TEST") == "1")
        proxy(real = true) { port ->
            val measured = ManualRealDelay.measure(port)
            println("RealDelay HTTPS 204 through SOCKS: ${measured}ms")
            assertTrue(measured in 0..ManualRealDelay.TIMEOUT_MS)
        }
    }
}
