package com.example.input_ds.personalization

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keeps server API keys outside profile/session JSON and encrypts them with Android Keystore. */
class SecureCredentialStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(localUserId: String, apiKey: String) {
        require(apiKey.isNotBlank()) { "API key must not be blank" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        val payload = ByteArray(1 + cipher.iv.size + encrypted.size).also {
            it[0] = cipher.iv.size.toByte()
            cipher.iv.copyInto(it, 1)
            encrypted.copyInto(it, 1 + cipher.iv.size)
        }
        preferences.edit().putString(localUserId, Base64.encodeToString(payload, Base64.NO_WRAP)).apply()
    }

    fun load(localUserId: String): String? = runCatching {
        val encoded = preferences.getString(localUserId, null) ?: return null
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        val ivSize = payload.firstOrNull()?.toInt()?.and(0xff) ?: return null
        if (ivSize !in 12..32 || payload.size <= 1 + ivSize) return null
        val iv = payload.copyOfRange(1, 1 + ivSize)
        val encrypted = payload.copyOfRange(1 + ivSize, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }.getOrNull()

    fun delete(localUserId: String) {
        preferences.edit().remove(localUserId).apply()
    }

    fun hasCredential(localUserId: String): Boolean = preferences.contains(localUserId)

    fun saveDeviceBinding(serverUserId: String, apiKey: String) {
        require(serverUserId.isNotBlank()) { "Server user ID must not be blank" }
        saveEncrypted(DEVICE_SERVER_USER_ID, serverUserId.trim())
        saveEncrypted(DEVICE_API_KEY, apiKey.trim())
    }

    fun loadDeviceBinding(): DeviceServerBinding? {
        val serverUserId = loadEncrypted(DEVICE_SERVER_USER_ID) ?: return null
        val apiKey = loadEncrypted(DEVICE_API_KEY) ?: return null
        if (serverUserId.isBlank() || apiKey.isBlank()) return null
        return DeviceServerBinding(serverUserId, apiKey)
    }

    fun deleteDeviceBinding() {
        preferences.edit().remove(DEVICE_SERVER_USER_ID).remove(DEVICE_API_KEY).apply()
    }

    private fun saveEncrypted(key: String, value: String) {
        require(value.isNotBlank()) { "Encrypted value must not be blank" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = ByteArray(1 + cipher.iv.size + encrypted.size).also {
            it[0] = cipher.iv.size.toByte()
            cipher.iv.copyInto(it, 1)
            encrypted.copyInto(it, 1 + cipher.iv.size)
        }
        preferences.edit().putString(key, Base64.encodeToString(payload, Base64.NO_WRAP)).apply()
    }

    private fun loadEncrypted(key: String): String? = runCatching {
        val encoded = preferences.getString(key, null) ?: return null
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        val ivSize = payload.firstOrNull()?.toInt()?.and(0xff) ?: return null
        if (ivSize !in 12..32 || payload.size <= 1 + ivSize) return null
        val iv = payload.copyOfRange(1, 1 + ivSize)
        val encrypted = payload.copyOfRange(1 + ivSize, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }.getOrNull()

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
    }

    private companion object {
        const val PREFS_NAME = "personalization_credentials"
        const val KEY_ALIAS = "inputds_personalization_api_key_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val DEVICE_SERVER_USER_ID = "__device_server_user_id_v1"
        const val DEVICE_API_KEY = "__device_api_key_v1"
    }
}

data class DeviceServerBinding(val serverUserId: String, val apiKey: String)
