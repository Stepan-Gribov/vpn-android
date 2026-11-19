package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import java.net.URI

/** Pure URL and bearer-token policy shared by the subscription purchase UI. */
object PurchaseFlow {
    private val subscriptionToken = Regex("^[A-Za-z0-9_-]{1,64}$")
    private val trustedOrigin = URI(AppConfig.KINDLYVPN_PUBLIC_ORIGIN)

    /**
     * Extract the RemnaWave shortUuid only from our canonical HTTPS
     * subscription URL.  A token imported from another provider must never
     * be sent to KindlyVPN's checkout endpoint.
     */
    fun extractSubscriptionToken(subscriptionUrl: String?): String? {
        val uri = parse(subscriptionUrl) ?: return null
        if (!isTrustedOrigin(uri)) return null
        val segments = uri.path.orEmpty().trim('/').split('/')
        if (segments.size != 3 || segments[0] != "api" || segments[1] != "sub") {
            return null
        }
        return segments[2].takeIf(subscriptionToken::matches)
    }

    fun isSubscriptionUrl(url: String?): Boolean = extractSubscriptionToken(url) != null

    /** The website's "back to plans" link returns to the native catalogue. */
    fun isCatalogUrl(url: String?): Boolean {
        val uri = parse(url) ?: return false
        if (!isTrustedOrigin(uri)) return false
        return uri.path.orEmpty().trimEnd('/') == "/buy"
    }

    /** Only the production HTTPS origin may stay inside the payment WebView. */
    fun isTrustedWebUrl(url: String?): Boolean {
        val uri = parse(url) ?: return false
        return isTrustedOrigin(uri)
    }

    /** Schemes which must never be handed to another application. */
    fun isBlockedExternalScheme(url: String?): Boolean {
        val scheme = parse(url)?.scheme?.lowercase() ?: return true
        return scheme in setOf("file", "content", "javascript", "data", "about")
    }

    private fun isTrustedOrigin(uri: URI): Boolean =
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals(trustedOrigin.host, ignoreCase = true) &&
            effectivePort(uri) == effectivePort(trustedOrigin)

    private fun effectivePort(uri: URI): Int = if (uri.port >= 0) uri.port else 443

    private fun parse(value: String?): URI? = value
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let { runCatching { URI(it) }.getOrNull() }
}
