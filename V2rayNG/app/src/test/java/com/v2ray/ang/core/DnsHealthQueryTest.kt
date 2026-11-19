package com.v2ray.ang.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DnsHealthQueryTest {
    private val host="www.gstatic.com"
    private val id=0x1234
    private fun answer(query:ByteArray=DnsHealthQuery.query(host,id)):ByteArray = ByteArrayOutputStream().also { b ->
        val header=query.copyOf().apply { this[2]=0x81.toByte();this[3]=0x80.toByte();this[7]=1 }
        b.write(header)
        DataOutputStream(b).apply {
            writeShort(0xc00c);writeShort(1);writeShort(1);writeInt(60);writeShort(4);write(byteArrayOf(142.toByte(),251.toByte(),1,1))
        }
    }.toByteArray()
    @Test fun `compressed A answer validates original question and transaction`() {
        assertTrue(DnsHealthQuery.validAnswer(answer(),host,id))
        assertFalse(DnsHealthQuery.validAnswer(answer(),host,id+1))
        assertFalse(DnsHealthQuery.validAnswer(answer(),"different.example",id))
    }
    @Test fun `DNS errors truncated packets and pointer loops never count as connectivity`() {
        for(flags in listOf(0x8182,0x8183,0x8380,0x0180)) {
            val bytes=answer().apply { this[2]=(flags shr 8).toByte();this[3]=flags.toByte() }
            assertFalse(DnsHealthQuery.validAnswer(bytes,host,id))
        }
        val bytes=answer()
        for(size in 0 until bytes.size) assertFalse("truncation $size",DnsHealthQuery.validAnswer(bytes.copyOf(size),host,id))
        val loop=bytes.copyOf().apply { this[12]=0xc0.toByte();this[13]=12 }
        assertFalse(DnsHealthQuery.validAnswer(loop,host,id))
    }
    @Test fun `empty and sinkhole DNS answers are not healthy`() {
        assertFalse(DnsHealthQuery.validAnswer(answer().apply { this[7]=0 },host,id))
        assertFalse(DnsHealthQuery.validAnswer(answer().apply { this[size-4]=127 },host,id))
    }
    private fun socks(block:(DataInputStream,DataOutputStream)->Unit,client:(InetSocketAddress)->Unit) {
        ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout=3000
            val executor=Executors.newSingleThreadExecutor()
            val worker=executor.submit {
                server.accept().use { socket ->
                    socket.soTimeout=3000
                    val input=DataInputStream(socket.getInputStream());val out=DataOutputStream(socket.getOutputStream())
                    assertEquals(5,input.readUnsignedByte())
                    val methods=ByteArray(input.readUnsignedByte());input.readFully(methods)
                    out.write(byteArrayOf(5,0));out.flush()
                    assertEquals(5,input.readUnsignedByte());assertEquals(1,input.readUnsignedByte());input.readUnsignedByte()
                    when(input.readUnsignedByte()) {
                        1 -> input.readFully(ByteArray(4))
                        3 -> input.readFully(ByteArray(input.readUnsignedByte()))
                        else -> fail("unexpected address type")
                    }
                    assertEquals(5353,input.readUnsignedShort())
                    out.write(byteArrayOf(5,0,0,1,127,0,0,1,0,1));out.flush()
                    block(input,out)
                }
            }
            try { client(InetSocketAddress("127.0.0.1",server.localPort));worker.get(4,TimeUnit.SECONDS) }
            finally { executor.shutdownNow() }
        }
    }
    @Test fun `fresh DNS goes through SOCKS to configured port without a system lookup`() {
        socks({ input,out ->
            val query=ByteArray(input.readUnsignedShort());input.readFully(query)
            val answer=answer(query);out.writeShort(answer.size);out.write(answer);out.flush()
        }) { proxy ->
            assertTrue(DnsHealthQuery.exchange(proxy,InetSocketAddress.createUnresolved("203.0.113.10",5353),host,1500))
        }
    }
    @Test fun `blackholed DNS has a bounded timeout while the proxy itself stays alive`() {
        socks({ input,_ ->
            input.readFully(ByteArray(input.readUnsignedShort()));Thread.sleep(350)
        }) { proxy ->
            val started=System.nanoTime()
            assertThrows(SocketTimeoutException::class.java) {
                DnsHealthQuery.exchange(proxy,InetSocketAddress.createUnresolved("203.0.113.10",5353),host,200)
            }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<2000)
        }
    }
    @Test fun `resolved HTTP socket cannot bypass the SOCKS data path`() {
        socks({ input,out ->
            assertEquals(42,input.readUnsignedByte());out.writeByte(99);out.flush()
        }) { proxy ->
            ProxiedIpSocketFactory(proxy).createSocket().use { socket ->
                socket.connect(InetSocketAddress(java.net.InetAddress.getByAddress(byteArrayOf(203.toByte(),0,113,10)),5353),1500)
                socket.soTimeout=1500;socket.getOutputStream().write(42)
                assertEquals(99,socket.getInputStream().read())
            }
        }
    }
    @Test fun `unresolved health destination cannot silently use another resolver`() {
        ProxiedIpSocketFactory(InetSocketAddress("127.0.0.1",1)).createSocket().use { socket ->
            assertThrows(IllegalArgumentException::class.java) { socket.connect(InetSocketAddress.createUnresolved("example.com",443),100) }
        }
    }
}
