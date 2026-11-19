package com.phucho0237.lucaauth.backup

import com.phucho0237.lucaauth.backup.BackupFixtures.SECRET_A_BASE32
import com.phucho0237.lucaauth.backup.BackupFixtures.entry
import com.phucho0237.lucaauth.backup.BackupFixtures.secretA
import com.phucho0237.lucaauth.backup.BackupFixtures.secretB
import com.phucho0237.lucaauth.backup.ConflictDecision.KEEP_BOTH
import com.phucho0237.lucaauth.backup.ConflictDecision.REPLACE
import com.phucho0237.lucaauth.backup.ConflictDecision.SKIP
import com.phucho0237.lucaauth.domain.model.OtpType
import com.phucho0237.lucaauth.domain.otp.OtpAlgorithm
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportPlannerTest {

    /** Secret B with its last byte changed: a different, still public, test secret. */
    private fun secretC() = secretB().also { it[it.lastIndex] = (it.last() + 1).toByte() }

    private fun existing(id: String, backup: BackupEntry) = ExistingAccount(id, backup.account)

    private val github = entry(issuer = "GitHub", accountName = "alice", secret = secretA())
    private val bank = entry(issuer = "Bank", accountName = "alice", secret = secretB())

    @Test
    fun plan_withoutExistingAccounts_hasNoConflicts() {
        val plan = planImport(emptyList(), listOf(github, bank))
        assertEquals(listOf(github, bank), plan.nonConflicting)
        assertEquals(emptyList<ImportConflict>(), plan.conflicts)
        val writes = applyDecisions(plan, emptyList())
        assertEquals(listOf(github, bank), writes.inserts)
        assertEquals(emptyList<Replacement>(), writes.replacements)
    }

    @Test
    fun plan_emptyImport() {
        val plan = planImport(listOf(existing("1", github)), emptyList())
        assertEquals(emptyList<BackupEntry>(), plan.nonConflicting)
        assertEquals(emptyList<ImportConflict>(), plan.conflicts)
    }

    @Test
    fun plan_sameLabelDifferentSecret_isConflict() {
        val incoming = entry(issuer = "GitHub", accountName = "alice", secret = secretC())
        val conflict = planImport(listOf(existing("1", github)), listOf(incoming)).conflicts.single()
        assertEquals(ConflictReason.SAME_LABEL, conflict.reason)
        assertEquals("1", conflict.existing.id)
        assertSame(incoming, conflict.incoming)
    }

    @Test
    fun plan_sameSecretDifferentLabel_isConflict() {
        val incoming = entry(issuer = "GitHub Enterprise", accountName = "a.l.i.c.e", secret = secretA())
        val conflict = planImport(listOf(existing("1", github)), listOf(incoming)).conflicts.single()
        assertEquals(ConflictReason.SAME_SECRET, conflict.reason)
    }

    @Test
    fun plan_sameSecretDifferentType_isConflict() {
        val incoming = entry(issuer = "Other", accountName = "x", type = OtpType.HOTP, secret = secretA())
        assertEquals(ConflictReason.SAME_SECRET, planImport(listOf(existing("1", github)), listOf(incoming)).conflicts.single().reason)
    }

    @Test
    fun plan_sameLabelAndSecret_isConflict() {
        val incoming = github.copy(createdAtMillis = 5)
        val conflict = planImport(listOf(existing("1", github)), listOf(incoming)).conflicts.single()
        assertEquals(ConflictReason.SAME_LABEL_AND_SECRET, conflict.reason)
    }

    @Test
    fun plan_labelMatchIsExactAndIncludesType() {
        val existingAccounts = listOf(existing("1", github))
        listOf(
            entry(issuer = "github", accountName = "alice", secret = secretC()),
            entry(issuer = "GitHub ", accountName = "alice", secret = secretC()),
            entry(issuer = "GitHub", accountName = "Alice", secret = secretC()),
            entry(issuer = "GitHub", accountName = "alice", type = OtpType.HOTP, secret = secretC()),
        ).forEach { incoming ->
            val plan = planImport(existingAccounts, listOf(incoming))
            assertEquals(listOf(incoming), plan.nonConflicting)
            assertTrue(plan.conflicts.isEmpty())
        }
    }

    @Test
    fun plan_otherFieldsDoNotMatter() {
        val incoming = entry(
            issuer = "GitHub",
            accountName = "alice",
            secret = secretC(),
            algorithm = OtpAlgorithm.SHA512,
            digits = 8,
            period = 60,
        )
        assertEquals(1, planImport(listOf(existing("1", github)), listOf(incoming)).conflicts.size)
    }

    @Test
    fun plan_secretMatchIsPreferredCounterpart() {
        // "label" matches by label only, "secret" by secret only; the secret match wins.
        val byLabel = existing("label", entry(issuer = "GitHub", accountName = "alice", secret = secretC()))
        val bySecret = existing("secret", entry(issuer = "Other", accountName = "bob", secret = secretA()))
        val conflict = planImport(listOf(byLabel, bySecret), listOf(github)).conflicts.single()
        assertEquals("secret", conflict.existing.id)
        assertEquals(ConflictReason.SAME_SECRET, conflict.reason)
    }

    @Test
    fun plan_firstMatchingExistingAccountIsTheCounterpart() {
        val first = existing("first", entry(issuer = "A", accountName = "a", secret = secretA()))
        val second = existing("second", entry(issuer = "B", accountName = "b", secret = secretA()))
        assertEquals("first", planImport(listOf(first, second), listOf(github)).conflicts.single().existing.id)
    }

    @Test
    fun plan_duplicatesInsideTheBackupAreNotConflicts() {
        val plan = planImport(emptyList(), listOf(github, github))
        assertEquals(listOf(github, github), plan.nonConflicting)
    }

    @Test
    fun plan_rejectsDuplicateExistingIds() {
        assertThrows(IllegalArgumentException::class.java) {
            planImport(listOf(existing("1", github), existing("1", bank)), listOf(github))
        }
    }

    @Test
    fun plan_doesNotModifyInputs() {
        val existingAccounts = listOf(existing("1", github))
        val incoming = listOf(github, bank)
        applyDecisions(planImport(existingAccounts, incoming), listOf(REPLACE))
        assertArrayEquals(secretA(), existingAccounts[0].account.secret)
        assertArrayEquals(secretA(), incoming[0].account.secret)
        assertArrayEquals(secretB(), incoming[1].account.secret)
    }

    @Test
    fun decisions_skipKeepBothReplace() {
        val existingAccounts = listOf(existing("g", github), existing("b", bank))
        val newOne = entry(issuer = "New", accountName = "carol", secret = secretC())
        val githubAgain = github.copy(createdAtMillis = 1)
        val bankAgain = entry(issuer = "Bank", accountName = "alice", secret = secretC())
        val plan = planImport(existingAccounts, listOf(githubAgain, newOne, bankAgain))
        assertEquals(listOf(newOne), plan.nonConflicting)
        assertEquals(listOf("g", "b"), plan.conflicts.map { it.existing.id })

        assertEquals(listOf(newOne), applyDecisions(plan, listOf(SKIP, SKIP)).inserts)
        assertEquals(listOf(githubAgain, newOne, bankAgain), applyDecisions(plan, listOf(KEEP_BOTH, KEEP_BOTH)).inserts)

        val writes = applyDecisions(plan, listOf(SKIP, REPLACE))
        assertEquals(listOf(newOne), writes.inserts)
        val replacement = writes.replacements.single()
        assertEquals("b", replacement.existingId)
        assertEquals(bankAgain.account, replacement.account)
    }

    @Test
    fun decisions_insertsKeepBackupOrder() {
        val first = entry(issuer = "First", accountName = "1", secret = secretC())
        val conflicting = github.copy(createdAtMillis = 2)
        val last = entry(issuer = "Last", accountName = "3", secret = secretB())
        val plan = planImport(listOf(existing("g", github)), listOf(first, conflicting, last))
        assertEquals(listOf(first, conflicting, last), applyDecisions(plan, listOf(KEEP_BOTH)).inserts)
    }

    @Test
    fun decisions_neverOverwriteWithoutAnExplicitDecision() {
        val plan = planImport(listOf(existing("g", github), existing("b", bank)), listOf(github, bank))
        assertEquals(emptyList<BackupEntry>(), plan.nonConflicting)
        for (decisions in listOf(emptyList(), listOf(REPLACE), listOf(REPLACE, REPLACE, REPLACE))) {
            assertThrows(decisions.toString(), IllegalArgumentException::class.java) { applyDecisions(plan, decisions) }
        }
    }

    @Test
    fun decisions_twoReplacementsOfTheSameAccountAreRejected() {
        val one = entry(issuer = "GitHub", accountName = "alice", secret = secretC())
        val two = entry(issuer = "Other", accountName = "x", secret = secretA())
        val plan = planImport(listOf(existing("g", github)), listOf(one, two))
        assertEquals(listOf("g", "g"), plan.conflicts.map { it.existing.id })
        assertThrows(IllegalArgumentException::class.java) { applyDecisions(plan, listOf(REPLACE, REPLACE)) }
        val writes = applyDecisions(plan, listOf(REPLACE, KEEP_BOTH))
        assertEquals(listOf(two), writes.inserts)
        assertEquals(listOf("g"), writes.replacements.map { it.existingId })
    }

    // Allowed decisions: no Keep both for two HOTP accounts with the same secret

    private fun conflictOf(existingEntry: BackupEntry, incoming: BackupEntry, expectedReason: ConflictReason): ImportConflict {
        val conflict = planImport(listOf(existing("x", existingEntry)), listOf(incoming)).conflicts.single()
        assertEquals(expectedReason, conflict.reason)
        return conflict
    }

    private val sameSecretHotpConflicts
        get() = listOf(
            conflictOf(hotp(100), hotp(5), ConflictReason.SAME_LABEL_AND_SECRET),
            conflictOf(hotp(5), hotp(5), ConflictReason.SAME_LABEL_AND_SECRET),
            conflictOf(hotp(100), hotp(5, issuer = "VPN (renamed)"), ConflictReason.SAME_SECRET),
        )

    private val otherConflicts
        get() = listOf(
            // HOTP, different secrets
            conflictOf(hotp(100, secretB()), hotp(5, secretC()), ConflictReason.SAME_LABEL),
            // TOTP, same and different secrets
            conflictOf(github, github.copy(createdAtMillis = 1), ConflictReason.SAME_LABEL_AND_SECRET),
            conflictOf(github, entry(issuer = "Other", accountName = "x", secret = secretA()), ConflictReason.SAME_SECRET),
            conflictOf(github, entry(issuer = "GitHub", accountName = "alice", secret = secretC()), ConflictReason.SAME_LABEL),
            // HOTP on one side only, same secret
            conflictOf(entry(issuer = "VPN", accountName = "alice", secret = secretB()), hotp(3), ConflictReason.SAME_SECRET),
            conflictOf(hotp(3), entry(issuer = "VPN", accountName = "alice", secret = secretB()), ConflictReason.SAME_SECRET),
        )

    @Test
    fun allowedDecisions_sameSecretHotp_areSkipAndReplace() {
        for (conflict in sameSecretHotpConflicts) {
            assertEquals(listOf(SKIP, REPLACE), allowedDecisions(conflict).toList())
        }
    }

    @Test
    fun allowedDecisions_otherwise_areAllThreeInDisplayOrder() {
        for (conflict in otherConflicts) {
            assertEquals(listOf(SKIP, KEEP_BOTH, REPLACE), allowedDecisions(conflict).toList())
        }
    }

    @Test
    fun applyDecisions_rejectsKeepBothForSameSecretHotp() {
        for (conflict in sameSecretHotpConflicts) {
            val plan = planImport(listOf(ExistingAccount("x", conflict.existing.account)), listOf(conflict.incoming))
            val error = assertThrows(IllegalArgumentException::class.java) { applyDecisions(plan, listOf(KEEP_BOTH)) }
            assertFalse(error.message.orEmpty().contains("VPN") || error.message.orEmpty().contains("alice"))
            assertEquals(emptyList<BackupEntry>(), applyDecisions(plan, listOf(SKIP)).inserts)
            assertEquals(listOf("x"), applyDecisions(plan, listOf(REPLACE)).replacements.map { it.existingId })
        }
    }

    @Test
    fun applyDecisions_checksEachConflictSeparately() {
        // First conflict: TOTP (Keep both allowed). Second: same-secret HOTP (not allowed).
        val vpn = hotp(10, issuer = "VPN")
        val plan = planImport(
            listOf(existing("g", github), existing("v", vpn)),
            listOf(github.copy(createdAtMillis = 1), hotp(20, issuer = "VPN")),
        )
        assertEquals(listOf("g", "v"), plan.conflicts.map { it.existing.id })
        assertThrows(IllegalArgumentException::class.java) { applyDecisions(plan, listOf(KEEP_BOTH, KEEP_BOTH)) }
        assertThrows(IllegalArgumentException::class.java) { applyDecisions(plan, listOf(SKIP, KEEP_BOTH)) }
        val writes = applyDecisions(plan, listOf(KEEP_BOTH, REPLACE))
        assertEquals(1, writes.inserts.size)
        assertEquals(20L, writes.replacements.single().account.counter)
    }

    @Test
    fun applyDecisions_acceptsExactlyTheAllowedDecisions() {
        for (conflict in sameSecretHotpConflicts + otherConflicts) {
            val plan = planImport(listOf(ExistingAccount("x", conflict.existing.account)), listOf(conflict.incoming))
            for (decision in ConflictDecision.entries) {
                val accepted = runCatching { applyDecisions(plan, listOf(decision)) }.isSuccess
                assertEquals("$decision for ${conflict.reason}", decision in allowedDecisions(conflict), accepted)
            }
        }
    }

    // Counter rule on Replace: larger counter only for HOTP with the same secret

    private fun hotp(counter: Long, secret: ByteArray = secretB(), issuer: String = "VPN") =
        entry(issuer = issuer, accountName = "alice", type = OtpType.HOTP, secret = secret, counter = counter)

    /** Replaces [existingEntry] with [incoming] and checks why they conflicted. */
    private fun replaced(existingEntry: BackupEntry, incoming: BackupEntry, expectedReason: ConflictReason): Replacement {
        val plan = planImport(listOf(existing("x", existingEntry)), listOf(incoming))
        assertEquals(expectedReason, plan.conflicts.single().reason)
        return applyDecisions(plan, listOf(REPLACE)).replacements.single()
    }

    @Test
    fun hotpReplace_sameSecret_keepsTheLargerCounter() {
        val same = ConflictReason.SAME_LABEL_AND_SECRET
        assertEquals(100L, replaced(hotp(100), hotp(5), same).account.counter)
        assertEquals(100L, replaced(hotp(5), hotp(100), same).account.counter)
        assertEquals(7L, replaced(hotp(7), hotp(7), same).account.counter)
        assertEquals(Long.MAX_VALUE, replaced(hotp(Long.MAX_VALUE), hotp(0), same).account.counter)
    }

    @Test
    fun hotpReplace_sameSecretDifferentLabel_keepsTheLargerCounter() {
        val incoming = hotp(5, issuer = "VPN (renamed)")
        val account = replaced(hotp(100), incoming, ConflictReason.SAME_SECRET).account
        assertEquals(incoming.account.copy(counter = 100), account)
    }

    @Test
    fun hotpReplace_sameSecret_takesEveryOtherFieldFromTheImport() {
        val incoming = entry(
            issuer = "VPN",
            accountName = "alice",
            type = OtpType.HOTP,
            secret = secretB(),
            algorithm = OtpAlgorithm.SHA256,
            digits = 8,
            counter = 3,
        )
        val account = replaced(hotp(50), incoming, ConflictReason.SAME_LABEL_AND_SECRET).account
        assertEquals(incoming.account.copy(counter = 50), account)
    }

    @Test
    fun hotpReplace_differentSecret_usesTheIncomingCounter() {
        // Only the label matched: the account was re-enrolled with a new secret, so the old
        // counter is ignored even when it is larger.
        val label = ConflictReason.SAME_LABEL
        assertEquals(5L, replaced(hotp(100, secretB()), hotp(5, secretC()), label).account.counter)
        assertEquals(0L, replaced(hotp(Long.MAX_VALUE, secretB()), hotp(0, secretC()), label).account.counter)
        assertEquals(100L, replaced(hotp(5, secretB()), hotp(100, secretC()), label).account.counter)
        val incoming = entry(
            issuer = "VPN",
            accountName = "alice",
            type = OtpType.HOTP,
            secret = secretC(),
            algorithm = OtpAlgorithm.SHA256,
            digits = 8,
            counter = 3,
        )
        assertEquals(incoming.account, replaced(hotp(50, secretB()), incoming, label).account)
    }

    @Test
    fun replace_bothTotp_takesTheImportedAccount() {
        // TOTP does not use the counter; a stored counter is never carried over.
        val existingTotp = entry(issuer = "Mail", accountName = "alice", secret = secretB(), counter = 50)
        val sameSecret = entry(issuer = "Mail", accountName = "alice", secret = secretB(), period = 60)
        assertEquals(
            sameSecret.account,
            replaced(existingTotp, sameSecret, ConflictReason.SAME_LABEL_AND_SECRET).account,
        )
        val newSecret = entry(issuer = "Mail", accountName = "alice", secret = secretC())
        assertEquals(newSecret.account, replaced(existingTotp, newSecret, ConflictReason.SAME_LABEL).account)
    }

    @Test
    fun replace_hotpOnOneSideOnly_takesTheImportedAccount() {
        // The type is part of the label, so a type change can only match by secret.
        val existingTotp = entry(issuer = "VPN", accountName = "alice", secret = secretB(), counter = 99)
        val incomingHotp = hotp(3)
        assertEquals(incomingHotp.account, replaced(existingTotp, incomingHotp, ConflictReason.SAME_SECRET).account)

        val incomingTotp = entry(issuer = "VPN", accountName = "alice", secret = secretB())
        assertEquals(incomingTotp.account, replaced(hotp(50), incomingTotp, ConflictReason.SAME_SECRET).account)
    }

    @Test
    fun toString_neverShowsSecretsOrLabels() {
        val plan = planImport(listOf(existing("id-1", github)), listOf(github, bank))
        val writes = applyDecisions(plan, listOf(REPLACE))
        val texts = listOf(
            existing("id-1", github).toString(),
            plan.conflicts.single().toString(),
            plan.toString(),
            writes.toString(),
            writes.replacements.single().toString(),
        )
        for (text in texts) {
            assertFalse(text, text.contains("GitHub") || text.contains("alice") || text.contains("Bank"))
            assertFalse(text, text.contains(SECRET_A_BASE32))
            assertFalse(text, text.contains(secretA().contentToString()))
            assertFalse(text, text.contains(String(secretA(), Charsets.ISO_8859_1)))
        }
        assertEquals("ImportPlan(nonConflicting=1, conflicts=1)", plan.toString())
    }
}
