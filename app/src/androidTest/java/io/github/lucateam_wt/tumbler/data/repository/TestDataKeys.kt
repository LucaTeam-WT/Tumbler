package io.github.lucateam_wt.tumbler.data.repository

import io.github.lucateam_wt.tumbler.data.crypto.DataKeyHolder
import io.github.lucateam_wt.tumbler.data.crypto.keystoreDataKeyWrapper
import io.github.lucateam_wt.tumbler.data.db.TumblerDatabase
import java.security.KeyStore

/**
 * A data-key holder for instrumented tests, over [database]'s `data_keys` table and Keystore
 * aliases under [aliasBase] (`<aliasBase>` for version 1, `<aliasBase>_k<n>` after it), never the
 * app's. Every write creates or uses such a key, so tests clean up with [deleteTestKeys].
 */
internal fun testDataKeyHolder(database: TumblerDatabase, aliasBase: String) =
    DataKeyHolder(RoomDataKeyStore(database), ::keystoreDataKeyWrapper, aliasBase = aliasBase)

/**
 * Deletes every Android Keystore entry whose alias starts with [prefix]: a test's legacy key and
 * the wrapping keys of the data keys made under it (`<prefix>_wrap`, `<prefix>_wrap_k2`, ...).
 */
internal fun deleteTestKeys(prefix: String) {
    val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    keyStore.aliases().toList().filter { it.startsWith(prefix) }.forEach(keyStore::deleteEntry)
}
