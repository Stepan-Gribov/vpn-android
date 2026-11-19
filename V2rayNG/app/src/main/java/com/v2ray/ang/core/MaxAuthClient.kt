package com.v2ray.ang.core

import okhttp3.OkHttpClient
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Minimal MAX WebSocket login used only to acquire a reusable account token. */
object MaxAuthClient {
    private const val ENDPOINT = "wss://ws-api.oneme.ru/websocket"
    private const val ORIGIN = "https://web.max.ru"
    private const val TIMEOUT_SECONDS = 30L

    sealed interface AuthResult {
        data class LoggedIn(val token: String, val externalUserID: String? = null) : AuthResult
        data object RequiresPassword : AuthResult
    }

    data class CodeRequest(val verificationToken: String, val codeLength: Int)

    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .build()

    fun normalizePhone(value: String): String? {
        val normalized = value.filterNot { it.isWhitespace() || it == '-' || it == '(' || it == ')' }
            .let { if (it.startsWith('+')) it else "+$it" }
        return normalized.takeIf {
            it.length in 9..16 && it.first() == '+' && it.drop(1).all(Char::isDigit)
        }
    }

    fun normalizePeerID(value: String): String? = value.trim().takeIf {
        it.isNotEmpty() && it.all(Char::isDigit)
    }

    fun requestCode(phone: String): CodeRequest = MaxSession().use { session ->
        session.connect()
        session.hello()
        val payload = session.request(
            sequence = 1,
            opcode = 17,
            payload = JSONObject()
                .put("phone", normalizePhone(phone) ?: error("Invalid MAX phone number"))
                .put("type", "START_AUTH")
                .put("language", "ru"),
        )
        val token = payload.optString("token").takeIf { it.isNotBlank() }
            ?: error("MAX did not return a verification token")
        CodeRequest(token, payload.optInt("codeLength", 6).coerceIn(4, 8))
    }

    fun confirmCode(verificationToken: String, code: String, password: String?): AuthResult =
        MaxSession().use { session ->
            session.connect()
            session.hello()
            val payload = session.request(
                sequence = 1,
                opcode = 18,
                payload = JSONObject()
                    .put("token", verificationToken)
                    .put("verifyCode", code.trim())
                    .put("authTokenType", "CHECK_CODE"),
            )
            loginToken(payload)?.let { return@use AuthResult.LoggedIn(it) }

            val trackID = findString(payload, "trackId")
            val needsPassword = payload.toString().contains("passwordChallenge", ignoreCase = true) ||
                !trackID.isNullOrBlank()
            if (!needsPassword) error("MAX did not return a login token")
            if (password.isNullOrBlank()) return@use AuthResult.RequiresPassword

            val passwordPayload = session.request(
                sequence = 2,
                opcode = 115,
                payload = JSONObject()
                    .put("trackId", trackID.orEmpty())
                    .put("password", password),
            )
            val token = loginToken(passwordPayload)
                ?: findLongestToken(passwordPayload)
                ?: error("MAX accepted the password but returned no login token")
            AuthResult.LoggedIn(token)
        }

