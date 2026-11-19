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

/** Stores the phone-linked MAX login token encrypted by a non-exportable Android Keystore key. */
object MaxTokenStore {
    private const val KEY_ALIAS = "kindlyvpn_max_turn_token_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    @Synchronized
    fun save(token: String) {
        val normalized = token.trim()
        require(normalized.isNotEmpty()) { "MAX token must not be empty" }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(normalized.toByteArray(Charsets.UTF_8))
        MmkvManager.encodeSettings(
            AppConfig.PREF_MAX_AUTH_TOKEN_IV,
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
        )
        MmkvManager.encodeSettings(
            AppConfig.PREF_MAX_AUTH_TOKEN_CIPHERTEXT,
            Base64.encodeToString(ciphertext, Base64.NO_WRAP),
        )
    }

    @Synchronized
    fun load(): String? {
        val iv = MmkvManager.decodeSettingsString(AppConfig.PREF_MAX_AUTH_TOKEN_IV).orEmpty()
        val ciphertext = MmkvManager.decodeSettingsString(
            AppConfig.PREF_MAX_AUTH_TOKEN_CIPHERTEXT,
        ).orEmpty()
        if (iv.isBlank() || ciphertext.isBlank()) return null

        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
            )
            String(
                cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)),
                Charsets.UTF_8,
            ).takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            clear()
            null
        }
    }

    fun clear() {
        MmkvManager.encodeSettings(AppConfig.PREF_MAX_AUTH_TOKEN_IV, "")
        MmkvManager.encodeSettings(AppConfig.PREF_MAX_AUTH_TOKEN_CIPHERTEXT, "")
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
