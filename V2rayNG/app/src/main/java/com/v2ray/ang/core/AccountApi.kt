package com.v2ray.ang.core

import com.v2ray.ang.AppConfig

import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Uses the same session API as the website, without a WebView or stored password. */
class AccountApi(private val sessions: AccountSessionStore) {
    private val client = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .callTimeout(30, TimeUnit.SECONDS).build()

    fun request(action: String, body: JSONObject? = null): JSONObject {
        require(action in setOf("me", "captcha", "login", "signup", "logout", "account/select"))
        val builder = Request.Builder().url("${AppConfig.KINDLYVPN_PUBLIC_ORIGIN}/api/auth/$action")
            .header("X-Kindly-Request", "1").header("Accept", "application/json").header("Cache-Control", "no-store")
        sessions.load()?.let { builder.header("Cookie", "kindlyvpn_session=$it") }
        if (body != null) builder.post(body.toString().toRequestBody("application/json".toMediaType()))
        val request = builder.build()
        return client.newCall(request).execute().use { response ->
            val text = response.body.string()
            val data = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (!response.isSuccessful) {
                error(data.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                    ?: "Account service HTTP ${response.code}")
            }
            if (action == "login" || action == "signup") {
                val token = sessionToken(request.url, response.headers)
                    ?: error("Account service did not return a session")
                require(data.optJSONObject("client") != null) { "Invalid account response" }
                sessions.save(token)
            }
            if (action == "me") {
                require(data.opt("authenticated") is Boolean &&
                    (!data.optBoolean("authenticated") || data.optJSONObject("client") != null)) { "Invalid account response" }
                if (!data.getBoolean("authenticated")) sessions.clear()
            }
            if (action == "account/select") require(data.optBoolean("ok") && data.optJSONObject("client") != null) { "Invalid subscription selection response" }
            if (action == "logout") {
                require(data.optBoolean("ok")) { "Sign-out failed; please try again" }
                sessions.clear()
            }
            data
        }
    }
    companion object {
        internal fun sessionToken(url: HttpUrl, headers: Headers): String? = Cookie.parseAll(url, headers)
            .firstOrNull { it.name == "kindlyvpn_session" && it.secure && it.httpOnly &&
                it.matches(url) && it.expiresAt > System.currentTimeMillis() }
            ?.value
    }

}
