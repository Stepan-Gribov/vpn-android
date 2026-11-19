package com.v2ray.ang.simple

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class StagedPublicationTest {
    @Test fun delayedCancelledStartupCannotOverwriteManualSelection() {
        val attempt=StartupAttempt();val ticket=attempt.claim()!!
        val preparation=CountDownLatch(1);val finish=CountDownLatch(1)
        val staged=AtomicReference("old")
        val worker=Thread {
            preparation.countDown()
            assertTrue(finish.await(5,TimeUnit.SECONDS))
            runCatching { StagedPublication.publish({attempt.current(ticket)}) { staged.set("automatic") } }
        }
        worker.start();assertTrue(preparation.await(5,TimeUnit.SECONDS))
        attempt.cancel()
        StagedPublication.publish({true}) { staged.set("manual") }
        finish.countDown();worker.join(5000)
        assertFalse(worker.isAlive);assertEquals("manual",staged.get())
    }
}
