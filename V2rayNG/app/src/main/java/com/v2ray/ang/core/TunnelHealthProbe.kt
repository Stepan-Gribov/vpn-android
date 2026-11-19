package com.v2ray.ang.core

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** A working literal-IP HTTPS endpoint does not prove that apps can resolve names.
 * Check fresh upstream DNS, the application's DNS inlet, and authenticated HTTPS.
 * Both HTTPS providers are diagnostics only; app split rules are not changed. */
internal object TunnelHealthProbe {
    data class Result(val success: Boolean, val detail: String, val elapsedMillis: Long)
    val urls = listOf("https://www.gstatic.com/generate_204", "https://cp.cloudflare.com/generate_204")

    fun check(proxy: InetSocketAddress, appProxy: InetSocketAddress, resolvers: List<InetSocketAddress>, timeoutMillis: Long, verifyAppRoute: Boolean = false): Result {
        val start=System.nanoTime()
        val deadline=start+TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        fun remaining()=TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()).coerceAtLeast(0)
        var detail="DNS unavailable"
        for((index,url) in urls.withIndex()) {
            if(remaining()<=0) break
            val candidateDeadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(remaining()/(urls.size-index))
            fun budget(limit: Long)=minOf(limit,TimeUnit.NANOSECONDS.toMillis(candidateDeadline-System.nanoTime())).coerceAtLeast(0)
            val host=java.net.URI(url).host
            try {
                var resolved=false
                for(resolver in resolvers.take(2)) {
                    val timeout=budget(2500)
                    if(timeout<=0) break
                    if(runCatching { DnsHealthQuery.exchange(proxy,resolver,host,timeout.toInt()) }.getOrDefault(false)) { resolved=true; break }
                }
                if(!resolved) throw IOException("Upstream DNS unavailable")
                val dnsBudget=budget(2500)
                val address=if(dnsBudget>0) DnsHealthQuery.resolve(appProxy,InetSocketAddress.createUnresolved("1.1.1.1",53),host,dnsBudget.toInt()) else null
                if(address==null) throw IOException("Application DNS unavailable")
                for (route in if (verifyAppRoute) listOf(proxy, appProxy) else listOf(proxy)) {
                    val timeout=budget(timeoutMillis)
                    if(timeout<=0) throw IOException("HTTPS deadline")
                    val client=OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
                        // Keep hostname TLS, use a fresh app-DNS answer and verify both
                        // forced transport and actual split routing for Amnezia.
                        .socketFactory(ProxiedIpSocketFactory(route)).dns { listOf(address) }
                        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                        .connectTimeout(timeout,TimeUnit.MILLISECONDS).readTimeout(timeout,TimeUnit.MILLISECONDS)
                        .callTimeout(timeout,TimeUnit.MILLISECONDS).build()
                    try {
                        client.newCall(Request.Builder().url(url).header("User-Agent","TnDtun connectivity check").build()).execute().use {
                            if(it.code!=204) throw IOException("HTTPS ${it.code}")
                        }
                    } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
                }
                return Result(true,if(verifyAppRoute) "DNS, tunnel HTTPS and app HTTPS available" else "DNS and HTTPS available",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start))
            } catch(e:Exception) {
                if(Thread.currentThread().isInterrupted) throw e
                detail=e.message?.take(160) ?: "Connectivity check failed"
            }
        }
        return Result(false,detail,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start))
    }
}

