package com.v2ray.ang.core

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/** Editable DNS transport metadata. Subscription credentials belong to the connection. */
data class DnsTunnelSettings(val domain: String, val publicKey: String, val resolvers: List<String>) {
    fun toJson(): String = Gson().toJson(mapOf(
        "version" to 1,
        "listen" to "127.0.0.1:${DnsTunnelProfiles.PORT}",
        "profiles" to listOf(mapOf(
            "id" to "dns-tunnel", "type" to "dnstt",
            "dns" to mapOf("domain" to domain, "public_key" to publicKey, "resolvers" to resolvers),
        )),
    ))

    companion object {
        const val MAX_BYTES = 65_536

        fun fromFields(domain: String, publicKey: String, resolvers: String): DnsTunnelSettings {
            var zone = domain.trim().lowercase().removeSuffix(".")
            if (zone.startsWith("https://") || zone.startsWith("http://")) {
                val uri = URI(zone)
                require(uri.rawPath in listOf("", "/") && uri.rawQuery == null &&
                    uri.rawFragment == null && uri.userInfo == null && uri.port == -1)
                zone = uri.host.orEmpty()
            }
            require(zone.length in 3..80 && zone.contains('.') && zone.split('.').all {
                it.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"))
            })
            val key = publicKey.trim().lowercase()
            require(key.matches(Regex("[a-f0-9]{64}")))
            val endpoints = resolvers.trim().split(Regex("[\\s,;]+")).filter(String::isNotEmpty)
                .map(::resolverEndpoint).distinct()
            require(endpoints.size in 1..4)
            return DnsTunnelSettings(zone, key, endpoints)
        }

        fun fromJson(raw: String): DnsTunnelSettings {
            require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
            val root = JsonParser.parseString(raw).asJsonObject
            require(root["version"].asString == "1")
            val routes = root.getAsJsonArray("profiles")
            require(routes.size() == 1)
            val route = routes[0].asJsonObject
            require(route["type"].asString == "dnstt")
            val dns = route.getAsJsonObject("dns")
            return fromFields(dns["domain"].asString, dns["public_key"].asString,
                dns.getAsJsonArray("resolvers").joinToString("\n") { it.asString })
        }

        private fun resolverEndpoint(value: String): String {
            val endpoint = when {
                value.startsWith('[') -> if (value.endsWith(']')) "$value:53" else value
                value.count { it == ':' } > 1 -> "[$value]:53"
                ':' !in value -> "$value:53"
                else -> value
            }
            val uri = URI("udp://$endpoint")
            require(uri.userInfo == null && uri.rawPath.isEmpty() && uri.rawQuery == null && uri.rawFragment == null)
            require(uri.port in 1..65535)
            val host = uri.host?.removeSurrounding("[", "]") ?: error("IP address required")
            if (':' in host) {
                // Restrict input to IPv6 syntax before calling InetAddress; no DNS lookup.
                require(host.matches(Regex("[a-fA-F0-9:]+")))
                require(InetAddress.getByName(host) is Inet6Address)
            } else {
                val octets = host.split('.')
                require(octets.size == 4 && octets.all {
                    it.matches(Regex("0|[1-9][0-9]{0,2}")) && it.toInt() in 0..255
                })
            }
            return if (':' in host) "[${host.lowercase()}]:${uri.port}" else "$host:${uri.port}"
        }
    }
}
