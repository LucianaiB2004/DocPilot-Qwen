package com.docpilot.qwen.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ApiKeyStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("secure_api_keys", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun saveQwenApiKey(value: String) = saveEncrypted("qwen_api_key", value)
    fun saveTextInAppId(value: String) = saveEncrypted("textin_app_id", value)
    fun saveTextInSecret(value: String) = saveEncrypted("textin_secret", value)

    fun getQwenApiKey(): String = readEncrypted("qwen_api_key")
    fun getTextInAppId(): String = readEncrypted("textin_app_id")
    fun getTextInSecret(): String = readEncrypted("textin_secret")

    fun deleteQwenApiKey() = deleteEncrypted("qwen_api_key")
    fun deleteTextInCredentials() {
        deleteEncrypted("textin_app_id")
        deleteEncrypted("textin_secret")
    }

    fun deleteAll() {
        deleteQwenApiKey()
        deleteTextInCredentials()
    }

    private fun saveEncrypted(name: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString("${name}_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(name, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .apply()
    }

    private fun readEncrypted(name: String): String {
        val iv = prefs.getString("${name}_iv", null) ?: return ""
        val encoded = prefs.getString(name, null) ?: return ""
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
            )
            String(cipher.doFinal(Base64.decode(encoded, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun deleteEncrypted(name: String) {
        prefs.edit()
            .remove("${name}_iv")
            .remove(name)
            .apply()
    }

    private fun getOrCreateKey(): SecretKey {
        keyStore.getKey(KEY_ALIAS, null)?.let { return it as SecretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val KEY_ALIAS = "docpilot_api_key_alias"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
