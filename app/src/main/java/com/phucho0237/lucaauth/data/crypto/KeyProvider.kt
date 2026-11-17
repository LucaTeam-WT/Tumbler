package com.phucho0237.lucaauth.data.crypto

import javax.crypto.SecretKey

/** Supplies the AES key used by [AesGcmSecretCipher]. */
fun interface KeyProvider {

    /**
     * Returns the key, creating it if needed.
     *
     * @throws java.security.GeneralSecurityException if the key cannot be loaded or created.
     */
    fun getKey(): SecretKey
}
