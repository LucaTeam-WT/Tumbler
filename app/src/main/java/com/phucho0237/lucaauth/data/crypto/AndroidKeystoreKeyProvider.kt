package com.phucho0237.lucaauth.data.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.annotation.RequiresApi
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * [KeyProvider] backed by a non-exportable AES-256 key in the Android Keystore.
 *
 * [getOrCreateKey] creates the key on first use (StrongBox-backed when the device
 * supports it, otherwise TEE-backed) and reuses it on later calls, including from
 * other instances with the same [alias]. Creation is serialized process-wide, so
 * concurrent first calls cannot create two keys. [getExistingKey] only loads the key
 * and never creates one.
 *
 * The key does not require user authentication; app lock is a separate feature. Key
 * material never leaves the Keystore, so the key is lost when app data is cleared or
 * the app is uninstalled, and it is not part of backups or device transfers.
 *
 * Both methods can throw:
 * - [java.security.KeyStoreException] or another
 *   [java.security.GeneralSecurityException] if the Keystore cannot be opened or the
 *   entry cannot be read ([java.security.UnrecoverableKeyException]) or, for
 *   [getOrCreateKey], generated;
 * - [java.io.IOException] from loading the Keystore;
 * - [ProviderException] for internal Keystore or secure-hardware errors. A
 *   [ProviderException] during StrongBox key generation is not thrown; generation then
 *   falls back to the TEE, whose errors are thrown.
 */
class AndroidKeystoreKeyProvider(
    private val alias: String = DEFAULT_ALIAS,
) : KeyProvider {

    override fun getOrCreateKey(): SecretKey = synchronized(LOCK) {
        loadKey() ?: createKey()
    }

    override fun getExistingKey(): SecretKey? = loadKey()

    private fun loadKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.getKey(alias, null) as? SecretKey
    }

    private fun createKey(): SecretKey {
        val strongBoxKey =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) createStrongBoxKeyOrNull() else null
        return strongBoxKey ?: generate(specBuilder().build())
    }

    /**
     * Returns `null` if StrongBox key generation fails for any reason (no StrongBox,
     * or any other [ProviderException]), so the caller falls back to the TEE.
     */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun createStrongBoxKeyOrNull(): SecretKey? = try {
        generate(specBuilder().setIsStrongBoxBacked(true).build())
    } catch (e: ProviderException) {
        null
    }

    private fun specBuilder() = KeyGenParameterSpec.Builder(
        alias,
        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
    )
        .setKeySize(KEY_SIZE_BITS)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setRandomizedEncryptionRequired(true)
        .setUserAuthenticationRequired(false)

    private fun generate(spec: KeyGenParameterSpec): SecretKey =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()

    companion object {
        const val DEFAULT_ALIAS = "lucaauth_secret_key_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_SIZE_BITS = 256
        private val LOCK = Any()
    }
}
