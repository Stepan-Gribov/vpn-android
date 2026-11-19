package com.v2ray.ang.simple

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException

/** Bounded DNS packet server. Resolution stays independent of the listener lifecycle. */
internal class UdpDnsBridge(private val resolve: (ByteArray)->ByteArray?, private val cancelResolution: ()->Unit = {}) : AutoCloseable {
    private val listener=DatagramSocket(null).apply {
        receiveBufferSize=512*1024
        bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"),0))
    }
    val endpoint="127.0.0.1:${listener.localPort}"
    val isClosed: Boolean get()=listener.isClosed
    private val workers=ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,ArrayBlockingQueue(64),
        { task->Thread(task,"direct-dns-query").apply { isDaemon=true } }, { task,executor->
            if(executor.isShutdown) throw RejectedExecutionException("DNS stopped")
            // Backpressure keeps the four-worker limit. The UDP receive buffer
            // holds the remaining burst instead of silently discarding tasks.
            executor.queue.put(task)
            if(executor.isShutdown && executor.remove(task)) throw RejectedExecutionException("DNS stopped")
        })
    private val receiver=Thread({
        while(!listener.isClosed) {
            val packet=DatagramPacket(ByteArray(4096),4096)
            if(runCatching { listener.receive(packet) }.isFailure) break
            if(packet.length<12) continue
            val query=packet.data.copyOf(packet.length)
            val client=packet.socketAddress
            runCatching { workers.execute {
                val answer=runCatching { resolve(query) }.getOrNull() ?: failure(query)
                runCatching { listener.send(DatagramPacket(answer,answer.size,client)) }
            } }
        }
    },"direct-dns-listener").apply { isDaemon=true; start() }

    private fun failure(query: ByteArray)=query.copyOf().apply {
        this[2]=(this[2].toInt() or 0x80).toByte()
        this[3]=((this[3].toInt() and 0xF0) or 0x82).toByte()
        for(i in 6..11) this[i]=0
    }

    override fun close() {
        listener.close()
        workers.shutdownNow()
        cancelResolution()
        receiver.interrupt()
        receiver.join(400)
        workers.awaitTermination(400,TimeUnit.MILLISECONDS)
    }
}
