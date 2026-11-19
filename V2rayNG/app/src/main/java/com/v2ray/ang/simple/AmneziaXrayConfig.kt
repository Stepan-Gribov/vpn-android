package com.v2ray.ang.simple

import com.google.gson.JsonParser

object AmneziaXrayConfig {
    fun replaceProxy(config: String, outbound: String): String {
        val root = JsonParser.parseString(config).asJsonObject
        val proxy = JsonParser.parseString(outbound).asJsonObject
        require(proxy.get("protocol")?.asString in listOf("vless", "vmess", "trojan", "shadowsocks", "wireguard") && !proxy.has("proxySettings"))
        proxy.addProperty("tag", "proxy")
        val outbounds = root.getAsJsonArray("outbounds")
        val index = outbounds.indexOfFirst { it.asJsonObject.get("tag")?.asString == "proxy" }
        require(index >= 0)
        outbounds.set(index, proxy)
        return root.toString()
    }
}
