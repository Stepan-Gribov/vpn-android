package com.v2ray.ang.core

import android.content.Context
import android.os.SystemClock
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.ArrayDeque
import kotlin.concurrent.thread

enum class ManagedDnsTunnelTransport(val storageValue: String) {
    DNSTT("dnstt"),
    SLIPSTREAM("slipstream");

    companion object {
        fun fromStorage(value: String?): ManagedDnsTunnelTransport = entries.firstOrNull {
            it.storageValue == value
        } ?: DNSTT
    }
}

enum class ManagedDnsTunnelType(val storageValue: String) {
    SOCKS5("socks5"),
    SSH("ssh");

    companion object {
        fun fromStorage(value: String?): ManagedDnsTunnelType = entries.firstOrNull {
            it.storageValue == value
        } ?: SOCKS5
    }
}

data class ManagedDnsTunnelSettings(
    val name: String,
    val transport: ManagedDnsTunnelTransport,
    val tunnelType: ManagedDnsTunnelType,
    val domain: String,
    val resolver: String,
    val publicKey: String,
    val congestionControl: String,
    val keepAliveInterval: Int,
    val gsoEnabled: Boolean,
    val automaticResolverCapture: Boolean,
    val sshUsername: String,
    val sshPassword: String,
    val sshPrivateKey: String,
    val sessionCount: Int = 1,
)

/** Owns DNSTT/Slipstream and exposes one managed local SOCKS profile to Xray. */
object ManagedDnsTunnelManager {
    const val HELPER_VERSION = "DNSTT 1.20260501-pool.1 · Slipstream 2.2.0"
    const val READY_TIMEOUT_MILLIS = 45_000L
    const val DEFAULT_RESOLVER = "1.1.1.1:53"
    val SESSION_COUNTS = listOf(1, 2, 4, 8, 16)
    const val DEFAULT_SESSION_COUNT = 1
    const val MAX_QUERIES_PER_SECOND = 800
    const val DEFAULT_KEEPALIVE_MILLIS = 400

    private const val DNSTT_HELPER_FILE = "libdnstt.so"
    private const val SLIPSTREAM_HELPER_FILE = "libslipstream_client.so"
    private const val MANAGED_PROFILE_GUID = "kindlyvpn_managed_dnstt"
    private const val MAX_LOG_LINES = 60
    private const val MAX_SAVED_RESOLVERS = 32

    private var process: Process? = null
    private var sshClient: ManagedDnsTunnelSshClient? = null
    private var activeSignature: String? = null
    private val readiness = ManagedDnsTunnelReadiness()
    private var finalizationStarted = false
    private var processGeneration = 0L
    private val recentLog = ArrayDeque<String>()

    fun isManagedDnsTunnelProfile(profile: ProfileItem?): Boolean {
        if (profile?.configType != EConfigType.SOCKS) return false
        val host = profile.server?.trim()?.lowercase()
        return (host == AppConfig.DNS_TUNNEL_LOCAL_ADDRESS || host == "localhost") &&
            profile.serverPort == AppConfig.DNS_TUNNEL_LOCAL_PORT
    }

