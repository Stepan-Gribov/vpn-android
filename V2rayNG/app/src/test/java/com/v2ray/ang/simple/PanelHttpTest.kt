package com.v2ray.ang.simple

import kotlinx.coroutines.*
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class PanelHttpTest {
    private fun server(block:(MockWebServer,okhttp3.OkHttpClient,String)->Unit) {
        val cert=HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls=HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientTls=HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        MockWebServer().use { server->
            server.useHttps(serverTls.sslSocketFactory(),false); server.start()
            val client=PanelHttp.client(3).sslSocketFactory(clientTls.sslSocketFactory(),clientTls.trustManager).build()
            block(server,client,server.url("/v1").toString())
        }
    }
    @Test fun gzipIsDecodedAndBearerOnlyGoesToTheExactOrigin()=server { server,client,api->
        val buffer=Buffer(); GzipSink(buffer).buffer().use { it.writeUtf8("{\"fixture\":true}") }
        server.enqueue(MockResponse().setHeader("Content-Encoding","gzip").setBody(buffer))
        assertEquals("{\"fixture\":true}",String(runBlocking { PanelHttp.requestCancellable(client,api,"sync","{\"nonce\":\"fixture-nonce\"}","fixture-token") }))
        val request=server.takeRequest(1,TimeUnit.SECONDS)!!
        assertEquals("POST",request.method); assertEquals("/v1/sync",request.path)
        assertEquals("Bearer fixture-token",request.getHeader("Authorization"))
        assertEquals("gzip",request.getHeader("Accept-Encoding"))
    }
    @Test fun redirectsNeverReceiveTheCodeOrBearer()=server { server,client,api->
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location",server.url("/untrusted")))
        try { PanelHttp.request(client,api,"activate","{\"code\":\"fixture-code\"}",null); fail() } catch(e:PanelHttpFailure) { assertEquals(302,e.status) }
        assertEquals(1,server.requestCount)
        assertNull(server.takeRequest()!!.getHeader("Authorization"))
    }
    @Test fun badTlsCertificateAndWrongHostAreRejected()=server { _,client,api->
        try { PanelHttp.request(PanelHttp.client(2).dns { listOf(java.net.InetAddress.getByName("127.0.0.1")) }.build(),api,"sync","{}","fixture-token"); fail() } catch(_:javax.net.ssl.SSLException){}
        try { PanelHttp.request(client,api.replace("localhost","127.0.0.1"),"sync","{}","fixture-token"); fail() } catch(_:javax.net.ssl.SSLException){}
    }
    @Test fun decompressedEnvelopeSizeIsBounded()=server { server,client,api->
        val buffer=Buffer(); GzipSink(buffer).buffer().use { sink->
            val chunk="x".repeat(8192)
            repeat(PanelContract.ENVELOPE_LIMIT/8192+1) { sink.writeUtf8(chunk) }
        }
        server.enqueue(MockResponse().setHeader("Content-Encoding","gzip").setBody(buffer))
        try { PanelHttp.request(client,api,"sync","{}","fixture-token"); fail() } catch(_:IllegalArgumentException){}
    }
    @Test fun cancellableRequestPreservesHttpErrorsWithoutRedirectingCredentials()=server { server,client,api->
        runBlocking {
            for(status in listOf(401,403,429,503,302)) {
                server.enqueue(MockResponse().setResponseCode(status).setHeader("Location",server.url("/untrusted")))
                try { PanelHttp.requestCancellable(client,api,"sync","{}","fixture-token"); fail() }
                catch(e:PanelHttpFailure) { assertEquals(status,e.status) }
                assertEquals("/v1/sync",server.takeRequest(1,TimeUnit.SECONDS)!!.path)
            }
            assertEquals(5,server.requestCount)
        }
    }
    @Test fun cancellationClosesAnOutstandingHttpCallPromptly()=server { server,client,api->
        runBlocking {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val request=async { PanelHttp.requestCancellable(client,api,"sync","{}","fixture-token") }
            val received=withContext(Dispatchers.IO) { server.takeRequest(2,TimeUnit.SECONDS) }
            assertNotNull(received)
            val started=System.nanoTime()
            request.cancelAndJoin()
            withTimeout(1000) { while(client.dispatcher.runningCallsCount()!=0) delay(10) }
            assertTrue("Cancellation must not await HTTP timeout",(System.nanoTime()-started)/1_000_000<1000)
            assertTrue(request.isCancelled)
        }
    }
}
