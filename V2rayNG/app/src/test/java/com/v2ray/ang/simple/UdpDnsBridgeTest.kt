package com.v2ray.ang.simple

import org.junit.Assert.*
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class UdpDnsBridgeTest {
    private fun query(id: Int)=byteArrayOf((id shr 8).toByte(),id.toByte(),1,0,0,1,0,0,0,0,0,0,
        5,98,117,114,115,116,4,116,101,115,116,0,0,1,0,1)
    private fun answer(q: ByteArray)=q.copyOf().apply { this[2]=0x81.toByte(); this[3]=0x80.toByte(); this[7]=1 }+
        byteArrayOf(0xc0.toByte(),12,0,1,0,1,0,0,0,60,0,4,192.toByte(),0,2,1)
    @Test fun burstRetainsAll128PacketsWithOnlyFourActiveResolvers() {
        val release=CountDownLatch(1)
        val first=CountDownLatch(4)
        UdpDnsBridge({ q->first.countDown(); release.await(); Thread.sleep(2); answer(q) }).use { bridge->
            val port=bridge.endpoint.substringAfter(':').toInt()
            val clients=(0 until 128).map { DatagramSocket().apply { soTimeout=4000 } }
            try {
                // Fill the application queue while workers are held; pace OS loopback writes
                // so this tests application admission rather than macOS UDP burst loss.
                clients.forEachIndexed { i,s->val q=query(i);s.send(DatagramPacket(q,q.size,InetAddress.getLoopbackAddress(),port));Thread.sleep(1) }
                assertTrue(first.await(1,TimeUnit.SECONDS))
                Thread.sleep(200)
                release.countDown()
                val pool=Executors.newFixedThreadPool(128)
                val pending=clients.mapIndexed { i,s->pool.submit<Boolean> { runCatching {
                    val p=DatagramPacket(ByteArray(512),512);s.receive(p)
                    assertArrayEquals(answer(query(i)),p.data.copyOf(p.length));true
                }.getOrDefault(false) } }
                val answers=try { pending.map { it.get(5,TimeUnit.SECONDS) } } finally { pool.shutdownNow() }
                assertEquals("Every queued DNS transaction must get its positive answer",128,answers.count { it })
            } finally { release.countDown();clients.forEach { it.close() } }
        }
    }
    @Test fun stopCancelsFullQueueAndRestartCanResolveAgain() {
        repeat(2) {
            val first=CountDownLatch(4)
            val release=CountDownLatch(1)
            val bridge=UdpDnsBridge({ q->first.countDown(); release.await(); answer(q) },{ release.countDown() })
            val port=bridge.endpoint.substringAfter(':').toInt()
            DatagramSocket().use { s->repeat(128) { i->val q=query(i);s.send(DatagramPacket(q,q.size,InetAddress.getLoopbackAddress(),port)) } }
            assertTrue(first.await(1,TimeUnit.SECONDS));Thread.sleep(100)
            val started=System.nanoTime();bridge.close()
            assertTrue("Shutdown must finish within one second",System.nanoTime()-started<TimeUnit.SECONDS.toNanos(1))
            UdpDnsBridge({ answer(it) }).use { restarted->
                DatagramSocket().use { s->
                    s.soTimeout=1000;val q=query(99);s.send(DatagramPacket(q,q.size,InetAddress.getLoopbackAddress(),restarted.endpoint.substringAfter(':').toInt()))
                    val p=DatagramPacket(ByteArray(512),512);s.receive(p);assertArrayEquals(answer(q),p.data.copyOf(p.length))
                }
            }
        }
    }
}
