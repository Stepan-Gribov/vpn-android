package com.v2ray.ang.core

import android.content.Context
import android.os.SystemClock
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.MessageUtil
import java.io.File
import java.net.URI
import java.util.ArrayDeque
import kotlin.concurrent.thread

/**
 * Owns the bundled vk-turn-proxy client process.
 *
 * The Android VPN builder excludes this application's UID. That is intentional: both Xray and
 * this helper must reach the mobile carrier directly, while traffic from other apps enters the
 * TUN interface and is forwarded to Xray. The helper exposes the remote VLESS server locally on
 * 127.0.0.1:9000.
 */
object VkTurnManager {
    const val HELPER_VERSION = "1.10.1-srtp-bond.6-captcha"
    const val CAPTCHA_URL = "http://localhost:8765/"
    const val DEFAULT_SESSION_COUNT = TurnSessionCount.DEFAULT
    const val MAXIMUM_SESSION_COUNT = 16

    private const val HELPER_FILE = "libvkturn.so"
    private const val MAX_LOG_LINES = 80
    private const val MAX_RESTART_ATTEMPTS = 3
    private const val UDP_READY_TIMEOUT_MS = 45_000L
    private const val UDP_POST_CAPTCHA_TIMEOUT_MS = 30_000L
    private const val UDP_FALLBACK_POLL_MS = 1_000L
    private const val MAX_REMEMBERED_PEERS = 20

    internal enum class Transport {
        UDP,
        TCP,
    }

    enum class Provider {
        VK,
        MAX,
    }

    private data class ProviderConfig(
        val provider: Provider,
        val credentialSource: String,
        val maxToken: String? = null,
    )

    private var process: Process? = null
    private var activeSignature: String? = null
    private var activeTransport: Transport? = null
    private var preferredTransport: Transport? = null
    private var activeProvider: Provider? = null
    private var helperReady = false
    private val captchaState = TurnCaptchaState()
    private var nativeControlReady = false
    private var resumeV2 = false
    private var networkRefreshID = 0L
    private var udpReadyDeadline = 0L
    private var processGeneration = 0L
    private var restartAttempts = 0
    private val recentLog = ArrayDeque<String>()

    fun isTurnProfile(profile: ProfileItem?): Boolean {
        if (profile?.configType != EConfigType.VLESS) return false
        val host = profile.server?.trim()?.lowercase()
        val port = profile.serverPort?.toIntOrNull()
        return (host == AppConfig.VK_TURN_LOCAL_ADDRESS || host == "localhost") &&
            port != null &&
            port in setOf(AppConfig.VK_TURN_LOCAL_PORT.toInt(), DnsTunnelProfiles.PORT.toInt())
    }

    /** App-level automatic recovery is intentionally limited to VK Calls TURN routes. */
    fun isVkTurnProfile(profile: ProfileItem?): Boolean =
        isTurnProfile(profile) && !DnsTunnelProfiles.isDns(profile) && getProvider() == Provider.VK

    /** TURN adds DTLS/KCP/smux overhead; its upstream Android client requires MTU 1280. */
    fun effectiveVpnMtu(profile: ProfileItem?, configuredMtu: Int): Int =
        if (isTurnProfile(profile)) AppConfig.VK_TURN_VPN_MTU else configuredMtu

    fun getCallLink(): String =
        MmkvManager.decodeSettingsString(AppConfig.PREF_VK_TURN_CALL_LINK).orEmpty()

    fun getRememberedPeers(): List<String> {
        val json = MmkvManager.decodeSettingsString(AppConfig.PREF_VK_TURN_PEER_HISTORY)
        val saved = if (json.isNullOrBlank()) {
            emptyList()
        } else {
            JsonUtil.fromJsonSafe(json, Array<String>::class.java)?.toList().orEmpty()
        }
        return (listOf(getPeer()) + saved)
            .mapNotNull(::normalizePeer)
            .distinct()
            .take(MAX_REMEMBERED_PEERS)
    }