    fun getSettings(): ManagedDnsTunnelSettings = ManagedDnsTunnelSettings(
        name = MmkvManager.decodeSettingsString(AppConfig.PREF_DNS_TUNNEL_NAME, "DNS Tunnel")
            .orEmpty().trim().ifBlank { "DNS Tunnel" },
        transport = ManagedDnsTunnelTransport.fromStorage(
            MmkvManager.decodeSettingsString(AppConfig.PREF_DNS_TUNNEL_TRANSPORT),
        ),
        tunnelType = ManagedDnsTunnelType.fromStorage(
            MmkvManager.decodeSettingsString(AppConfig.PREF_DNS_TUNNEL_TYPE),
        ),
        domain = MmkvManager.decodeSettingsString(AppConfig.PREF_DNS_TUNNEL_DOMAIN, "")
            .orEmpty().trim(),
        resolver = com.v2ray.ang.simple.SimpleRuntime.effectiveResolver(MmkvManager.decodeSettingsString(
            AppConfig.PREF_DNS_TUNNEL_RESOLVER, DEFAULT_RESOLVER,
        ).orEmpty().ifBlank { DEFAULT_RESOLVER }),
        publicKey = MmkvManager.decodeSettingsString(AppConfig.PREF_DNS_TUNNEL_PUBLIC_KEY, "")
            .orEmpty().trim(),
        congestionControl = MmkvManager.decodeSettingsString(
            AppConfig.PREF_DNS_TUNNEL_CONGESTION,
            "dcubic",
        ).orEmpty().takeIf { it == "dcubic" || it == "bbr" } ?: "dcubic",
        keepAliveInterval = MmkvManager.decodeSettingsInt(
            AppConfig.PREF_DNS_TUNNEL_KEEPALIVE,
            DEFAULT_KEEPALIVE_MILLIS,
        ).coerceIn(50, 60_000),
        gsoEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_DNS_TUNNEL_GSO, false),
        automaticResolverCapture = MmkvManager.decodeSettingsBool(
            AppConfig.PREF_DNS_TUNNEL_AUTO_CAPTURE_RESOLVER,
            false,
        ),
        sshUsername = MmkvManager.decodeSettingsString(
            AppConfig.PREF_DNS_TUNNEL_SSH_USERNAME,
            "",
        ).orEmpty().trim(),
        sshPassword = ManagedDnsTunnelCredentialStore.loadPassword(),
        sshPrivateKey = ManagedDnsTunnelCredentialStore.loadPrivateKey(),
        sessionCount = normalizeSessionCount(MmkvManager.decodeSettingsInt(
            AppConfig.PREF_DNS_TUNNEL_SESSIONS, DEFAULT_SESSION_COUNT,
        )),
    )

    internal fun normalizeSessionCount(value: Int): Int =
        value.takeIf { it in SESSION_COUNTS } ?: DEFAULT_SESSION_COUNT

    fun getDomain(): String = getSettings().domain
    fun getResolver(): String = getSettings().resolver
    fun getPublicKey(): String = getSettings().publicKey

    fun getResolverList(): List<String> {
        val saved = MmkvManager.decodeSettingsString(AppConfig.PREF_DNS_TUNNEL_RESOLVER_LIST)
            ?.let { JsonUtil.fromJsonSafe(it, Array<String>::class.java) }
            ?.asList()
            .orEmpty()
        return (saved + getResolver())
            .mapNotNull(::normalizeResolver)
            .distinct()
            .take(MAX_SAVED_RESOLVERS)
    }

    /** Saves a manually entered or discovered resolver and makes it the active one. */
    fun addResolver(value: String): String? {
        val normalized = normalizeResolver(value) ?: return null
        val values = (listOf(normalized) + getResolverList())
            .distinct()
            .take(MAX_SAVED_RESOLVERS)
        MmkvManager.encodeSettings(AppConfig.PREF_DNS_TUNNEL_RESOLVER_LIST, JsonUtil.toJson(values))
        MmkvManager.encodeSettings(AppConfig.PREF_DNS_TUNNEL_RESOLVER, normalized)
        return normalized
    }

    data class ResolverCapture(val activeResolver: String, val discoveredResolvers: List<String>)

    /** Reads DNS assigned by the physical network, stores all results, and selects the first. */
    fun captureAndApplyCurrentResolver(context: Context): ResolverCapture? {
        val discovered = ManagedDnsTunnelResolverDiscovery.discover(context)
        if (discovered.isEmpty()) return null
        val active = addResolver(discovered.first()) ?: return null
        val allResolvers = (discovered + getResolverList())
            .mapNotNull(::normalizeResolver)
            .distinct()
            .take(MAX_SAVED_RESOLVERS)
        MmkvManager.encodeSettings(
            AppConfig.PREF_DNS_TUNNEL_RESOLVER_LIST,
            JsonUtil.toJson(allResolvers),
        )
        return ResolverCapture(active, discovered)
    }

    fun captureAutomaticallyIfEnabled(context: Context): ResolverCapture? =
        if (isAutomaticResolverCaptureEnabled()) captureAndApplyCurrentResolver(context) else null

    fun isAutomaticResolverCaptureEnabled(): Boolean = MmkvManager.decodeSettingsBool(
        AppConfig.PREF_DNS_TUNNEL_AUTO_CAPTURE_RESOLVER,
        false,
    )

    fun saveSettings(settings: ManagedDnsTunnelSettings) {
        val domain = requireNotNull(normalizeDomain(settings.domain))
        val resolver = requireNotNull(normalizeResolver(settings.resolver, settings.transport))
        val publicKey = if (settings.transport == ManagedDnsTunnelTransport.DNSTT) {
            requireNotNull(normalizePublicKey(settings.publicKey))
        } else {
            settings.publicKey.trim().lowercase()
        }
        require(settings.sessionCount in SESSION_COUNTS)
        require(settings.keepAliveInterval in 50..60_000)
        if (settings.tunnelType == ManagedDnsTunnelType.SSH) {
            require(settings.sshUsername.isNotBlank())
            require(settings.sshPassword.isNotBlank() || settings.sshPrivateKey.isNotBlank())
        }

        MmkvManager.encodeSettings(AppConfig.PREF_DNS_TUNNEL_NAME, settings.name.trim())
        MmkvManager.encodeSettings(AppConfig.PREF_DNS_TUNNEL_TRANSPORT, settings.transport.storageValue)
        MmkvManager.encodeSettings(AppConfig.PREF_DNS_TUNNEL_TYPE, settings.tunnelType.storageValue)
        MmkvManager.encodeSettings(AppConfig.PREF_DNS_TUNNEL_DOMAIN, domain)
        MmkvManager.encodeSettings(AppConfig.PREF_DNS_TUNNEL_RESOLVER, resolver)
        MmkvManager.encodeSettings(AppConfig.PREF_DNS_TUNNEL_PUBLIC_KEY, publicKey)
        MmkvManager.encodeSettings(AppConfig.PREF_DNS_TUNNEL_CONGESTION, settings.congestionControl)
        MmkvManager.encodeSettings(AppConfig.PREF_DNS_TUNNEL_SESSIONS, settings.sessionCount)
        MmkvManager.encodeSettings(AppConfig.PREF_DNS_TUNNEL_KEEPALIVE, settings.keepAliveInterval)
        MmkvManager.encodeSettings(AppConfig.PREF_DNS_TUNNEL_GSO, settings.gsoEnabled)
        MmkvManager.encodeSettings(
            AppConfig.PREF_DNS_TUNNEL_AUTO_CAPTURE_RESOLVER,
            settings.automaticResolverCapture,
        )
        MmkvManager.encodeSettings(AppConfig.PREF_DNS_TUNNEL_SSH_USERNAME, settings.sshUsername.trim())
        ManagedDnsTunnelCredentialStore.savePassword(settings.sshPassword)
        ManagedDnsTunnelCredentialStore.savePrivateKey(settings.sshPrivateKey)
        addResolver(resolver)
    }

    fun isConfigured(profile: ProfileItem?): Boolean {
        if (!isManagedDnsTunnelProfile(profile)) return true
        val settings = getSettings()
        if (normalizeDomain(settings.domain) == null || normalizeResolver(settings.resolver, settings.transport) == null) {
            return false
        }
        if (settings.transport == ManagedDnsTunnelTransport.DNSTT &&
            normalizePublicKey(settings.publicKey) == null
        ) return false
        return settings.tunnelType != ManagedDnsTunnelType.SSH || (
            settings.sshUsername.isNotBlank() &&
                (settings.sshPassword.isNotBlank() || settings.sshPrivateKey.isNotBlank())
            )
    }

    internal fun normalizeDomain(value: String): String? {
        val candidate = value.trim().trimEnd('.').lowercase()
        if (candidate.isBlank() || candidate.length > 253) return null
        val labels = candidate.split('.')
        if (labels.size < 2) return null
        if (labels.any { label ->
                label.isBlank() || label.length > 63 ||
                    label.first() == '-' || label.last() == '-' ||
                    label.any { !it.isLetterOrDigit() && it != '-' }
            }
        ) return null
        return candidate
    }

    internal fun normalizePublicKey(value: String): String? =
        value.trim().lowercase().takeIf { it.matches(Regex("[0-9a-f]{64}")) }

    internal fun normalizeResolver(
        value: String,
        transport: ManagedDnsTunnelTransport = ManagedDnsTunnelTransport.DNSTT,
    ): String? {
        val candidate = value.trim()
        if (candidate.startsWith("https://", ignoreCase = true)) {
            if (transport != ManagedDnsTunnelTransport.DNSTT) return null
            val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
            if (uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.rawFragment != null ||
                uri.rawQuery != null || (uri.port != -1 && uri.port !in 1..65535)
            ) return null
            val path = uri.rawPath.orEmpty().ifEmpty { "/dns-query" }
            return "https://${uri.rawAuthority}$path"
        }
        if (candidate.isBlank() || candidate.any { it.isWhitespace() } ||
            candidate.contains('/') || candidate.contains("//")
        ) return null
        if (candidate.startsWith("[")) {
            val closing = candidate.indexOf(']')
            if (closing <= 1) return null
            val host = candidate.substring(1, closing)
            if (host.any { it !in "0123456789abcdefABCDEF:" }) return null
            val suffix = candidate.substring(closing + 1)
            val port = when {
                suffix.isEmpty() -> 53
                suffix.startsWith(":") -> suffix.drop(1).toIntOrNull()
                else -> null
            } ?: return null
            return "[$host]:${port.takeIf { it in 1..65535 } ?: return null}"
        }
        if (candidate.count { it == ':' } > 1) {
            if (candidate.any { it !in "0123456789abcdefABCDEF:" }) return null
            return "[$candidate]:53"
        }
        val separator = candidate.lastIndexOf(':')
        val host = if (separator >= 0) candidate.substring(0, separator) else candidate
        val port = if (separator >= 0) candidate.substring(separator + 1).toIntOrNull() else 53
        if (host.isBlank() || host.any { !it.isLetterOrDigit() && it !in ".-" }) return null
        if (port == null || port !in 1..65535) return null
        return "$host:$port"
    }

    data class ResolverEndpoint(val host: String, val port: Int)

    fun getResolverEndpoint(): ResolverEndpoint? = resolverEndpoint(getResolver())

    internal fun resolverEndpoint(value: String): ResolverEndpoint? {
        val normalized = normalizeResolver(value) ?: return null
        // HTTPS endpoints are consumed as URLs by Xray, never as UDP port 443.
        if (normalized.startsWith("https://")) return null
        if (normalized.startsWith("[")) {
            val closing = normalized.indexOf(']')
            return ResolverEndpoint(
                normalized.substring(1, closing),
                normalized.substring(closing + 2).toInt(),
            )
        }
        val separator = normalized.lastIndexOf(':')
        return ResolverEndpoint(normalized.substring(0, separator), normalized.substring(separator + 1).toInt())
    }

    /** Creates or refreshes the one managed profile and keeps it immediately below TURN. */
    fun upsertManagedProfile(preferredSubscriptionId: String?): Pair<String, String> {
        val allGuids = MmkvManager.decodeAllServerList().distinct()
        val existingGuid = when {
            MmkvManager.decodeServerConfig(MANAGED_PROFILE_GUID) != null -> MANAGED_PROFILE_GUID
            else -> allGuids.firstOrNull { isManagedDnsTunnelProfile(MmkvManager.decodeServerConfig(it)) }
        }
        val previousProfile = existingGuid?.let(MmkvManager::decodeServerConfig)
        val targetSubscriptionId = resolveTargetSubscription(
            previousProfile?.subscriptionId,
            preferredSubscriptionId,
            allGuids,
        )
        val guid = existingGuid ?: MANAGED_PROFILE_GUID
        previousProfile?.subscriptionId
            ?.takeIf { it != targetSubscriptionId }
            ?.let { previousSubscriptionId ->
                val previousList = MmkvManager.decodeServerList(previousSubscriptionId)
                if (previousList.remove(guid)) MmkvManager.encodeServerList(previousList, previousSubscriptionId)
            }

        val settings = getSettings()
        val transportName = settings.transport.name.lowercase().replaceFirstChar(Char::uppercase)
        val profile = ProfileItem.create(EConfigType.SOCKS).apply {
            subscriptionId = targetSubscriptionId
            remarks = "${settings.name} · $transportName"
            description = "${settings.domain} · ${settings.resolver} · ${settings.tunnelType.name}"
            server = AppConfig.DNS_TUNNEL_LOCAL_ADDRESS
            serverPort = AppConfig.DNS_TUNNEL_LOCAL_PORT
            network = "tcp"
        }
        MmkvManager.encodeServerConfig(guid, profile)

        val targetList = MmkvManager.decodeServerList(targetSubscriptionId)
        targetList.remove(guid)
        val turnIndex = targetList.indexOfFirst {
            VkTurnManager.isTurnProfile(MmkvManager.decodeServerConfig(it))
        }
        targetList.add(if (turnIndex >= 0) turnIndex + 1 else 0, guid)
        MmkvManager.encodeServerList(targetList, targetSubscriptionId)
        return guid to targetSubscriptionId
    }

    private fun resolveTargetSubscription(
        previousSubscriptionId: String?,
        preferredSubscriptionId: String?,
        allGuids: List<String>,
    ): String {
        if (!previousSubscriptionId.isNullOrBlank() &&
            MmkvManager.decodeSubscription(previousSubscriptionId) != null
        ) return previousSubscriptionId
        val turnSubscriptionId = allGuids.firstNotNullOfOrNull { guid ->
            MmkvManager.decodeServerConfig(guid)
                ?.takeIf(VkTurnManager::isTurnProfile)
                ?.subscriptionId
                ?.takeIf(String::isNotBlank)
        }
        if (turnSubscriptionId != null) return turnSubscriptionId
        if (!preferredSubscriptionId.isNullOrBlank() &&
            MmkvManager.decodeSubscription(preferredSubscriptionId) != null
        ) return preferredSubscriptionId
        if (MmkvManager.decodeSubscription(AppConfig.KINDLYVPN_SUBSCRIPTION_ID) != null) {
            return AppConfig.KINDLYVPN_SUBSCRIPTION_ID
        }
        return MmkvManager.decodeSubsList().firstOrNull().orEmpty()
    }

    @Synchronized
    fun ensureForProfile(context: Context, profile: ProfileItem) {
        if (!isManagedDnsTunnelProfile(profile)) {
            stop()
            return
        }
        captureAutomaticallyIfEnabled(context)
        val settings = getSettings()
        val domain = normalizeDomain(settings.domain)
            ?: error(context.getString(R.string.managed_dns_tunnel_invalid_domain))
        val resolver = normalizeResolver(settings.resolver, settings.transport)
            ?: error(context.getString(R.string.managed_dns_tunnel_invalid_resolver))
        val publicKey = if (settings.transport == ManagedDnsTunnelTransport.DNSTT) {
            normalizePublicKey(settings.publicKey)
                ?: error(context.getString(R.string.managed_dns_tunnel_invalid_public_key))
        } else ""
        if (settings.tunnelType == ManagedDnsTunnelType.SSH &&
            (settings.sshUsername.isBlank() ||
                (settings.sshPassword.isBlank() && settings.sshPrivateKey.isBlank()))
        ) error(context.getString(R.string.managed_dns_tunnel_invalid_ssh))

        val normalized = settings.copy(domain = domain, resolver = resolver, publicKey = publicKey)
        val signature = normalized.toString()
        process?.let { running ->
            if (activeSignature == signature && running.isAliveCompat()) return
        }

        stopHelper()
        processGeneration += 1
        val generation = processGeneration
        val helperFile = when (normalized.transport) {
            ManagedDnsTunnelTransport.DNSTT -> DNSTT_HELPER_FILE
            ManagedDnsTunnelTransport.SLIPSTREAM -> SLIPSTREAM_HELPER_FILE
        }
        val helper = File(context.applicationInfo.nativeLibraryDir, helperFile)
        if (!helper.isFile || !helper.canExecute()) {
            error(context.getString(R.string.managed_dns_tunnel_helper_missing, normalized.transport.name))
        }

        recentLog.clear()
        val localPort = if (normalized.tunnelType == ManagedDnsTunnelType.SSH) {
            AppConfig.DNS_TUNNEL_SSH_UPSTREAM_PORT
        } else {
            AppConfig.DNS_TUNNEL_LOCAL_PORT.toInt()
        }
        val command = when (normalized.transport) {
            ManagedDnsTunnelTransport.DNSTT -> dnsttCommand(
                helper.absolutePath,
                resolver,
                publicKey,
                domain,
                localPort,
                normalized.sessionCount,
            )
            ManagedDnsTunnelTransport.SLIPSTREAM -> slipstreamCommand(
                helper.absolutePath,
                normalized,
                localPort,
            )
        }
        val started = ProcessBuilder(command)
            .directory(context.noBackupFilesDir)
            .redirectErrorStream(true)
            .start()
        process = started
        activeSignature = signature
        readiness.reset(
            generation,
            needsPool = normalized.transport == ManagedDnsTunnelTransport.DNSTT,
            needsSsh = normalized.tunnelType == ManagedDnsTunnelType.SSH,
        )
        finalizationStarted = false
        consumeOutput(context.applicationContext, started, generation, normalized)
        if (normalized.transport == ManagedDnsTunnelTransport.SLIPSTREAM) {
            watchSlipstreamReadiness(context.applicationContext, started, generation, normalized, localPort)
        }

        Thread.sleep(200)
        if (!started.isAliveCompat()) {
            val detail = synchronized(recentLog) { recentLog.lastOrNull().orEmpty() }
            process = null
            activeSignature = null
            error(context.getString(R.string.managed_dns_tunnel_start_failed, detail))
        }
        LogUtil.i(AppConfig.TAG, "${normalized.transport.name} helper started via $resolver for $domain")
    }

    internal fun dnsttCommand(
        helperPath: String,
        resolver: String,
        publicKey: String,
        domain: String,
        localPort: Int = AppConfig.DNS_TUNNEL_LOCAL_PORT.toInt(),
        sessionCount: Int = DEFAULT_SESSION_COUNT,
    ): List<String> {
        require(sessionCount in SESSION_COUNTS)
        return listOf(
            helperPath,
            if (normalizeResolver(resolver)?.startsWith("https://") == true) "-doh" else "-udp",
            normalizeResolver(resolver) ?: resolver,
            "-sessions", sessionCount.toString(),
            "-max-qps", MAX_QUERIES_PER_SECOND.toString(),
            "-pubkey",
            normalizePublicKey(publicKey) ?: publicKey,
            normalizeDomain(domain) ?: domain,
            "${AppConfig.DNS_TUNNEL_LOCAL_ADDRESS}:$localPort",
        )
    }

    internal fun slipstreamCommand(
        helperPath: String,
        settings: ManagedDnsTunnelSettings,
        localPort: Int = AppConfig.DNS_TUNNEL_LOCAL_PORT.toInt(),
    ): List<String> = buildList {
        add(helperPath)
        addAll(listOf("--tcp-listen-port", localPort.toString()))
        addAll(listOf("--tcp-listen-host", AppConfig.DNS_TUNNEL_LOCAL_ADDRESS))
        addAll(listOf("--domain", normalizeDomain(settings.domain) ?: settings.domain))
        addAll(listOf("--resolver", normalizeResolver(settings.resolver, settings.transport) ?: settings.resolver))
        addAll(listOf("--congestion-control", settings.congestionControl))
        addAll(listOf("--keep-alive-interval", settings.keepAliveInterval.toString()))
        if (settings.gsoEnabled) add("--gso")
    }

    private fun consumeOutput(
        context: Context,
        started: Process,
        generation: Long,
        settings: ManagedDnsTunnelSettings,
    ) {
        thread(name = "dns-tunnel-output", isDaemon = true) {
            runCatching {
                started.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(recentLog) {
                            if (recentLog.size >= MAX_LOG_LINES) recentLog.removeFirst()
                            recentLog.addLast(line)
                        }
                        LogUtil.i(AppConfig.TAG, "${settings.transport.name} helper: $line")
                        if (settings.transport == ManagedDnsTunnelTransport.DNSTT) {
                            updateTransportReadiness(context, started, generation, settings, line)
                        }
                    }
                }
            }.onFailure { LogUtil.w(AppConfig.TAG, "Failed to read DNS helper output", it) }

            val shouldRecover = synchronized(this) {
                if (generation != processGeneration || process !== started) {
                    false
                } else {
                    process = null
                    activeSignature = null
                    readiness.reset(generation)
                    sshClient?.disconnect()
                    sshClient = null
                    val selected = MmkvManager.getSelectServer()?.let(MmkvManager::decodeServerConfig)
                    SettingsManager.isConnectionDesired() && isManagedDnsTunnelProfile(selected)
                }
            }
            if (shouldRecover) {
                CoreServiceManager.reportConnectionDrop(context, context.getString(R.string.managed_dns_tunnel_stopped))
            }
        }
    }

    private fun watchSlipstreamReadiness(
        context: Context,
        started: Process,
        generation: Long,
        settings: ManagedDnsTunnelSettings,
        port: Int,
    ) {
        thread(name = "slipstream-readiness", isDaemon = true) {
            val deadline = SystemClock.elapsedRealtime() + 12_000L
            while (started.isAliveCompat() && SystemClock.elapsedRealtime() < deadline) {
                if (isLocalPortOpen(port)) {
                    updateTransportReadiness(context, started, generation, settings)
                    return@thread
                }
                Thread.sleep(100L)
            }
        }
    }

    private fun isLocalPortOpen(port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(AppConfig.DNS_TUNNEL_LOCAL_ADDRESS, port), 200)
        }
        true
    }.getOrDefault(false)

    private fun updateTransportReadiness(
        context: Context,
        started: Process,
        generation: Long,
        settings: ManagedDnsTunnelSettings,
        line: String? = null,
    ) {
        val changed = synchronized(this) {
            if (generation != processGeneration || process !== started) return
            val wasReady = readiness.ready
            val accepted = if (line == null) readiness.onListenerReady(generation) else readiness.onLog(generation, line)
            if (!accepted) return
            if (!readiness.transportReady) {
                sshClient?.disconnect()
                sshClient = null
                finalizationStarted = false
            }
            wasReady != readiness.ready
        }
        if (changed) CoreServiceManager.onManagedDnsTunnelReadinessChanged()
        if (settings.tunnelType == ManagedDnsTunnelType.SSH) {
            finalizeSshAsync(context, started, generation, settings)
        }
    }

    private fun finalizeSshAsync(
        context: Context,
        started: Process,
        generation: Long,
        settings: ManagedDnsTunnelSettings,
    ) {
        val epoch = synchronized(this) {
            if (generation != processGeneration || process !== started || finalizationStarted ||
                !readiness.transportReady || readiness.ready
            ) return
            finalizationStarted = true
            readiness.transportEpoch
        }
        thread(name = "dns-tunnel-finalize", isDaemon = true) {
            val client = ManagedDnsTunnelSshClient()
            val connected = client.connect(settings.sshUsername, settings.sshPassword, settings.sshPrivateKey)
            val current = synchronized(this) {
                if (generation == processGeneration && process === started && epoch == readiness.transportEpoch) {
                    finalizationStarted = false
                    if (connected && readiness.onSshReady(generation, epoch)) sshClient = client
                    true
                } else false
            }
            if (!current || !connected) client.disconnect()
            if (current && connected) CoreServiceManager.onManagedDnsTunnelReadinessChanged()
            if (current && !connected) {
                LogUtil.e(AppConfig.TAG, "DNS tunnel SSH stage could not connect")
                runCatching { started.destroy() }
                CoreServiceManager.reportConnectionDrop(
                    context,
                    context.getString(R.string.managed_dns_tunnel_ssh_failed),
                )
            }
        }
    }

    @Synchronized
    fun stop() {
        processGeneration += 1
        stopHelper()
    }

    @Synchronized
    fun stopTestHelperIfIdle() {
        val selected = MmkvManager.getSelectServer()?.let(MmkvManager::decodeServerConfig)
        if (!SettingsManager.isConnectionDesired() || !isManagedDnsTunnelProfile(selected)) stop()
    }

    fun awaitReadyForTraffic(timeoutMillis: Long = READY_TIMEOUT_MILLIS): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis.coerceAtLeast(0L)
        while (SystemClock.elapsedRealtime() <= deadline) {
            val state = synchronized(this) { isReadyForTraffic() to (process?.isAliveCompat() == true) }
            if (state.first) return true
            if (!state.second) return false
            Thread.sleep(100L)
        }
        return isReadyForTraffic()
    }

    @Synchronized
    fun isReadyForTraffic(): Boolean = readiness.ready &&
        process?.isAliveCompat() == true && sshClient?.isConnected() != false

    private fun stopHelper() {
        sshClient?.disconnect()
        sshClient = null
        val running = process
        process = null
        activeSignature = null
        readiness.reset(processGeneration)
        finalizationStarted = false
        if (running != null && running.isAliveCompat()) {
            runCatching { running.destroy() }
                .onFailure { LogUtil.w(AppConfig.TAG, "Failed to stop DNS tunnel helper", it) }
        }
    }

    fun recentLog(): List<String> = synchronized(recentLog) { recentLog.toList() }

    private fun Process.isAliveCompat(): Boolean = try {
        exitValue()
        false
    } catch (_: IllegalThreadStateException) {
        true
    }
}
