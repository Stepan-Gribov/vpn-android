package com.v2ray.ang.dto.entities

import com.v2ray.ang.enums.EConfigType

data class ProfileItem(
    val configVersion: Int = 4,
    val configType: EConfigType,
    var subscriptionId: String = "",
    var addedTime: Long = System.currentTimeMillis(),

    var remarks: String = "",
    var description: String? = null,
    var server: String? = null,
    var serverPort: String? = null,

    var password: String? = null,
    var method: String? = null,
    var flow: String? = null,
    var username: String? = null,

    var network: String? = null,
    var headerType: String? = null,
    var host: String? = null,
    var path: String? = null,
    var seed: String? = null,
    var kcpMtu: Int? = null,
    var kcpTti: Int? = null,

    var quicSecurity: String? = null,
    var quicKey: String? = null,
    var mode: String? = null,
    var serviceName: String? = null,
    var authority: String? = null,
    var xhttpMode: String? = null,
    var xhttpExtra: String? = null,
    var finalMask: String? = null,
    var security: String? = null,
    var sni: String? = null,
    var alpn: String? = null,
    var fingerPrint: String? = null,
    var insecure: Boolean? = null,
    var echConfigList: String? = null,
    var verifyPeerCertByName: String? = null,
    var pinnedCA256: String? = null,

    var publicKey: String? = null,
    var shortId: String? = null,
    var spiderX: String? = null,
    var mldsa65Verify: String? = null,

    var secretKey: String? = null,
    var preSharedKey: String? = null,
    var localAddress: String? = null,
    var reserved: String? = null,
    var mtu: Int? = null,
    var amneziaWgConfig: String? = null,

    var pinSHA256: String? = null,

    var obfsPassword: String? = null,
    var portHopping: String? = null,
    var portHoppingInterval: String? = null,
    var bandwidthDown: String? = null,
    var bandwidthUp: String? = null,

    var policyGroupType: String? = null,
    var policyGroupSubscriptionId: String? = null,
    var policyGroupFilter: String? = null,
    var proxyChainProfiles: String? = null,

    var browserDialerMode: String? = null,

    ) {
    companion object {
        fun create(configType: EConfigType): ProfileItem {
            return ProfileItem(configType = configType)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProfileItem) return false
        val obj = other

        return (this.configType == obj.configType
                && this.server == obj.server
                && this.serverPort == obj.serverPort
                && this.password == obj.password
                && this.method == obj.method
                && this.flow == obj.flow
                && this.username == obj.username

                && this.network == obj.network
                && this.headerType == obj.headerType
                && this.host == obj.host
                && this.path == obj.path
                && this.seed == obj.seed
                && this.kcpMtu == obj.kcpMtu
                && this.kcpTti == obj.kcpTti
                && this.quicSecurity == obj.quicSecurity
                && this.quicKey == obj.quicKey
                && this.mode == obj.mode
                && this.serviceName == obj.serviceName
                && this.authority == obj.authority
                && this.xhttpMode == obj.xhttpMode
                && this.xhttpExtra == obj.xhttpExtra
                && this.finalMask == obj.finalMask

                && this.security == obj.security
                && this.sni == obj.sni
                && this.alpn == obj.alpn
                && this.fingerPrint == obj.fingerPrint
                && this.insecure == obj.insecure
                && this.echConfigList == obj.echConfigList
                && this.verifyPeerCertByName == obj.verifyPeerCertByName
                && this.publicKey == obj.publicKey
                && this.shortId == obj.shortId
                && this.spiderX == obj.spiderX
                && this.mldsa65Verify == obj.mldsa65Verify

                && this.secretKey == obj.secretKey
                && this.preSharedKey == obj.preSharedKey
                && this.localAddress == obj.localAddress
                && this.reserved == obj.reserved
                && this.mtu == obj.mtu
                && this.amneziaWgConfig == obj.amneziaWgConfig

                && this.pinnedCA256 == obj.pinnedCA256
                && this.pinSHA256 == obj.pinSHA256
                && this.obfsPassword == obj.obfsPassword
                && this.portHopping == obj.portHopping
                && this.portHoppingInterval == obj.portHoppingInterval
                && this.bandwidthDown == obj.bandwidthDown
                && this.bandwidthUp == obj.bandwidthUp
                && this.policyGroupType == obj.policyGroupType
                && this.policyGroupSubscriptionId == obj.policyGroupSubscriptionId
                && this.policyGroupFilter == obj.policyGroupFilter
                && this.proxyChainProfiles == obj.proxyChainProfiles
                && this.browserDialerMode == obj.browserDialerMode
                )
    }

    // Keep hashing aligned with connection equality. Display labels, storage version,
    // creation time and the profile's subscription membership are metadata; policy
    // group selectors determine its outbound connections and remain part of equality.
    override fun hashCode(): Int = listOf(
        configType, server, serverPort, password, method, flow, username,
        network, headerType, host, path, seed, kcpMtu, kcpTti, quicSecurity,
        quicKey, mode, serviceName, authority, xhttpMode, xhttpExtra, finalMask,
        security, sni, alpn, fingerPrint, insecure, echConfigList, verifyPeerCertByName,
        publicKey, shortId, spiderX, mldsa65Verify, secretKey, preSharedKey,
        localAddress, reserved, mtu, amneziaWgConfig, pinnedCA256, pinSHA256, obfsPassword,
        portHopping, portHoppingInterval, bandwidthDown, bandwidthUp, policyGroupType,
        policyGroupSubscriptionId, policyGroupFilter, proxyChainProfiles, browserDialerMode,
    ).hashCode()
}