    fun rememberPeerAddress(value: String) {
        val peer = normalizePeer(value) ?: return
        val json = MmkvManager.decodeSettingsString(AppConfig.PREF_VK_TURN_PEER_HISTORY)
        val saved = if (json.isNullOrBlank()) {
            emptyList()
        } else {
            JsonUtil.fromJsonSafe(json, Array<String>::class.java)?.toList().orEmpty()
        }
        val updated = (listOf(peer) + saved)
            .mapNotNull(::normalizePeer)
            .distinct()
            .take(MAX_REMEMBERED_PEERS)
        MmkvManager.encodeSettings(AppConfig.PREF_VK_TURN_PEER_HISTORY, JsonUtil.toJson(updated))
    }

    fun getProvider(): Provider = runCatching {
        Provider.valueOf(
            MmkvManager.decodeSettingsString(AppConfig.PREF_TURN_PROVIDER, Provider.VK.name)
                .orEmpty(),
        )
    }.getOrDefault(Provider.VK)

    fun getMaxPeerID(): String =
        MmkvManager.decodeSettingsString(AppConfig.PREF_MAX_TURN_PEER_ID).orEmpty()

    fun getMaxPhone(): String =
        MmkvManager.decodeSettingsString(AppConfig.PREF_MAX_AUTH_PHONE).orEmpty()

    fun getMaxExternalID(): String =
        MmkvManager.decodeSettingsString(AppConfig.PREF_MAX_AUTH_EXTERNAL_ID).orEmpty()

    fun getPeer(): String = migrateLegacyPeer(
        MmkvManager.decodeSettingsString(
            AppConfig.PREF_VK_TURN_PEER,
            AppConfig.VK_TURN_DEFAULT_PEER,
        ).orEmpty(),
    )

    internal fun migrateLegacyPeer(value: String): String {
        val peer = value.trim().ifBlank { AppConfig.VK_TURN_DEFAULT_PEER }
        return if (
            peer == AppConfig.VK_TURN_LEGACY_PEER ||
            peer == AppConfig.VK_TURN_RETIRED_FINLAND_PEER ||
            peer == AppConfig.VK_TURN_LEGACY_YANDEX_PEER
        ) {
            AppConfig.VK_TURN_DEFAULT_PEER
        } else {
            peer
        }
    }

    fun getSessionCount(): Int = normalizeSessionCount(
        MmkvManager.decodeSettingsInt(
            AppConfig.PREF_VK_TURN_SESSION_COUNT,
            DEFAULT_SESSION_COUNT,
        ),
    )

    fun isAutoCaptchaEnabled(): Boolean = false

    /** True only after the helper has opened its bonded local VLESS listener. */
    @Synchronized
    fun isReadyForTraffic(): Boolean = if (DnsTunnelManager.active) DnsTunnelManager.ready else helperReady

    /** A pending manual/automatic captcha is not a failed tunnel health check. */
    @Synchronized
    fun isCaptchaPending(): Boolean = !DnsTunnelManager.active && captchaState.snapshot() != null

    internal fun captchaChallenge(): TurnCaptchaState.Challenge? = captchaState.snapshot()
    internal fun requireCaptchaUser(id: Long): Boolean = captchaState.requireUser(id)

    internal fun normalizeSessionCount(value: Int): Int = TurnSessionCount.normalize(value)

    fun saveSettings(
        callLink: String,
        peer: String,
        sessionCount: Int,
        provider: Provider = Provider.VK,
        maxPeerID: String = getMaxPeerID(),
        maxPhone: String = getMaxPhone(),
        autoCaptcha: Boolean = isAutoCaptchaEnabled(),
    ) {
        MmkvManager.encodeSettings(AppConfig.PREF_VK_TURN_CALL_LINK, callLink.trim())
        MmkvManager.encodeSettings(AppConfig.PREF_VK_TURN_PEER, peer.trim())
        rememberPeerAddress(peer)
        MmkvManager.encodeSettings(
            AppConfig.PREF_VK_TURN_SESSION_COUNT,
            normalizeSessionCount(sessionCount),
        )
        MmkvManager.encodeSettings(AppConfig.PREF_TURN_PROVIDER, provider.name)
        MmkvManager.encodeSettings(AppConfig.PREF_MAX_TURN_PEER_ID, maxPeerID.trim())
        MmkvManager.encodeSettings(AppConfig.PREF_MAX_AUTH_PHONE, maxPhone.trim())
        MmkvManager.encodeSettings(AppConfig.PREF_VK_TURN_AUTO_CAPTCHA, false)
    }