    fun resolveExternalUserID(authToken: String): String {
        val callToken = MaxSession().use { session ->
            session.connect()
            session.hello()
            session.request(
                sequence = 1,
                opcode = 19,
                payload = JSONObject()
                    .put("token", authToken)
                    .put("interactive", false)
                    .put("chatsCount", 0)
                    .put("chatsSync", 0)
                    .put("contactsSync", 0)
                    .put("presenceSync", 0)
                    .put("draftsSync", 0),
            )
            session.request(2, 158, JSONObject()).optString("token")
                .takeIf { it.isNotBlank() }
                ?: error("MAX did not return a calls token")
        }

        val sessionData = JSONObject()
            .put("auth_token", callToken)
            .put("client_type", "SDK_JS")
            .put("client_version", "1.1")
            .put("device_id", UUID.randomUUID().toString())
            .put("version", 3)
        val body = FormBody.Builder()
            .add("method", "auth.anonymLogin")
            .add("format", "JSON")
            .add("application_key", "CNHIJPLGDIHBABABA")
            .add("session_data", sessionData.toString())
            .build()
        val request = Request.Builder()
            .url("https://calls.okcdn.ru/fb.do")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "MAX calls login returned HTTP ${response.code}" }
            val payload = JSONObject(response.body.string())
            if (payload.has("error") || payload.has("error_msg")) {
                error("MAX calls login failed: ${payload.opt("error_msg") ?: payload.opt("error")}")
            }
            return payload.optString("external_user_id").takeIf { it.isNotBlank() }
                ?: error("MAX calls login returned no external user ID")
        }
    }

    private fun loginToken(payload: JSONObject): String? = payload
        .optJSONObject("tokenAttrs")
        ?.optJSONObject("LOGIN")
        ?.optString("token")
        ?.takeIf { it.isNotBlank() }

    private fun findString(value: Any?, key: String): String? {
        when (value) {
            is JSONObject -> {
                value.optString(key).takeIf { it.isNotBlank() }?.let { return it }
                val keys = value.keys()
                while (keys.hasNext()) {
                    findString(value.opt(keys.next()), key)?.let { return it }
                }
            }
        }
        return null
    }

    private fun findLongestToken(payload: JSONObject): String? = Regex("[A-Za-z0-9_.+~=\\-]{100,}")
        .findAll(payload.toString())
        .map { it.value }
        .maxByOrNull(String::length)

    private class MaxSession : WebSocketListener(), Closeable {
        private sealed interface Event {
            data class Message(val text: String) : Event
            data class Failure(val error: Throwable) : Event
        }

        private val opened = CountDownLatch(1)
        private val events = LinkedBlockingQueue<Event>()
        private var webSocket: WebSocket? = null
        private var openFailure: Throwable? = null

        fun connect() {
            val request = Request.Builder()
                .url(ENDPOINT)
                .header("Origin", ORIGIN)
                .build()
            webSocket = client.newWebSocket(request, this)
            if (!opened.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                error("MAX login connection timed out")
            }
            openFailure?.let { throw IOException("MAX login connection failed", it) }
        }

        fun hello() {
            request(
                sequence = 0,
                opcode = 6,
                payload = JSONObject()
                    .put(
                        "userAgent",
                        JSONObject()
                            .put("deviceType", "WEB")
                            .put("locale", "ru")
                            .put("deviceLocale", "ru")
                            .put("osVersion", "Android")
                            .put("deviceName", "KindlyVPN")
                            .put(
                                "headerUserAgent",
                                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                                    "Chrome/142.0.0.0 Mobile Safari/537.36",
                            )
                            .put("appVersion", "25.11.2")
                            .put("screen", "1080x1920 1.0x")
                            .put("timezone", "Europe/Moscow"),
                    )
                    .put("deviceId", UUID.randomUUID().toString()),
            )
        }

        fun request(sequence: Int, opcode: Int, payload: JSONObject): JSONObject {
            val message = JSONObject()
                .put("seq", sequence)
                .put("opcode", opcode)
                .put("payload", payload)
                .put("ver", 11)
                .put("cmd", 0)
            check(webSocket?.send(message.toString()) == true) { "MAX login socket is closed" }

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
            while (true) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) error("MAX login response timed out")
                when (val event = events.poll(remaining, TimeUnit.NANOSECONDS)
                    ?: error("MAX login response timed out")) {
                    is Event.Failure -> throw IOException("MAX login socket failed", event.error)
                    is Event.Message -> {
                        val response = JSONObject(event.text)
                        if (response.optInt("seq", -1) != sequence || response.optInt("cmd", -1) != 1) {
                            continue
                        }
                        val responsePayload = response.optJSONObject("payload")
                            ?: error("MAX returned an invalid response")
                        if (responsePayload.has("error") && !responsePayload.isNull("error")) {
                            error("MAX: ${responsePayload.opt("error")}")
                        }
                        return responsePayload
                    }
                }
            }
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened.countDown()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            events.offer(Event.Message(text))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            openFailure = t
            opened.countDown()
            events.offer(Event.Failure(t))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            events.offer(Event.Failure(IOException("MAX closed the socket: $code $reason")))
        }

        override fun close() {
            webSocket?.close(1000, null)
            webSocket = null
        }
    }
}
