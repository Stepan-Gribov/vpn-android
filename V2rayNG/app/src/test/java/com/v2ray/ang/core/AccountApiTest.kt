package com.v2ray.ang.core

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccountApiTest {
    private val url = "https://vpnapp.example.invalid//api/auth/login".toHttpUrl()
    private fun token(cookie: String) = AccountApi.sessionToken(url, Headers.headersOf("Set-Cookie", cookie))

    @Test fun acceptsOnlyTheAccountSession() {
        assertEquals("demo-token", token("kindlyvpn_session=demo-token; Path=/; Secure; HttpOnly; Max-Age=3600"))
        assertNull(token("kindly_cid=demo-token; Path=/; Secure; HttpOnly"))
    }
    @Test fun rejectsInsecureOrScriptReadableSessions() {
        assertNull(token("kindlyvpn_session=demo-token; Path=/; HttpOnly"))
        assertNull(token("kindlyvpn_session=demo-token; Path=/; Secure"))
    }
    @Test fun rejectsExpiredAndForeignCookies() {
        assertNull(token("kindlyvpn_session=demo-token; Path=/; Secure; HttpOnly; Max-Age=0"))
        assertNull(token("kindlyvpn_session=demo-token; Path=/; Secure; HttpOnly; Domain=example.com"))
        assertNull(token("kindlyvpn_session=demo-token; Path=/other; Secure; HttpOnly"))
    }
}
