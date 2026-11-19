package com.v2ray.ang.simple

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException

internal enum class PanelRequestRoute(val message: String) {
    DIRECT("Обновляем настройки…"),
    DNS("Получаем данные через DNS-туннель… Это может занять больше времени.")
}

/** Every new attempt starts directly; only a transport failure selects DNS. */
internal object PanelRouteFallback {
    suspend fun post(dnsOnly: Boolean = false, onRoute: (PanelRequestRoute?) -> Unit = {},
                     direct: suspend () -> ByteArray, dns: suspend () -> ByteArray): ByteArray {
        fun report(route: PanelRequestRoute?) {
            // An optional UI observer cannot change request or verification semantics.
            try { onRoute(route) } catch (_: Exception) { }
        }
        try {
            currentCoroutineContext().ensureActive()
            if (!dnsOnly) {
                report(PanelRequestRoute.DIRECT)
                currentCoroutineContext().ensureActive()
                try { return direct() }
                catch (_: IOException) { /* HTTP/validation errors are not network failures. */ }
            }
            currentCoroutineContext().ensureActive()
            report(PanelRequestRoute.DNS)
            currentCoroutineContext().ensureActive()
            return dns()
        } finally {
            report(null)
        }
    }
}
