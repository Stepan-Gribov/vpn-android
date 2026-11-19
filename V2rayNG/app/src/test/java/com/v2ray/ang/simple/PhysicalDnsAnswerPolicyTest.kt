package com.v2ray.ang.simple

import com.v2ray.ang.core.DnsHealthQuery
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class PhysicalDnsAnswerPolicyTest {
    private fun query(host: String = "api.example.com", type: Int = 1) =
        DnsHealthQuery.query(host, 42).apply { this[size - 3] = type.toByte() }

    private fun answer(q: ByteArray, addresses: List<String>): ByteArray {
        val question = q.copyOfRange(12, q.size)
        return ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { out ->
            out.writeShort(42); out.writeShort(0x8180); out.writeShort(1)
            out.writeShort(addresses.size); out.writeShort(0); out.writeShort(0); out.write(question)
            addresses.forEach { ip ->
                val data = InetAddress.getByName(ip).address
                out.writeShort(0xc00c); out.writeShort(if (data.size == 4) 1 else 28)
                out.writeShort(1); out.writeInt(60); out.writeShort(data.size); out.write(data)
            }
        } }.toByteArray()
    }

    @Test fun publicLoopbackBlockAnswerTriggersFallback() {
        val q = query()
        assertFalse(PhysicalDnsAnswerPolicy.accepts(q, answer(q, listOf("127.0.0.2"))))
        assertTrue(PhysicalDnsAnswerPolicy.accepts(q, answer(q, listOf("57.144.244.192"))))
    }
    @Test fun mixedAnswerCannotHideSyntheticAddress() {
        val q = query()
        assertFalse(PhysicalDnsAnswerPolicy.accepts(q, answer(q, listOf("57.144.244.192", "127.0.0.2"))))
    }
    @Test fun unspecifiedAddressesAreNotSuccessfulPublicResolution() {
        for (ip in listOf("0.0.0.0", "0.0.0.2", "::", "::1")) {
            val q = query(type = if (ip.contains(':')) 28 else 1)
            assertFalse(ip, PhysicalDnsAnswerPolicy.accepts(q, answer(q, listOf(ip))))
        }
    }
    @Test fun validLanAndPublicV6RemainDirectDnsAnswers() {
        for (ip in listOf("10.0.0.1", "192.168.1.2", "172.16.0.2", "fd00::1", "2606:4700:4700::1111")) {
            val q = query(type = if (ip.contains(':')) 28 else 1)
            assertTrue(ip, PhysicalDnsAnswerPolicy.accepts(q, answer(q, listOf(ip))))
        }
    }
    @Test fun explicitLocalNamesMayUseLoopback() {
        for (name in listOf("localhost", "app.localhost", "router.local", "dev.lan", "db.internal", "workstation")) {
            val q = query(name)
            assertTrue(name, PhysicalDnsAnswerPolicy.accepts(q, answer(q, listOf("127.0.0.1"))))
        }
    }
    @Test fun mismatchedQuestionOrIdCannotBeAccepted() {
        val q = query()
        assertFalse(PhysicalDnsAnswerPolicy.accepts(q, answer(query("other.example.com"), listOf("1.1.1.1"))))
        assertFalse(PhysicalDnsAnswerPolicy.accepts(q, answer(q, listOf("1.1.1.1")).apply { this[1] = 43 }))
    }
    @Test fun malformedCompressionAndTruncatedPacketsFailClosed() {
        val q = query()
        val valid = answer(q, listOf("1.1.1.1"))
        assertFalse(PhysicalDnsAnswerPolicy.accepts(q, valid.copyOf(valid.size - 1)))
        assertFalse(PhysicalDnsAnswerPolicy.accepts(q, valid.copyOf().apply { this[12] = 0xc0.toByte(); this[13] = 12 }))
        assertFalse(PhysicalDnsAnswerPolicy.accepts(q, byteArrayOf()))
    }
    @Test fun nxdomainAndEmptyResponsesKeepExistingFallbackSemantics() {
        val q = query()
        assertTrue(PhysicalDnsAnswerPolicy.accepts(q, answer(q, emptyList())))
        assertTrue(PhysicalDnsAnswerPolicy.accepts(q, answer(q, emptyList()).apply { this[3] = 0x83.toByte() }))
    }
}
