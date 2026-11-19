package com.v2ray.ang.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PurchaseFlowTest {
    @Test
    fun extractsTokenOnlyFromCanonicalSubscriptionUrl() {
        assertEquals(
            "AbCdEf_0123-xyz",
            PurchaseFlow.extractSubscriptionToken(
                "https://vpnapp.example.invalid//api/sub/AbCdEf_0123-xyz?client=android",
            ),
        )
        assertEquals(
            "AbCdEf_0123-xyz",
            PurchaseFlow.extractSubscriptionToken(
                "https://vpnapp.example.invalid//api/sub/AbCdEf_0123-xyz/",
            ),
        )
    }

    @Test
    fun refusesToExfiltrateOtherProvidersSubscriptionTokens() {
        assertNull(
            PurchaseFlow.extractSubscriptionToken(
                "https://other-vpn.example/api/sub/SecretToken123",
            ),
        )
        assertNull(
            PurchaseFlow.extractSubscriptionToken(
                "http://panel.example.invalid/api/sub/SecretToken123",
            ),
        )
        assertNull(
            PurchaseFlow.extractSubscriptionToken(
                "https://vpnapp.example.invalid/.evil.example/api/sub/SecretToken123",
            ),
        )
    }

    @Test
    fun rejectsMalformedOrUnexpectedSubscriptionPaths() {
        assertNull(PurchaseFlow.extractSubscriptionToken(null))
        assertNull(PurchaseFlow.extractSubscriptionToken("not a url"))
        assertNull(
            PurchaseFlow.extractSubscriptionToken(
                "https://vpnapp.example.invalid//api/sub/../../admin",
            ),
        )
        assertNull(
            PurchaseFlow.extractSubscriptionToken(
                "https://vpnapp.example.invalid//buy/Token123",
            ),
        )
    }

    @Test
    fun paymentWebViewTrustIsExactHttpsOrigin() {
        assertTrue(
            PurchaseFlow.isTrustedWebUrl(
                "https://vpnapp.example.invalid//pay/012345?lang=ru",
            ),
        )
        assertFalse(PurchaseFlow.isTrustedWebUrl("http://panel.example.invalid/pay/x"))
        assertFalse(PurchaseFlow.isTrustedWebUrl("https://vpnapp.example.invalid/:444/pay/x"))
        assertFalse(PurchaseFlow.isTrustedWebUrl("https://qr.nspk.ru/payment"))
    }

    @Test
    fun websiteBackToPlansReturnsToNativeCatalog() {
        assertTrue(PurchaseFlow.isCatalogUrl("https://vpnapp.example.invalid//buy/"))
        assertTrue(PurchaseFlow.isCatalogUrl("https://vpnapp.example.invalid//buy?lang=ru"))
        assertFalse(PurchaseFlow.isCatalogUrl("https://vpnapp.example.invalid//pay/ref"))
        assertFalse(PurchaseFlow.isCatalogUrl("https://evil.example/buy/"))
    }

    @Test
    fun dangerousExternalSchemesAreBlocked() {
        for (url in listOf(
            "file:///data/local/tmp/secret",
            "content://com.example/private",
            "javascript:alert(1)",
            "data:text/html,hello",
            "about:blank",
        )) {
            assertTrue(url, PurchaseFlow.isBlockedExternalScheme(url))
        }
        assertFalse(PurchaseFlow.isBlockedExternalScheme("https://qr.nspk.ru/payment"))
        assertFalse(PurchaseFlow.isBlockedExternalScheme("intent://pay#Intent;scheme=bank;end"))
    }
}
