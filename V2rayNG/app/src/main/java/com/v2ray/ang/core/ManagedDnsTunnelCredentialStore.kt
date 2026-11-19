package com.v2ray.ang.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keeps user-entered SSH secrets out of plain MMKV storage and out of exported profiles. */
object ManagedDnsTunnelCredentialStore {
    private const val KEY_ALIAS = "kindlyvpn_dns_tunnel_ssh_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    @Synchronized
    fun savePassword(value: String) = save(
        value,
        AppConfig.PREF_DNS_TUNNEL_SSH_PASSWORD_IV,
        AppConfig.PREF_DNS_TUNNEL_SSH_PASSWORD_CIPHERTEXT,
    )

    @Synchronized
    fun savePrivateKey(value: String) = save(
        value,
        AppConfig.PREF_DNS_TUNNEL_SSH_KEY_IV,
        AppConfig.PREF_DNS_TUNNEL_SSH_KEY_CIPHERTEXT,
    )

    @Synchronized
    fun loadPassword(): String = load(
        AppConfig.PREF_DNS_TUNNEL_SSH_PASSWORD_IV,
        AppConfig.PREF_DNS_TUNNEL_SSH_PASSWORD_CIPHERTEXT,
    )

    @Synchronized
    fun loadPrivateKey(): String = load(
        AppConfig.PREF_DNS_TUNNEL_SSH_KEY_IV,
        AppConfig.PREF_DNS_TUNNEL_SSH_KEY_CIPHERTEXT,
    )

    fun clear() {
        savePassword("")
        savePrivateKey("")
    }

    private fun save(value: String, ivKey: String, ciphertextKey: String) {
        if (value.isEmpty()) {
            MmkvManager.encodeSettings(ivKey, "")
            MmkvManager.encodeSettings(ciphertextKey, "")
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        MmkvManager.encodeSettings(ivKey, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
        MmkvManager.encodeSettings(
            ciphertextKey,
            Base64.encodeToString(ciphertext, Base64.NO_WRAP),
        )
    }

    private fun load(ivKey: String, ciphertextKey: String): String {
        val iv = MmkvManager.decodeSettingsString(ivKey).orEmpty()
        val ciphertext = MmkvManager.decodeSettingsString(ciphertextKey).orEmpty()
        if (iv.isBlank() || ciphertext.isBlank()) return ""
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
            )
            String(
                cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)),
                Charsets.UTF_8,
            )
        }.getOrElse {
            MmkvManager.encodeSettings(ivKey, "")
            MmkvManager.encodeSettings(ciphertextKey, "")
            ""
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }
}