    fun normalizeVkCallLink(value: String): String? {
        val candidate = value.trim()
        val uri = try {
            URI(candidate)
        } catch (_: Exception) {
            return null
        }
        val host = uri.host?.lowercase() ?: return null
        val validHost = host in setOf(
            "vk.com",
            "www.vk.com",
            "m.vk.com",
            "vk.ru",
            "www.vk.ru",
            "m.vk.ru",
        )
        val path = uri.path.orEmpty()
        val token = path.substringAfter("/call/join/", "").substringBefore('/')
        return candidate.takeIf {
            uri.scheme.equals("https", ignoreCase = true) && validHost && token.isNotBlank()
        }
    }

    fun normalizePeer(value: String): String? {
        val candidate = value.trim()
        val separator = candidate.lastIndexOf(':')
        if (separator <= 0 || separator == candidate.lastIndex) return null
        val host = candidate.substring(0, separator).trim()
        val port = candidate.substring(separator + 1).toIntOrNull() ?: return null
        if (host.isBlank() || host.any { it.isWhitespace() } || port !in 1..65535) return null
        return "$host:$port"
    }

    fun isConfigured(profile: ProfileItem?): Boolean {
        if (DnsTunnelProfiles.isDns(profile)) return DnsTunnelStore.configured(com.v2ray.ang.AngApplication.application)
        if (!isTurnProfile(profile)) return true
        return when (getProvider()) {
            Provider.VK -> normalizeVkCallLink(getCallLink()) != null
            Provider.MAX -> MaxAuthClient.normalizePeerID(getMaxPeerID()) != null &&
                !MaxTokenStore.load().isNullOrBlank()
        }
    }

    private fun resolveProviderConfig(context: Context): ProviderConfig = when (getProvider()) {
        Provider.VK -> ProviderConfig(
            provider = Provider.VK,
            credentialSource = normalizeVkCallLink(getCallLink())
                ?: error(context.getString(R.string.vk_turn_error_link_missing)),
        )

        Provider.MAX -> ProviderConfig(
            provider = Provider.MAX,
            credentialSource = MaxAuthClient.normalizePeerID(getMaxPeerID())
                ?: error(context.getString(R.string.max_turn_peer_invalid)),
            maxToken = MaxTokenStore.load()
                ?: error(context.getString(R.string.max_turn_login_required)),
        )
    }

    private fun buildSignature(
        peer: String,
        providerConfig: ProviderConfig,
        sessionCount: Int,
        autoCaptcha: Boolean,
    ): String = buildString {
        append(peer)
        append('\n')
        append(providerConfig.provider.name)
        append('\n')
        append(providerConfig.credentialSource)
        append('\n')
        append(sessionCount)
        append('\n')
        append(autoCaptcha)
        append(MmkvManager.decodeSettingsString(com.v2ray.ang.simple.SimpleRuntime.WRAP_KEY, AppConfig.VK_TURN_WRAP_KEY))
        if (providerConfig.provider == Provider.MAX) {
            append('\n')
            append(providerConfig.maxToken.hashCode())
        }
    }

