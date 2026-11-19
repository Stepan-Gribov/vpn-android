package com.v2ray.ang.simple

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class PanelOperationQueueTest {
    @Test fun concurrentIdenticalActivationHasOneRequest()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val queue=PanelOperationQueue(scope); val release=CompletableDeferred<Unit>(); val entered=CompletableDeferred<Unit>(); val calls=AtomicInteger()
            val first=queue.submit("activate-same-code") { calls.incrementAndGet(); entered.complete(Unit); release.await(); true }
            entered.await()
            val repeated=(1..32).map { async(Dispatchers.Default) { queue.submit("activate-same-code") { calls.incrementAndGet(); false } } }.awaitAll()
            assertTrue(repeated.all { it === first }); release.complete(Unit)
            assertTrue(first.await()); assertEquals(1,calls.get())
        } finally { scope.cancel() }
    }
    @Test fun activationWaitsForSyncInsteadOfReturningItsResult()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val queue=PanelOperationQueue(scope); val release=CompletableDeferred<Unit>(); val entered=CompletableDeferred<Unit>(); val order=mutableListOf<String>()
            val sync=queue.submit("sync") { order.add("sync"); entered.complete(Unit); release.await(); false }
            entered.await()
            val activation=queue.submit("activate") { order.add("activate"); true }
            assertNotSame(sync,activation); release.complete(Unit)
            assertTrue(activation.await()); assertEquals(listOf("sync","activate"),order)
        } finally { scope.cancel() }
    }
}
