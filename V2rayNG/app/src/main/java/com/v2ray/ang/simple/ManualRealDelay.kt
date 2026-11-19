package com.v2ray.ang.simple

import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** One fresh HTTPS exchange through the existing forced-proxy inlet, never app split routing. */
internal object ManualRealDelay {
    const val URL = "https://www.gstatic.com/generate_204"
    const val TIMEOUT_MS = 8_000L

    fun measure(port: Int): Long {
        val client = client(port).build()
        try {
            return exchange(client, URL)
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    internal fun client(port: Int): OkHttpClient.Builder {
        require(port in 1..65535) { "VPN probe inlet missing" }
        return OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    internal fun exchange(client: OkHttpClient, url: String): Long {
        val call = client.newCall(Request.Builder().url(url)
            .header("Cache-Control", "no-cache").header("Connection", "close").build())
        // Includes tunnel DNS/TCP/TLS and response headers, excludes setup and cleanup.
        val start = System.nanoTime()
        return call.execute().use { response ->
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            check(response.code == 204) { "Unexpected HTTP ${response.code}" }
            elapsed
        }
    }
}