    /** Starts the helper for a TURN profile and stops any stale helper for other profiles. */
    @Synchronized
    fun ensureForProfile(context: Context, profile: ProfileItem) {
        if (!isTurnProfile(profile)) {
            if (profile.remarks.contains("turn", ignoreCase = true)) {
                LogUtil.w(
                    AppConfig.TAG,
                    "TURN helper skipped for '${profile.remarks}': expected local VLESS " +
                        "${AppConfig.VK_TURN_LOCAL_ADDRESS}:${AppConfig.VK_TURN_LOCAL_PORT}, " +
                        "got ${profile.configType} ${profile.server}:${profile.serverPort}",
                )
            }
            stop()
            return
        }

        if (DnsTunnelProfiles.isDns(profile)) {
            processGeneration += 1
            stopHelper()
            DnsTunnelManager.ensure(context)
            return
        }
        DnsTunnelManager.stop()

        val providerConfig = resolveProviderConfig(context)
        val peer = normalizePeer(getPeer())
            ?: error(context.getString(R.string.vk_turn_error_peer_invalid))
        val sessionCount = getSessionCount()
        val autoCaptcha = isAutoCaptchaEnabled()
        val signature = buildSignature(peer, providerConfig, sessionCount, autoCaptcha)

        LogUtil.i(
            AppConfig.TAG,
            "Starting ${providerConfig.provider.name} TURN helper for ${profile.remarks} via $peer",
        )

        process?.let { running ->
            if (activeSignature == signature && running.isAliveCompat()) return
        }
        launchHelper(
            context,
            profile,
            providerConfig,
            peer,
            signature,
            preferredTransport ?: Transport.UDP,
            sessionCount,
            autoCaptcha,
        )
    }

    /** Existing UDP/TCP sockets belong to the previous physical network. */
    @Synchronized
    fun refreshPhysicalNetwork(context: Context, profile: ProfileItem, stillWanted: () -> Boolean): Boolean {
        if (!stillWanted()) return false
        if(!isTurnProfile(profile) || DnsTunnelProfiles.isDns(profile)) return false
        if (requestNativeNetworkRefresh()) return true
        activeSignature = null
        ensureForProfile(context,profile)
        return false
    }

    /** A supported server retains logical connections while only relay slots are rebuilt. */
    @Synchronized
    fun refreshResumableTransport(stillWanted: () -> Boolean): Boolean =
        stillWanted() && resumeV2 && requestNativeNetworkRefresh()

    @Synchronized
    fun supportsConnectionResume(): Boolean = nativeControlReady && resumeV2 && process?.isAliveCompat() == true

    private fun requestNativeNetworkRefresh(): Boolean {
        val running = process ?: return false
        if (!nativeControlReady || !running.isAliveCompat()) return false
        return runCatching {
            running.outputStream.write("{\"action\":\"refresh-network\",\"id\":${++networkRefreshID}}\n".toByteArray(Charsets.UTF_8))
            running.outputStream.flush()
            LogUtil.i(AppConfig.TAG, "TURN relay refresh requested; preserving helper and logical connections")
            true
        }.getOrDefault(false)
    }

    /** Repair only the helper; Android TUN, HEV, Xray and split rules stay alive. */
    @Synchronized
    fun recoverTransport(context: Context, profile: ProfileItem, stillWanted: () -> Boolean): Boolean {
        // Recheck under the same lock used by stop(), so a cancelled repair
        // cannot launch a new helper after an explicit Disconnect.
        if (!stillWanted()) return false
        check(isVkTurnProfile(profile))
        preferredTransport = recoveryTransport(activeTransport ?: preferredTransport)
        activeSignature = null
        ensureForProfile(context, profile)
        return true
    }

    internal fun recoveryTransport(current: Transport?): Transport = when (current) {
        Transport.UDP -> Transport.TCP
        Transport.TCP -> Transport.TCP
        null -> Transport.UDP
    }

