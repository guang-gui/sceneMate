package com.scenemate.app.platform.byok

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * BYOK 密钥存储（ADR-015）。
 *
 * 四条硬约束：
 *  - Keystore 派生密钥 + AES/GCM 加密后落 SharedPreferences；
 *  - 不写日志、不进崩溃报告、不做备份导出（allowBackup=false）；
 *  - 一键清除；
 *  - 明文只在内存里存活一次调用的时间。
 */
class ByokKeyStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(provider: String, apiKey: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        val payload = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        prefs.edit().putString(KEY_PREFIX + provider, payload).apply()
    }

    fun load(provider: String): String? {
        val payload = prefs.getString(KEY_PREFIX + provider, null) ?: return null
        val parts = payload.split(":")
        if (parts.size != 2) return null
        return runCatching {
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val data = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        }.getOrNull()
    }

    fun hasKey(provider: String): Boolean = prefs.contains(KEY_PREFIX + provider)

    fun clear(provider: String) {
        prefs.edit().remove(KEY_PREFIX + provider).apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    fun selectedProvider(): String = prefs.getString(KEY_SELECTED_PROVIDER, "deepseek") ?: "deepseek"

    fun selectProvider(provider: String) {
        prefs.edit().putString(KEY_SELECTED_PROVIDER, provider).apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "scenemate_byok_v1"
        const val PREFS = "scenemate_byok"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_PREFIX = "key_"
        const val KEY_SELECTED_PROVIDER = "selected_provider"
    }
}
