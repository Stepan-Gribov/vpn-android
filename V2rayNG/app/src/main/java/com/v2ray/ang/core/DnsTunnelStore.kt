package com.v2ray.ang.core

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** DNS metadata is encrypted separately from the subscriber's connection credentials. */
object DnsTunnelStore {
    private const val MANUAL_KEY = "dns_tunnel_manual_settings_v1"
    class Unavailable : IOException()
    private fun store(context: Context) = AccountSessionStore(context, "dns-configuration")
    private val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    fun configured(context: Context): Boolean = store(context).load() != null
    fun automatic(context: Context): Boolean = configured(context) &&
        !MmkvManager.decodeSettingsBool(MANUAL_KEY, false)
    fun load(context: Context): String = store(context).load() ?: throw Unavailable()

    /** Old DNS-only imports can be edited and saved in the new screen. */
    fun settings(context: Context): DnsTunnelSettings? = runCatching {
        val raw = store(context).load()
            ?: AccountSessionStore(context, "recovery-configuration").load()
            ?: return null
        DnsTunnelSettings.fromJson(raw)
    }.getOrNull()

    @Synchronized fun save(context: Context, settings: DnsTunnelSettings, automatic: Boolean = false) {
        val validated = DnsTunnelManager.validate(context, settings.toJson())
        store(context).save(validated)
        MmkvManager.encodeSettings(MANUAL_KEY, !automatic)
        DnsTunnelManager.resetFailure()
    }

    /** A failed public refresh must preserve the last working configuration. */
    @Synchronized fun refresh(context: Context) {
        if (MmkvManager.decodeSettingsBool(MANUAL_KEY, false) && configured(context)) return
        runCatching { save(context, download(), automatic = true) }
    }

    fun download(): DnsTunnelSettings {
        val request = Request.Builder().url(AppConfig.KINDLYVPN_DNS_RECOVERY_URL)
            .header("Accept", "application/json").header("Cache-Control", "no-store").build()
        return client.newCall(request).execute().use { response ->
            if (response.code == 404) throw Unavailable()
            require(response.code == 200 && response.header("Content-Type")?.startsWith("application/json") == true)
            val body = response.body
            require(body.contentLength() <= DnsTunnelSettings.MAX_BYTES)
            DnsTunnelSettings.fromJson(body.byteStream().use(::readConfiguration))
        }
    }

    fun readConfiguration(input: InputStream): String {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= DnsTunnelSettings.MAX_BYTES)
            output.write(buffer, 0, count)
        }
        return output.toString("UTF-8")
    }
}
