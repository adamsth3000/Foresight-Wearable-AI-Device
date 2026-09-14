package com.foresight.gateway.voice.conversation

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/** Separate encrypted storage for a Google Maps Platform key; it is never shared with Gemini. */
class GoogleMapsPlatformKeyStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    fun read(): String? = runCatching {
        val parts = preferences.getString(ENCRYPTED_KEY, null)?.split(":", limit = 2) ?: return null
        require(parts.size == 2)
        Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, Base64.decode(parts[0], Base64.NO_WRAP))) }
            .doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).decodeToString().trim().ifBlank { null }
    }.getOrElse { clear(); null }
    fun write(value: String) { val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }; preferences.edit().putString(ENCRYPTED_KEY, "${Base64.encodeToString(cipher.iv, Base64.NO_WRAP)}:${Base64.encodeToString(cipher.doFinal(value.trim().encodeToByteArray()), Base64.NO_WRAP)}").apply() }
    fun clear() = preferences.edit().remove(ENCRYPTED_KEY).apply()
    private fun key() = (KeyStore.getInstance(KEYSTORE).apply { load(null) }.getKey(KEY_ALIAS, null) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()) }.generateKey()) as javax.crypto.SecretKey
    private companion object { const val PREFERENCES="foresight_google_maps_key"; const val ENCRYPTED_KEY="encrypted_api_key"; const val KEYSTORE="AndroidKeyStore"; const val KEY_ALIAS="foresight_google_maps_platform"; const val TRANSFORMATION="AES/GCM/NoPadding"; const val TAG_BITS=128 }
}
