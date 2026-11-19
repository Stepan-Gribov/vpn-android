package com.v2ray.ang.simple

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PanelRouteFallbackTest {
    @Test fun successfulDirectRequestNeverShowsDns() = runBlocking {
        val routes = mutableListOf<PanelRequestRoute?>()
        val result = PanelRouteFallback.post(onRoute = { routes.add(it) },
            direct = { byteArrayOf(7) }, dns = { fail("DNS must not start"); byteArrayOf() })
        assertArrayEquals(byteArrayOf(7), result)
        assertEquals(listOf(PanelRequestRoute.DIRECT, null), routes)
    }

    @Test fun dnsIsOnlyReportedOnActualFallbackAndNextRequestStartsDirectly() = runBlocking {
        val routes = mutableListOf<PanelRequestRoute?>()
        repeat(2) { attempt ->
            PanelRouteFallback.post(onRoute = { routes.add(it) },
                direct = { if (attempt == 0) throw IOException("offline"); byteArrayOf(1) },
                dns = { byteArrayOf(2) })
        }
        assertEquals(listOf(PanelRequestRoute.DIRECT, PanelRequestRoute.DNS, null,
            PanelRequestRoute.DIRECT, null), routes)
    }

    @Test fun httpAndValidationFailuresNeverTryDns() = runBlocking {
        for (error in listOf(401, 403, 429, 503, 302).map { PanelHttpFailure(it, "") } +
            IllegalArgumentException("invalid manifest")) {
            val routes = mutableListOf<PanelRequestRoute?>()
            try {
                PanelRouteFallback.post(onRoute = { routes.add(it) }, direct = { throw error },
                    dns = { fail("HTTP errors are not transport failures"); byteArrayOf() })
                fail("Expected error")
            } catch (e: Exception) { assertSame(error, e) }
            assertEquals(listOf(PanelRequestRoute.DIRECT, null), routes)
        }
    }

    @Test fun cancellationClosesDirectRequestWithoutStartingDns() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val routes = mutableListOf<PanelRequestRoute?>()
        var closed = false
        val request = async {
            PanelRouteFallback.post(onRoute = { routes.add(it) }, direct = {
                try { entered.complete(Unit); awaitCancellation() } finally { closed = true }
            }, dns = { fail("Cancelled request must not start DNS"); byteArrayOf() })
        }
        entered.await(); request.cancelAndJoin()
        assertTrue(closed)
        assertEquals(listOf(PanelRequestRoute.DIRECT, null), routes)
    }

    @Test fun cancellationClearsDnsStatusAndRunsCleanup() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val routes = mutableListOf<PanelRequestRoute?>()
        var closed = false
        val request = async {
            PanelRouteFallback.post(onRoute = { routes.add(it) }, direct = { throw IOException() }, dns = {
                try { entered.complete(Unit); awaitCancellation() } finally { closed = true }
            })
        }
        entered.await(); request.cancelAndJoin()
        assertTrue(closed)
        assertEquals(listOf(PanelRequestRoute.DIRECT, PanelRequestRoute.DNS, null), routes)
    }

    @Test fun cancellationDuringNetworkFailureCannotEnterDns() = runBlocking {
        val routes = mutableListOf<PanelRequestRoute?>()
        val request = async {
            PanelRouteFallback.post(onRoute = { routes.add(it) }, direct = {
                currentCoroutineContext().cancel()
                throw IOException("cancelled socket")
            }, dns = { fail("Cancelled socket must not select DNS"); byteArrayOf() })
        }
        request.join()
        assertTrue(request.isCancelled)
        assertEquals(listOf(PanelRequestRoute.DIRECT, null), routes)
    }

    @Test fun failingStatusObserverCannotFailNetworking() = runBlocking {
        assertArrayEquals(byteArrayOf(3), PanelRouteFallback.post(
            onRoute = { throw IllegalStateException("closed screen") },
            direct = { throw IOException() }, dns = { byteArrayOf(3) }))
    }

    @Test fun explicitDnsTestPathHasNoDirectStatusAndClearsOnFailure() = runBlocking {
        val routes = mutableListOf<PanelRequestRoute?>()
        try {
            PanelRouteFallback.post(dnsOnly = true, onRoute = { routes.add(it) },
                direct = { fail("Explicit DNS test"); byteArrayOf() }, dns = { throw IOException() })
            fail()
        } catch (_: IOException) { }
        assertEquals(listOf(PanelRequestRoute.DNS, null), routes)
    }
}
