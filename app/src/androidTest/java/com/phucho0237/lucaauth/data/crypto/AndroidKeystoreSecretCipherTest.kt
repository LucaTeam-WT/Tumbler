package com.phucho0237.lucaauth.data.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phucho0237.lucaauth.domain.crypto.SecretCipherException
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore

@RunWith(AndroidJUnit4::class)
class AndroidKeystoreSecretCipherTest {

    private val alias = "lucaauth_test_key_instrumented"
    private val plaintext = "JBSWY3DPEHPK3PXP".toByteArray()
    private val aad = "account-1".toByteArray()

    private fun newCipher() = AesGcmSecretCipher(AndroidKeystoreKeyProvider(alias))

    @After
    fun deleteTestKey() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    @Test
    fun roundTrip_withRealKeystore() {
        val cipher = newCipher()
        assertArrayEquals(plaintext, cipher.decrypt(cipher.encrypt(plaintext, aad), aad))
    }

    @Test
    fun keyPersistsAcrossProviderInstances() {
        val payload = newCipher().encrypt(plaintext, aad)
        assertArrayEquals(plaintext, newCipher().decrypt(payload, aad))
    }

    @Test
    fun decryptFailsAfterKeyIsDeleted() {
        val payload = newCipher().encrypt(plaintext, aad)
        deleteTestKey()
        assertThrows(SecretCipherException::class.java) { newCipher().decrypt(payload, aad) }
    }
}
