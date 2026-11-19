package com.v2ray.ang.core

import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.JSch
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo
import com.v2ray.ang.handler.MmkvManager
import java.security.MessageDigest
import java.util.Base64
import com.jcraft.jsch.Session
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Creates the local SOCKS5 endpoint after the DNS transport reaches an SSH server. */
class ManagedDnsTunnelSshClient {
    private var session: Session? = null
    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "dns-tunnel-ssh").apply { isDaemon = true }
    }

    fun connect(username: String, password: String, privateKey: String): Boolean {
        if (isConnected()) return true
        return runCatching {
            val jsch = JSch()
            if (privateKey.isNotBlank()) {
                jsch.addIdentity("kindlyvpn_dns_tunnel", privateKey.toByteArray(), null, null)
            }
            val pinned = MmkvManager.decodeSettingsString(com.v2ray.ang.simple.SimpleRuntime.SSH_HOST_KEY, "").orEmpty()
            require(pinned.startsWith("SHA256:")) { "Укажите SHA256-отпечаток SSH-сервера." }
            jsch.setHostKeyRepository(object : HostKeyRepository {
                override fun check(host: String?, key: ByteArray?): Int {
                    if (key == null) return HostKeyRepository.NOT_INCLUDED
                    val fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding()
                        .encodeToString(MessageDigest.getInstance("SHA-256").digest(key))
                    return if (MessageDigest.isEqual(fingerprint.toByteArray(), pinned.trimEnd('=').toByteArray()))
                        HostKeyRepository.OK else HostKeyRepository.CHANGED
                }
                override fun add(hostkey: HostKey?, ui: UserInfo?) {}
                override fun remove(host: String?, type: String?) {}
                override fun remove(host: String?, type: String?, key: ByteArray?) {}
                override fun getKnownHostsRepositoryID(): String = "SimpleBLT pinned SSH key"
                override fun getHostKey(): Array<HostKey> = emptyArray()
                override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
            })
            val connectedSession = jsch.getSession(
                username,
                AppConfig.DNS_TUNNEL_LOCAL_ADDRESS,
                AppConfig.DNS_TUNNEL_SSH_UPSTREAM_PORT,
            ).apply {
                if (password.isNotBlank()) setPassword(password)
                setConfig(
                    Properties().apply {
                        put("StrictHostKeyChecking", "yes")
                        put("PreferredAuthentications", "publickey,password,keyboard-interactive")
                    },
                )
                timeout = 30_000
                setServerAliveInterval(15_000)
                setServerAliveCountMax(3)
                connect(30_000)
            }
            session = connectedSession
            running.set(true)
            serverSocket = ServerSocket().apply {
                reuseAddress = true
                bind(
                    InetSocketAddress(
                        InetAddress.getByName(AppConfig.DNS_TUNNEL_LOCAL_ADDRESS),
                        AppConfig.DNS_TUNNEL_LOCAL_PORT.toInt(),
                    ),
                )
            }
            workers.execute(::acceptLoop)
            true
        }.getOrElse { error ->
            LogUtil.e(AppConfig.TAG, "DNS tunnel SSH connection failed", error)
            disconnect()
            false
        }
    }

    private fun acceptLoop() {
        while (running.get()) {
            val client = runCatching { serverSocket?.accept() }.getOrNull() ?: break
            clients.add(client)
            workers.execute { handleClient(client) }
        }
    }

    private fun handleClient(client: Socket) {
        var channel: ChannelDirectTCPIP? = null
        try {
            client.soTimeout = 30_000
            val input = DataInputStream(client.getInputStream())
            val output = client.getOutputStream()

            if (input.readUnsignedByte() != 5) return
            val methodCount = input.readUnsignedByte()
            repeat(methodCount) { input.readUnsignedByte() }
            output.write(byteArrayOf(5, 0))
            output.flush()

            if (input.readUnsignedByte() != 5 || input.readUnsignedByte() != 1) {
                sendReply(output, 7)
                return
            }
            input.readUnsignedByte()
            val destination = when (input.readUnsignedByte()) {
                1 -> InetAddress.getByAddress(ByteArray(4).also(input::readFully)).hostAddress
                3 -> String(ByteArray(input.readUnsignedByte()).also(input::readFully), Charsets.UTF_8)
                4 -> InetAddress.getByAddress(ByteArray(16).also(input::readFully)).hostAddress
                else -> {
                    sendReply(output, 8)
                    return
                }
            }
            val destinationPort = input.readUnsignedShort()
            channel = session?.openChannel("direct-tcpip") as? ChannelDirectTCPIP
                ?: error("SSH direct-tcpip channel unavailable")
            channel.setHost(destination)
            channel.setPort(destinationPort)
            channel.connect(15_000)
            sendReply(output, 0)
            client.soTimeout = 0

            val upstream = channel
            val upstreamToClient = workers.submit {
                runCatching { upstream.inputStream.copyTo(output, 16 * 1024) }
                runCatching { client.shutdownOutput() }
            }
            runCatching { input.copyTo(upstream.outputStream, 16 * 1024) }
            upstreamToClient.cancel(true)
        } catch (error: Exception) {
            LogUtil.w(AppConfig.TAG, "DNS tunnel SSH forwarding ended: ${error.message}")
        } finally {
            runCatching { channel?.disconnect() }
            clients.remove(client)
            runCatching { client.close() }
        }
    }

    private fun sendReply(output: OutputStream, result: Int) {
        output.write(byteArrayOf(5, result.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))
        output.flush()
    }

    fun isConnected(): Boolean = running.get() && session?.isConnected == true

    fun disconnect() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        runCatching { session?.disconnect() }
        session = null
    }
}
