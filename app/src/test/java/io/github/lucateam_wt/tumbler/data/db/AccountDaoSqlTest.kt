package io.github.lucateam_wt.tumbler.data.db

import io.github.lucateam_wt.tumbler.domain.model.OtpType
import org.junit.Assert.assertEquals
import org.junit.Test

class AccountDaoSqlTest {

    /**
     * [AccountDao.updateCounter] filters on the literal `type = 'HOTP'`: Room `@Query`
     * strings must be compile-time constants, so the SQL cannot reference
     * `OtpType.HOTP.name`. Rows store enum names, so renaming [OtpType.HOTP] without
     * updating that SQL (and migrating stored rows) would make every HOTP counter update
     * match no row. This test fails first. Update both places together.
     */
    @Test
    fun hotpEnumName_matchesLiteralInUpdateCounterSql() {
        assertEquals("HOTP", OtpType.HOTP.name)
    }
}
