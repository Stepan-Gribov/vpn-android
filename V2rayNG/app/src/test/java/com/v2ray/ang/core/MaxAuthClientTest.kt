package com.v2ray.ang.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MaxAuthClientTest {
    @Test
    fun normalizesInternationalPhoneNumbers() {
        assertEquals("+79991234567", MaxAuthClient.normalizePhone("+7 (999) 123-45-67"))
        assertEquals("+79991234567", MaxAuthClient.normalizePhone("7 999 123 45 67"))
    }

    @Test
    fun rejectsMalformedPhoneNumbers() {
        assertNull(MaxAuthClient.normalizePhone("+7abc123"))
        assertNull(MaxAuthClient.normalizePhone("123"))
    }

    @Test
    fun acceptsOnlyDecimalPeerIds() {
        assertEquals("123456789", MaxAuthClient.normalizePeerID(" 123456789 "))
        assertNull(MaxAuthClient.normalizePeerID("user-123"))
        assertNull(MaxAuthClient.normalizePeerID(""))
    }
}
