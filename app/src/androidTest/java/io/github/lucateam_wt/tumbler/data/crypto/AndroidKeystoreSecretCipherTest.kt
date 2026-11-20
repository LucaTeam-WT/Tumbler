package io.github.lucateam_wt.tumbler.data.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lucateam_wt.tumbler.domain.crypto.KeyUnavailableException
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore

@RunWith(AndroidJUnit4::class)
class AndroidKeystoreSecretCipherTest {

    private val alias = "lucaauth_test_key_instrumented"
    private val plaintext = "JBSWY3DPEHPK3PXP".toByteArray()
    private val aad = "account-1".toByteArray()

    private fun newCipher() = AesGcmSecretCipher(AndroidKeystoreKeyProvider(alias))

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Before
    @After
    fun deleteTestKey() {
        keyStore().deleteEntry(alias)
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
    fun decryptAfterKeyIsDeleted_throwsKeyUnavailableAndDoesNotRecreateKey() {
        val payload = newCipher().encrypt(plaintext, aad)
        deleteTestKey()
        assertFalse(keyStore().containsAlias(alias))

        assertThrows(KeyUnavailableException::class.java) { newCipher().decrypt(payload, aad) }

        // Decrypt must not have silently created a new key: a second call still finds none.
        assertFalse(keyStore().containsAlias(alias))
        assertNull(AndroidKeystoreKeyProvider(alias).getExistingKey())
        assertThrows(KeyUnavailableException::class.java) { newCipher().decrypt(payload, aad) }
        assertFalse(keyStore().containsAlias(alias))
    }

    @Test
    fun getExistingKey_neverCreatesKey() {
        val provider = AndroidKeystoreKeyProvider(alias)
        assertNull(provider.getExistingKey())
        assertFalse(keyStore().containsAlias(alias))

        provider.getOrCreateKey()
        assertNotNull(provider.getExistingKey())
    }
}