    @Synchronized
    private fun launchHelper(
        context: Context,
        profile: ProfileItem,
        providerConfig: ProviderConfig,
        peer: String,
        signature: String,
        transport: Transport,
        sessionCount: Int,
        autoCaptcha: Boolean,
    ) {
        stopHelper()
        processGeneration += 1
        val generation = processGeneration

        val helper = File(context.applicationInfo.nativeLibraryDir, HELPER_FILE)
        if (!helper.isFile || !helper.canExecute()) {
            error(context.getString(R.string.vk_turn_error_helper_missing))
        }

        recentLog.clear()
        val processBuilder = ProcessBuilder(
            helperCommand(
                helper.absolutePath,
                peer,
                providerConfig.credentialSource,
                transport,
                sessionCount,
                providerConfig.provider,
                autoCaptcha,
                wrapKey = MmkvManager.decodeSettingsString(
                    com.v2ray.ang.simple.SimpleRuntime.WRAP_KEY, AppConfig.VK_TURN_WRAP_KEY,
                ).orEmpty(),
            ),
        )
            .directory(context.noBackupFilesDir)
            .redirectErrorStream(true)
        if (providerConfig.provider == Provider.MAX) {
            processBuilder.environment()[AppConfig.MAX_TURN_TOKEN_ENV] =
                providerConfig.maxToken ?: error(context.getString(R.string.max_turn_login_required))
        }
        val started = processBuilder.start()

        process = started
        activeSignature = signature
        activeTransport = transport
        preferredTransport = transport
        activeProvider = providerConfig.provider
        helperReady = false
        captchaState.clear()
        udpReadyDeadline = if (transport == Transport.UDP) {
            SystemClock.elapsedRealtime() + UDP_READY_TIMEOUT_MS
        } else {
            Long.MAX_VALUE
        }
        consumeOutput(context.applicationContext, started, profile.copy(), generation)
        if (transport == Transport.UDP) {
            scheduleTcpFallback(
                context.applicationContext,
                profile.copy(),
                generation,
                sessionCount,
            )
        }

        // Catch bad arguments or an unsupported binary without blocking TURN negotiation.
        Thread.sleep(200)
        if (!started.isAliveCompat()) {
            val detail = synchronized(recentLog) { recentLog.lastOrNull().orEmpty() }
            process = null
            activeSignature = null
            error(context.getString(R.string.vk_turn_error_start_failed, detail))
        }
        LogUtil.i(
            AppConfig.TAG,
            "${providerConfig.provider.name} TURN helper v$HELPER_VERSION started for $peer via ${transport.name} " +
                "with $sessionCount sessions",
        )
    }

    internal fun helperCommand(
        helperPath: String,
        peer: String,
        credentialSource: String,
        transport: Transport,
        sessionCount: Int,
        provider: Provider = Provider.VK,
        autoCaptcha: Boolean = false,
        wrapKey: String = AppConfig.VK_TURN_WRAP_KEY,
    ): List<String> = buildList {
        add(helperPath)
        add("-vless")
        add("-vless-bond")
        add("-vless-resume")
        add("-control-stdin")
        if (wrapKey.isNotBlank()) {
            add("-wrap")
            add("-wrap-key")
            add(wrapKey)
        }
        add("-n")
        add(normalizeSessionCount(sessionCount).toString())
        if (transport == Transport.UDP) add("-udp")
        if (provider == Provider.VK) {
            add("-manual-captcha")
        }
        add("-listen")
        add("${AppConfig.VK_TURN_LOCAL_ADDRESS}:${AppConfig.VK_TURN_LOCAL_PORT}")
        add("-peer")
        add(peer)
        when (provider) {
            Provider.VK -> add("-vk-link")
            Provider.MAX -> add("-max-peer-id")
        }
        add(credentialSource)
    }

    @Synchronized
    fun stop() {
        DnsTunnelManager.stop()
        preferredTransport = null
        processGeneration += 1
        restartAttempts = 0
        stopHelper()
    }