/** Minimal bounded DNS-over-TCP client. No system lookup and no cached answer. */
internal object DnsHealthQuery {
    fun query(host:String,id:Int):ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeShort(id); out.writeShort(0x0100); out.writeShort(1)
            repeat(3) { out.writeShort(0) }
            host.split('.').forEach { label ->
                val value=label.toByteArray(Charsets.US_ASCII)
                require(value.size in 1..63); out.writeByte(value.size); out.write(value)
            }
            out.writeByte(0); out.writeShort(1); out.writeShort(1)
        }
    }.toByteArray()

    fun exchange(proxy:InetSocketAddress,resolver:InetSocketAddress,host:String,timeoutMillis:Int):Boolean = resolve(proxy,resolver,host,timeoutMillis)!=null

    fun resolve(proxy:InetSocketAddress,resolver:InetSocketAddress,host:String,timeoutMillis:Int):java.net.InetAddress? {
        require(timeoutMillis>0)
        val id=Random.nextInt(65536)
        val query=query(host,id)
        val deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMillis.toLong())
        Socket(Proxy(Proxy.Type.SOCKS,proxy)).use { socket ->
            socket.soTimeout=timeoutMillis
            socket.connect(resolver,timeoutMillis)
            val out=DataOutputStream(socket.getOutputStream())
            out.writeShort(query.size); out.write(query); out.flush()
            fun read(count:Int):ByteArray {
                val result=ByteArray(count);var offset=0
                while(offset<count) {
                    val remaining=TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime())
                    if(remaining<=0) throw java.net.SocketTimeoutException("DNS deadline")
                    socket.soTimeout=remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
                    val n=socket.getInputStream().read(result,offset,count-offset)
                    if(n<0) throw java.io.EOFException("DNS response closed")
                    offset+=n
                }
                return result
            }
            val length=read(2).let { ((it[0].toInt() and 255) shl 8) or (it[1].toInt() and 255) }
            if(length !in 12..4096) return null
            return answerAddress(read(length),host,id)?.let { java.net.InetAddress.getByAddress(host,it) }
        }
    }

    fun validAnswer(data:ByteArray,host:String,id:Int):Boolean = answerAddress(data,host,id)!=null

    private fun answerAddress(data:ByteArray,host:String,id:Int):ByteArray? = runCatching {
        fun u16(offset:Int):Int { require(offset>=0 && offset+2<=data.size); return ((data[offset].toInt() and 255) shl 8) or (data[offset+1].toInt() and 255) }
        require(data.size>=12 && u16(0)==id && u16(2) and 0xFA0F==0x8000 && u16(4)==1)
        fun name(start:Int):Pair<String,Int> {
            var p=start;var end=-1;val labels=ArrayList<String>();val seen=HashSet<Int>()
            while(true) {
                require(p in data.indices && seen.add(p) && seen.size<=128)
                val size=data[p].toInt() and 255
                if(size==0) return labels.joinToString(".") to if(end<0) p+1 else end
                if(size and 0xC0==0xC0) {
                    require(p+1<data.size)
                    if(end<0) end=p+2
                    p=((size and 63) shl 8) or (data[p+1].toInt() and 255)
                } else {
                    require(size in 1..63 && p+1+size<=data.size)
                    labels+=String(data,p+1,size,Charsets.US_ASCII); p+=1+size
                }
            }
        }
        val question=name(12);require(question.first.equals(host,true))
        require(u16(question.second)==1 && u16(question.second+2)==1)
        var p=question.second+4
        repeat(u16(6)) {
            val record=name(p);p=record.second
            val type=u16(p);val clazz=u16(p+2);val length=u16(p+8);p+=10
            require(p+length<=data.size)
            if(type==1 && clazz==1 && length==4 && data[p].toInt() and 255 !in listOf(0,127) && data[p].toInt() and 0xF0!=0xE0) return@runCatching data.copyOfRange(p,p+4)
            p+=length
        }
        null
    }.getOrNull()
}

/** Force every diagnostic connection into SOCKS, never the excluded app UID's
 * physical route. Only an explicitly resolved numeric destination is accepted. */
internal class ProxiedIpSocketFactory(private val proxy:InetSocketAddress):javax.net.SocketFactory() {
    override fun createSocket():Socket = object:Socket(Proxy(Proxy.Type.SOCKS,proxy)) {
        override fun connect(endpoint:java.net.SocketAddress,timeout:Int) {
            require(endpoint is InetSocketAddress && !endpoint.isUnresolved) { "Health destination must be a resolved IP" }
            super.connect(endpoint,timeout)
        }
    }
    override fun createSocket(host:String,port:Int):Socket = throw UnsupportedOperationException()
    override fun createSocket(host:String,port:Int,local:java.net.InetAddress,localPort:Int):Socket = throw UnsupportedOperationException()
    override fun createSocket(host:java.net.InetAddress,port:Int):Socket = createSocket().apply { connect(InetSocketAddress(host,port)) }
    override fun createSocket(host:java.net.InetAddress,port:Int,local:java.net.InetAddress,localPort:Int):Socket = createSocket().apply { bind(InetSocketAddress(local,localPort));connect(InetSocketAddress(host,port)) }
}
