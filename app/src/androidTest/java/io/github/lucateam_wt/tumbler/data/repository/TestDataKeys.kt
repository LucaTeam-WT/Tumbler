package io.github.lucateam_wt.tumbler.data.repository

import io.github.lucateam_wt.tumbler.data.crypto.DataKeyHolder
import io.github.lucateam_wt.tumbler.data.crypto.keystoreDataKeyWrapper
import io.github.lucateam_wt.tumbler.data.db.TumblerDatabase

/**
 * A data-key holder for instrumented tests, over [database]'s `data_keys` table and Keystore
 * aliases under [aliasBase], never the app's. The tests write only legacy rows, so it never
 * creates a key.
 */
internal fun testDataKeyHolder(database: TumblerDatabase, aliasBase: String) =
    DataKeyHolder(RoomDataKeyStore(database), ::keystoreDataKeyWrapper, aliasBase = aliasBase)