    private fun stopHelper() {
        val running = process
        process = null
        activeSignature = null
        activeTransport = null
        activeProvider = null
        helperReady = false
        captchaState.clear()
        nativeControlReady = false
        resumeV2 = false
        udpReadyDeadline = 0L
        if (running != null && running.isAliveCompat()) {
            try {
                running.destroy()
                // A replacement must not race the old listener for port 9000.
                val deadline = SystemClock.elapsedRealtime() + 2_000L
                while (running.isAliveCompat() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(25)
                if (running.isAliveCompat() && android.os.Build.VERSION.SDK_INT >= 26) {
                    running.destroyForcibly()
                    val killDeadline = SystemClock.elapsedRealtime() + 500L
                    while (running.isAliveCompat() && SystemClock.elapsedRealtime() < killDeadline) Thread.sleep(10)
                }
                if (running.isAliveCompat()) LogUtil.w(AppConfig.TAG, "Previous TURN helper is still stopping")
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "Failed to stop VK TURN helper", e)
            }
        }
    }

    fun recentLog(): List<String> = synchronized(recentLog) { recentLog.toList() }

    /** Replays process-local TURN readiness to a UI opened after the helper became ready. */
    @Synchronized
    fun reportCurrentStatus(context: Context) {
        if (DnsTunnelManager.active) {
            if (DnsTunnelManager.ready) MessageUtil.sendMsg2UI(context, AppConfig.MSG_VK_TURN_READY, context.getString(com.v2ray.ang.R.string.dns_tunnel_ready))
            return
        }
        when {
            helperReady -> MessageUtil.sendMsg2UI(
                context,
                AppConfig.MSG_VK_TURN_READY,
                context.getString(
                    if (activeProvider == Provider.MAX) R.string.max_turn_ready else R.string.vk_turn_ready,
                ),
            )

            captchaState.snapshot() != null -> MessageUtil.sendMsg2UI(
                context,
                AppConfig.MSG_VK_TURN_CAPTCHA_REQUIRED,
                CAPTCHA_URL,
            )
        }
    }

    internal fun redactSensitiveLog(line: String): String = line
        .replace(Regex("((?:session|success|access)_token=)[^&\\s\\]]+", RegexOption.IGNORE_CASE), "$1<redacted>")
        .replace(Regex("https?://(?:[a-z0-9.-]+\\.)?vk\\.(?:com|ru)/call/join/[^\\s\\]]+", RegexOption.IGNORE_CASE), "<VK call redacted>")
        .replace(Regex("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", RegexOption.IGNORE_CASE), "<UUID redacted>")
        .replace(Regex("(?<![0-9a-f])[0-9a-f]{64}(?![0-9a-f])", RegexOption.IGNORE_CASE), "<key redacted>")
        .replace(Regex("vpn://[^\\s]+"), "<VPN key redacted>")

    internal fun helperMessageForLog(line: String): Int? = when {
        line.contains("KINDLYVPN_CAPTCHA_REQUIRED") -> AppConfig.MSG_VK_TURN_CAPTCHA_REQUIRED
        line.contains("KINDLYVPN_CAPTCHA_COMPLETED") -> AppConfig.MSG_VK_TURN_CAPTCHA_COMPLETED
        line.contains("VLESS mode: listening on ") ||
            line.contains("VLESS bond mode: listening on ") -> AppConfig.MSG_VK_TURN_READY
        line.contains("manual captcha timed out", ignoreCase = true) ||
            line.contains("FATAL_CAPTCHA", ignoreCase = true) -> AppConfig.MSG_VK_TURN_FAILURE
        else -> null
    }

