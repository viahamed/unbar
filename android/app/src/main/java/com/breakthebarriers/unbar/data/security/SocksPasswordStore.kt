package com.breakthebarriers.unbar.data.security

import android.content.Context
import android.os.Build
import android.security.KeyPairGeneratorSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Calendar
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.security.auth.x500.X500Principal

/**
 * Encrypts and stores the optional local SOCKS5 proxy password using Android Keystore.
 * - API 23+: Direct AES-256-GCM SecretKey inside AndroidKeyStore.
 * - API 21-22: Random AES-256 key wrapped with an RSA key pair in AndroidKeyStore.
 * The plaintext password is never persisted to disk and never included in config exports.
 */
class SocksPasswordStore(private val context: Context) {

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS_AES = "unbar_socks_aes_v1"
        private const val KEY_ALIAS_RSA = "unbar_socks_rsa_v1"
        private const val PREFS_NAME = "unbar_secure_socks"
        private const val PREF_CIPHERTEXT = "socks_pass_ct"
        private const val PREF_IV = "socks_pass_iv"
        private const val PREF_WRAPPED_AES = "socks_wrapped_aes"
        private const val AES_GCM = "AES/GCM/NoPadding"
        private const val RSA_PKCS1 = "RSA/ECB/PKCS1Padding"
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun hasPassword(): Boolean {
        return !prefs.getString(PREF_CIPHERTEXT, null).isNullOrEmpty()
    }

    fun savePassword(password: String) {
        if (password.isEmpty()) {
            clearPassword()
            return
        }
        val secretKey = getOrCreateSecretKey()
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(password.toByteArray(Charsets.UTF_8))

        prefs.edit()
            .putString(PREF_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString(PREF_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
            .apply()
    }

    fun getPassword(): String {
        val ctB64 = prefs.getString(PREF_CIPHERTEXT, null) ?: return ""
        val ivB64 = prefs.getString(PREF_IV, null) ?: return ""
        return try {
            val secretKey = getOrCreateSecretKey()
            val ciphertext = Base64.decode(ctB64, Base64.NO_WRAP)
            val iv = Base64.decode(ivB64, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    fun clearPassword() {
        prefs.edit()
            .remove(PREF_CIPHERTEXT)
            .remove(PREF_IV)
            .apply()
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val existing = keyStore.getKey(KEY_ALIAS_AES, null) as? SecretKey
            if (existing != null) return existing

            val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS_AES,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGen.init(spec)
            return keyGen.generateKey()
        }

        // Fallback for Android 5.0 - 5.1 (API 21 - 22)
        if (!keyStore.containsAlias(KEY_ALIAS_RSA)) {
            val start = Calendar.getInstance()
            val end = Calendar.getInstance().apply { add(Calendar.YEAR, 25) }
            @Suppress("DEPRECATION")
            val spec = KeyPairGeneratorSpec.Builder(context)
                .setAlias(KEY_ALIAS_RSA)
                .setSubject(X500Principal("CN=UnbarSocksKey"))
                .setSerialNumber(BigInteger.ONE)
                .setStartDate(start.time)
                .setEndDate(end.time)
                .build()
            val kpg = KeyPairGenerator.getInstance("RSA", KEYSTORE_PROVIDER)
            kpg.initialize(spec)
            kpg.generateKeyPair()
        }

        val entry = keyStore.getEntry(KEY_ALIAS_RSA, null) as KeyStore.PrivateKeyEntry
        val wrappedB64 = prefs.getString(PREF_WRAPPED_AES, null)
        return if (wrappedB64 != null) {
            val wrapped = Base64.decode(wrappedB64, Base64.NO_WRAP)
            val rsaCipher = Cipher.getInstance(RSA_PKCS1)
            rsaCipher.init(Cipher.DECRYPT_MODE, entry.privateKey)
            val rawKey = rsaCipher.doFinal(wrapped)
            SecretKeySpec(rawKey, "AES")
        } else {
            val rawKey = ByteArray(32)
            SecureRandom().nextBytes(rawKey)
            val rsaCipher = Cipher.getInstance(RSA_PKCS1)
            rsaCipher.init(Cipher.ENCRYPT_MODE, entry.certificate.publicKey)
            val wrapped = rsaCipher.doFinal(rawKey)
            prefs.edit()
                .putString(PREF_WRAPPED_AES, Base64.encodeToString(wrapped, Base64.NO_WRAP))
                .apply()
            SecretKeySpec(rawKey, "AES")
        }
    }
}
