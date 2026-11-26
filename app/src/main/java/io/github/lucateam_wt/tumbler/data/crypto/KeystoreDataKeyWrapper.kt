package io.github.lucateam_wt.tumbler.data.crypto

/**
 * The app's [DataKeyWrapper] for Keystore [alias]: AES-256-GCM with a non-exportable Keystore key,
 * created on the first wrap (StrongBox requested, TEE fallback, no user authentication; see
 * [AndroidKeystoreKeyProvider]). Unwrapping never creates the key.
 */
internal fun keystoreDataKeyWrapper(alias: String): DataKeyWrapper =
    AesGcmDataKeyWrapper(AesGcmSecretCipher(AndroidKeystoreKeyProvider(alias)))