    private fun consumeOutput(
        context: Context,
        started: Process,
        profile: ProfileItem,
        generation: Long,
    ) {
        thread(name = "vk-turn-output", isDaemon = true) {
            try {
                started.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val safeLine = redactSensitiveLog(line).take(1_000)
                        synchronized(recentLog) {
                            if (recentLog.size >= MAX_LOG_LINES) recentLog.removeFirst()
                            recentLog.addLast(safeLine)
                        }
                        LogUtil.i(AppConfig.TAG, "TURN helper: $safeLine")
                        synchronized(this) {
                            if (process === started && generation == processGeneration) {
                                if (line.contains("TNDTUN_TURN_CONTROL_READY")) nativeControlReady = true
                                if (line.contains("TNDTUN_TURN_RESUME_V2")) resumeV2 = true
                                if (line.contains("TNDTUN_TURN_RESUME_V1")) resumeV2 = false
                            }
                        }
                        when (helperMessageForLog(line)) {
                            AppConfig.MSG_VK_TURN_CAPTCHA_REQUIRED -> {
                                val challenge = synchronized(this) {
                                    if (process === started && generation == processGeneration) {
                                        udpReadyDeadline = Long.MAX_VALUE
                                        captchaState.begin(SystemClock.elapsedRealtime(), isAutoCaptchaEnabled())
                                    } else {
                                        null
                                    }
                                }
                                if (challenge == null) return@forEach
                                MessageUtil.sendMsg2UI(
                                    context,
                                    AppConfig.MSG_VK_TURN_CAPTCHA_REQUIRED,
                                    CAPTCHA_URL,
                                )
                                CoreServiceManager.onTurnCaptchaRequired(context, challenge.id)
                            }
                            AppConfig.MSG_VK_TURN_CAPTCHA_COMPLETED -> {
                                val accepted = synchronized(this) {
                                    if (process === started && generation == processGeneration) {
                                        captchaState.clear()
                                        if (activeTransport == Transport.UDP) {
                                            udpReadyDeadline = SystemClock.elapsedRealtime() +
                                                UDP_POST_CAPTCHA_TIMEOUT_MS
                                        }
                                        true
                                    } else {
                                        false
                                    }
                                }
                                if (!accepted) return@forEach
                                MessageUtil.sendMsg2UI(
                                    context,
                                    AppConfig.MSG_VK_TURN_CAPTCHA_COMPLETED,
                                    context.getString(R.string.vk_turn_captcha_submitted),
                                )
                                CoreServiceManager.onTurnCaptchaFinished()
                            }
                            AppConfig.MSG_VK_TURN_READY -> {
                                val accepted = synchronized(this) {
                                    if (process === started && generation == processGeneration) {
                                        restartAttempts = 0
                                        helperReady = true
                                        captchaState.clear()
                                        true
                                    } else {
                                        false
                                    }
                                }
                                if (!accepted) return@forEach
                                MessageUtil.sendMsg2UI(
                                    context,
                                    AppConfig.MSG_VK_TURN_READY,
                                    context.getString(
                                        if (activeProvider == Provider.MAX) {
                                            R.string.max_turn_ready
                                        } else {
                                            R.string.vk_turn_ready
                                        },
                                    ),
                                )
                                CoreServiceManager.onTurnCaptchaFinished()
                            }
                            AppConfig.MSG_VK_TURN_FAILURE -> {
                                val failure = synchronized(this) {
                                    if (process === started && generation == processGeneration) {
                                        true to captchaState.snapshot()
                                    } else false to null
                                }
                                if (!failure.first) return@forEach
                                val challenge = failure.second
                                if (challenge != null) {
                                    CoreServiceManager.onTurnCaptchaFailed(context, challenge.id)
                                    return@forEach
                                }
                                CoreServiceManager.onTurnCaptchaFinished()
                                MessageUtil.sendMsg2UI(
                                    context,
                                    AppConfig.MSG_VK_TURN_FAILURE,
                                    safeLine,
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "VK TURN log reader stopped", e)
            } finally {
                val restartAttempt = synchronized(this) {
                    if (process === started) {
                        process = null
                        activeSignature = null
                        activeTransport = null
                        activeProvider = null
                        helperReady = false
                        captchaState.clear()
                        nativeControlReady = false
                        resumeV2 = false
                        udpReadyDeadline = 0L
                        if (generation == processGeneration && restartAttempts < MAX_RESTART_ATTEMPTS) {
                            restartAttempts += 1
                            restartAttempts
                        } else {
                            0
                        }
                    } else {
                        0
                    }
                }
                if (restartAttempt > 0) {
                    val exitDetail = helperExitDetail(started)
                    LogUtil.w(
                        AppConfig.TAG,
                        "TURN helper stopped unexpectedly ($exitDetail); restart $restartAttempt/$MAX_RESTART_ATTEMPTS",
                    )
                    MessageUtil.sendMsg2UI(
                        context,
                        AppConfig.MSG_VK_TURN_FAILURE,
                        context.getString(R.string.vk_turn_reconnecting),
                    )
                    scheduleRestart(context, profile, generation, restartAttempt)
                } else if (generation == synchronized(this) { processGeneration }) {
                    MessageUtil.sendMsg2UI(
                        context,
                        AppConfig.MSG_VK_TURN_FAILURE,
                        context.getString(R.string.vk_turn_helper_stopped),
                    )
                    CoreServiceManager.reportConnectionDrop(
                        context,
                        context.getString(R.string.vk_turn_helper_stopped),
                    )
                }
            }
        }
    }

    private fun scheduleTcpFallback(
        context: Context,
        profile: ProfileItem,
        generation: Long,
        sessionCount: Int,
    ) {
        thread(name = "vk-turn-udp-fallback", isDaemon = true) {
            while (true) {
                val waitMs = synchronized(this) {
                    if (
                        generation != processGeneration ||
                        activeTransport != Transport.UDP ||
                        helperReady ||
                        process == null
                    ) {
                        null
                    } else if (captchaState.snapshot() != null) {
                        UDP_FALLBACK_POLL_MS
                    } else {
                        (udpReadyDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                    }
                } ?: return@thread

                if (waitMs > 0L) {
                    Thread.sleep(waitMs.coerceAtMost(UDP_FALLBACK_POLL_MS))
                    continue
                }

                val fallbackStarted = synchronized(this) {
                    if (
                        generation != processGeneration ||
                        activeTransport != Transport.UDP ||
                        helperReady ||
                        captchaState.snapshot() != null ||
                        process == null
                    ) {
                        false
                    } else {
                        val peer = normalizePeer(getPeer())
                        val providerConfig = runCatching { resolveProviderConfig(context) }.getOrNull()
                        val autoCaptcha = isAutoCaptchaEnabled()
                        if (providerConfig == null || peer == null) {
                            false
                        } else {
                            MessageUtil.sendMsg2UI(
                                context,
                                AppConfig.MSG_VK_TURN_FAILURE,
                                context.getString(R.string.vk_turn_udp_fallback),
                            )
                            launchHelper(
                                context,
                                profile,
                                providerConfig,
                                peer,
                                buildSignature(peer, providerConfig, sessionCount, autoCaptcha),
                                Transport.TCP,
                                sessionCount,
                                autoCaptcha,
                            )
                            true
                        }
                    }
                }
                if (fallbackStarted) return@thread
                Thread.sleep(UDP_FALLBACK_POLL_MS)
            }
        }
    }

    private fun scheduleRestart(
        context: Context,
        profile: ProfileItem,
        generation: Long,
        attempt: Int,
    ) {
        thread(name = "vk-turn-restart", isDaemon = true) {
            try {
                Thread.sleep(attempt * 1_500L)
                val stillWanted = synchronized(this) {
                    process == null && processGeneration == generation
                }
                if (stillWanted) ensureForProfile(context, profile)
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "VK TURN helper restart failed", e)
                MessageUtil.sendMsg2UI(
                    context,
                    AppConfig.MSG_VK_TURN_FAILURE,
                    context.getString(R.string.vk_turn_helper_stopped),
                )
                CoreServiceManager.reportConnectionDrop(
                    context,
                    context.getString(R.string.vk_turn_helper_stopped),
                )
            }
        }
    }

    private fun helperExitDetail(started: Process): String = try {
        "exit=${started.exitValue()}"
    } catch (_: IllegalThreadStateException) {
        "output closed"
    }

    private fun Process.isAliveCompat(): Boolean = try {
        exitValue()
        false
    } catch (_: IllegalThreadStateException) {
        true
    }
}
