package io.github.lucateam_wt.tumbler.backup

import io.github.lucateam_wt.tumbler.backup.BackupFixtures.SECRET_A_BASE32
import io.github.lucateam_wt.tumbler.backup.BackupFixtures.entry
import io.github.lucateam_wt.tumbler.backup.BackupFixtures.secretA
import io.github.lucateam_wt.tumbler.backup.BackupFixtures.secretB
import io.github.lucateam_wt.tumbler.backup.ConflictDecision.KEEP_BOTH
import io.github.lucateam_wt.tumbler.backup.ConflictDecision.REPLACE
import io.github.lucateam_wt.tumbler.backup.ConflictDecision.SKIP
import io.github.lucateam_wt.tumbler.domain.model.OtpAccount
import io.github.lucateam_wt.tumbler.domain.model.OtpType
import io.github.lucateam_wt.tumbler.domain.otp.OtpAlgorithm
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

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
        assertEquals(bankAgain.account, replacement.accountToWrite(existingAccounts[1]))
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
        val replacement = writes.replacements.single()
        assertEquals(20L, replacement.accountToWrite(existing("v", vpn)).counter)
        assertEquals(30L, replacement.accountToWrite(existing("v", hotp(30, issuer = "VPN"))).counter)
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

    /** The account written for [replacement] when row "x" holds [storedNow] at write time. */
    private fun written(replacement: Replacement, storedNow: OtpAccount) =
        replacement.accountToWrite(ExistingAccount("x", storedNow))

    /** [replaced], then the account written when the row has not changed since the plan. */
    private fun replacedAccount(existingEntry: BackupEntry, incoming: BackupEntry, expectedReason: ConflictReason) =
        written(replaced(existingEntry, incoming, expectedReason), existingEntry.account)

    @Test
    fun hotpReplace_sameSecret_keepsTheLargerCounter() {
        assertEquals(100L, replacedAccount(hotp(100), hotp(5), same).counter)
        assertEquals(100L, replacedAccount(hotp(5), hotp(100), same).counter)
        assertEquals(7L, replacedAccount(hotp(7), hotp(7), same).counter)
        assertEquals(Long.MAX_VALUE, replacedAccount(hotp(Long.MAX_VALUE), hotp(0), same).counter)
    }

    @Test
    fun hotpReplace_sameSecretDifferentLabel_keepsTheLargerCounter() {
        val incoming = hotp(5, issuer = "VPN (renamed)")
        val account = replacedAccount(hotp(100), incoming, ConflictReason.SAME_SECRET)
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
        val account = replacedAccount(hotp(50), incoming, ConflictReason.SAME_LABEL_AND_SECRET)
        assertEquals(incoming.account.copy(counter = 50), account)
    }

    @Test
    fun hotpReplace_differentSecret_usesTheIncomingCounter() {
        // Only the label matched: the account was re-enrolled with a new secret, so the old
        // counter is ignored even when it is larger.
        val label = ConflictReason.SAME_LABEL
        assertEquals(5L, replacedAccount(hotp(100, secretB()), hotp(5, secretC()), label).counter)
        assertEquals(0L, replacedAccount(hotp(Long.MAX_VALUE, secretB()), hotp(0, secretC()), label).counter)
        assertEquals(100L, replacedAccount(hotp(5, secretB()), hotp(100, secretC()), label).counter)
        val incoming = entry(
            issuer = "VPN",
            accountName = "alice",
            type = OtpType.HOTP,
            secret = secretC(),
            algorithm = OtpAlgorithm.SHA256,
            digits = 8,
            counter = 3,
        )
        assertEquals(incoming.account, replacedAccount(hotp(50, secretB()), incoming, label))
    }

    @Test
    fun replace_bothTotp_takesTheImportedAccount() {
        // TOTP does not use the counter; a stored counter is never carried over.
        val existingTotp = entry(issuer = "Mail", accountName = "alice", secret = secretB(), counter = 50)
        val sameSecret = entry(issuer = "Mail", accountName = "alice", secret = secretB(), period = 60)
        assertEquals(
            sameSecret.account,
            replacedAccount(existingTotp, sameSecret, ConflictReason.SAME_LABEL_AND_SECRET),
        )
        val newSecret = entry(issuer = "Mail", accountName = "alice", secret = secretC())
        assertEquals(newSecret.account, replacedAccount(existingTotp, newSecret, ConflictReason.SAME_LABEL))
    }

    @Test
    fun replace_hotpOnOneSideOnly_takesTheImportedAccount() {
        // The type is part of the label, so a type change can only match by secret.
        val existingTotp = entry(issuer = "VPN", accountName = "alice", secret = secretB(), counter = 99)
        val incomingHotp = hotp(3)
        assertEquals(incomingHotp.account, replacedAccount(existingTotp, incomingHotp, ConflictReason.SAME_SECRET))

        val incomingTotp = entry(issuer = "VPN", accountName = "alice", secret = secretB())
        assertEquals(incomingTotp.account, replacedAccount(hotp(50), incomingTotp, ConflictReason.SAME_SECRET))
    }

    // Write-time counter rule: decided from the row as read when the write happens

    private val same = ConflictReason.SAME_LABEL_AND_SECRET

    /** A TOTP row "VPN:alice" with secret B, the secret of every `hotp(...)`. */
    private fun vpnTotp(counter: Long = 0) = entry(issuer = "VPN", accountName = "alice", secret = secretB(), counter = counter)

    @Test
    fun accountToWrite_sameSecretHotp_usesTheCounterStoredAtWriteTime() {
        // Planned while the stored counter was 100; the user then generated 3 codes (stored
        // 103) and confirmed Replace with an imported counter of 5. Writing the plan-time
        // value (100) would move the counter backwards from 103.
        val replacement = replaced(hotp(100), hotp(5), same)
        assertEquals(100L, resolveCounter(hotp(100).account, hotp(5).account)) // plan-time display hint
        assertEquals(hotp(5).account.copy(counter = 103), written(replacement, hotp(103).account))
        // The imported counter still wins when it is the larger one.
        assertEquals(5L, written(replacement, hotp(0).account).counter)
        assertEquals(5L, written(replacement, hotp(4).account).counter)
    }

    @Test
    fun accountToWrite_sameSecretHotp_equalCounters() {
        val replacement = replaced(hotp(7), hotp(7), same)
        assertEquals(7L, written(replacement, hotp(7).account).counter)
        assertEquals(8L, written(replacement, hotp(8).account).counter)
    }

    @Test
    fun accountToWrite_sameSecretHotp_longMaxValue() {
        val storedMax = replaced(hotp(Long.MAX_VALUE), hotp(0), same)
        assertEquals(Long.MAX_VALUE, written(storedMax, hotp(Long.MAX_VALUE).account).counter)
        assertEquals(0L, written(storedMax, hotp(0).account).counter)

        val importedMax = replaced(hotp(0), hotp(Long.MAX_VALUE), same)
        for (storedNow in listOf(0L, 5L, Long.MAX_VALUE)) {
            assertEquals(Long.MAX_VALUE, written(importedMax, hotp(storedNow).account).counter)
        }
    }

    @Test
    fun accountToWrite_differentSecrets_alwaysUsesTheImportedCounter() {
        val replacement = replaced(hotp(100, secretB()), hotp(5, secretC()), ConflictReason.SAME_LABEL)
        for (storedNow in listOf(0L, 5L, 100L, 103L, Long.MAX_VALUE)) {
            assertEquals("stored $storedNow", 5L, written(replacement, hotp(storedNow, secretB()).account).counter)
        }
    }

    @Test
    fun accountToWrite_totpAndMixedTypes_useTheImportedCounter() {
        val existingTotp = entry(issuer = "Mail", accountName = "alice", secret = secretB(), counter = 50)
        val incomingTotp = entry(issuer = "Mail", accountName = "alice", secret = secretB())
        assertEquals(CounterPolicy.USE_IMPORTED, counterPolicy(existingTotp.account, incomingTotp.account))
        val totp = replaced(existingTotp, incomingTotp, same)
        for (storedNow in listOf(0L, 50L, 103L, Long.MAX_VALUE)) {
            assertEquals(0L, written(totp, existingTotp.account.copy(counter = storedNow)).counter)
        }

        val totpToHotp = replaced(vpnTotp(counter = 99), hotp(3), ConflictReason.SAME_SECRET)
        assertEquals(3L, written(totpToHotp, vpnTotp(counter = 150).account).counter)
        val hotpToTotp = replaced(hotp(50), vpnTotp(), ConflictReason.SAME_SECRET)
        assertEquals(0L, written(hotpToTotp, hotp(53).account).counter)
    }

    @Test
    fun accountToWrite_decidesThePolicyFromTheRowAtWriteTime() {
        // Planned against a HOTP row with the imported secret, which would keep the larger
        // counter. At write time the row holds another secret, or is TOTP: the policy follows
        // the row as it is now, so the imported counter is written.
        val sameSecretAtPlan = replaced(hotp(100), hotp(5), same)
        assertEquals(5L, written(sameSecretAtPlan, hotp(500, secretA()).account).counter)
        assertEquals(5L, written(sameSecretAtPlan, vpnTotp(counter = 500).account).counter)

        // Planned against a HOTP row with another secret (imported counter). At write time
        // the row holds the imported secret: the larger counter is kept.
        val otherSecretAtPlan = replaced(hotp(100, secretA()), hotp(5), ConflictReason.SAME_LABEL)
        assertEquals(500L, written(otherSecretAtPlan, hotp(500).account).counter)
        assertEquals(
            CounterPolicy.KEEP_LARGER_OF_STORED_AND_IMPORTED,
            counterPolicy(hotp(500).account, hotp(5).account),
        )
    }

    @Test
    fun accountToWrite_rejectsAnotherRow() {
        val replacement = replaced(hotp(100), hotp(5), same)
        val error = assertThrows(IllegalArgumentException::class.java) {
            replacement.accountToWrite(ExistingAccount("other-row", hotp(100).account))
        }
        assertFalse(error.message.orEmpty().contains("other-row"))
    }

    @Test
    fun replacement_exposesTheImportedAccountOnlyThroughAccountToWrite() {
        // No getter or public field hands out the imported account with its raw counter.
        val type = Replacement::class.java
        assertEquals(listOf("accountToWrite"), type.methods.filter { it.returnType == OtpAccount::class.java }.map { it.name })
        assertTrue(type.fields.none { it.type == OtpAccount::class.java })
        assertTrue(
            type.declaredFields.filter { it.type == OtpAccount::class.java }.all { Modifier.isPrivate(it.modifiers) },
        )
    }

    // Several existing accounts with the imported HOTP account's secret

    /** A TOTP account with secret B, the secret of every `hotp(...)` below. */
    private val mailTotp = entry(issuer = "Mail", accountName = "alice", secret = secretB())

    @Test
    fun plan_hotpPicksASameSecretHotpCounterpartBeforeAnEarlierTotp() {
        // Existing [TOTP S, HOTP S counter 100], backup HOTP S counter 5, in both orders and
        // with the label matching the HOTP row or not.
        val totpRow = existing("totp", mailTotp)
        val hotpRow = existing("hotp", hotp(100))
        val cases = mapOf(
            hotp(5) to ConflictReason.SAME_LABEL_AND_SECRET,
            hotp(5, issuer = "VPN (renamed)") to ConflictReason.SAME_SECRET,
        )
        for ((incoming, reason) in cases) {
            for (rows in listOf(listOf(totpRow, hotpRow), listOf(hotpRow, totpRow))) {
                val description = "${rows.map { it.id }} $reason"
                val plan = planImport(rows, listOf(incoming))
                val conflict = plan.conflicts.single()
                assertEquals(description, "hotp", conflict.existing.id)
                assertEquals(description, reason, conflict.reason)
                assertEquals(description, listOf(SKIP, REPLACE), allowedDecisions(conflict).toList())

                // Skip: nothing is written.
                val skipped = applyDecisions(plan, listOf(SKIP))
                assertTrue(description, skipped.inserts.isEmpty() && skipped.replacements.isEmpty())

                // Keep both: refused, so no second HOTP row with this secret can appear.
                assertThrows(description, IllegalArgumentException::class.java) { applyDecisions(plan, listOf(KEEP_BOTH)) }

                // Replace: overwrites the HOTP row and never moves its counter backwards,
                // also after codes were generated since the plan.
                val replacement = applyDecisions(plan, listOf(REPLACE)).replacements.single()
                assertEquals(description, "hotp", replacement.existingId)
                assertEquals(description, 100L, replacement.accountToWrite(existing("hotp", hotp(100))).counter)
                assertEquals(description, 103L, replacement.accountToWrite(existing("hotp", hotp(103))).counter)
            }
        }
    }

    @Test
    fun plan_totpStillPicksTheFirstSameSecretAccount() {
        // The HOTP-first step only applies to an imported HOTP account.
        val rows = listOf(existing("hotp", hotp(100)), existing("totp", mailTotp))
        val incoming = entry(issuer = "Other", accountName = "x", secret = secretB())
        val conflict = planImport(rows, listOf(incoming)).conflicts.single()
        assertEquals("hotp", conflict.existing.id)
        assertEquals(listOf(SKIP, KEEP_BOTH, REPLACE), allowedDecisions(conflict).toList())
    }

    private fun <T> permutations(items: List<T>): List<List<T>> =
        if (items.size <= 1) {
            listOf(items)
        } else {
            items.indices.flatMap { i -> permutations(items - items[i]).map { listOf(items[i]) + it } }
        }

    private fun isHotpWithSecretB(account: OtpAccount) =
        account.type == OtpType.HOTP && account.secret.contentEquals(secretB())

    @Test
    fun import_neverAddsOrRewindsAHotpRowWithTheSameSecret() {
        // Every order of five existing rows: two TOTP and two HOTP with secret B, and a HOTP
        // with another secret but the same label as hotp(5). Every decision for three imported
        // HOTP accounts with secret B. The write happens after 3 codes were generated on
        // every HOTP row, and resolves counters against those stored values, as the
        // repository must.
        val rows = listOf(
            existing("totp", mailTotp),
            existing("h100", hotp(100)),
            existing("h200", hotp(200, issuer = "VPN backup")),
            existing("bank", bank),
            existing("label only", hotp(900, secretC())),
        )
        val incomings = listOf(hotp(5), hotp(150, issuer = "VPN (renamed)"), hotp(500, issuer = "Elsewhere"))
        var replaces = 0
        for (order in permutations(rows)) for (incoming in incomings) {
            val plan = planImport(order, listOf(incoming))
            val conflict = plan.conflicts.single()
            val description = "${order.map { it.id }} ${incoming.account.issuer}"
            assertTrue(description, isHotpWithSecretB(conflict.existing.account))
            val storedNow = order.associate { row ->
                row.id to if (row.account.type == OtpType.HOTP) row.account.copy(counter = row.account.counter + 3) else row.account
            }
            for (decision in ConflictDecision.entries) {
                val writes = runCatching { applyDecisions(plan, listOf(decision)) }.getOrNull()
                assertEquals("$description $decision", decision in allowedDecisions(conflict), writes != null)
                if (writes == null) continue
                assertTrue("$description $decision", writes.inserts.none { isHotpWithSecretB(it.account) })
                val after = storedNow.toMutableMap()
                for (replacement in writes.replacements) {
                    replaces++
                    val stored = after.getValue(replacement.existingId)
                    after[replacement.existingId] = replacement.accountToWrite(ExistingAccount(replacement.existingId, stored))
                }
                for ((id, before) in storedNow) {
                    if (!isHotpWithSecretB(before)) continue
                    val now = after.getValue(id)
                    assertTrue("$description $decision $id", isHotpWithSecretB(now) && now.counter >= before.counter)
                }
            }
        }
        assertEquals(120 * 3, replaces)
    }

    // Two HOTP accounts with one secret inside the same backup (documented exception)

    @Test
    fun plan_sameSecretHotpTwiceInsideTheBackup_isInsertedTwice() {
        // A backup holds what the device that wrote it had, so both are inserted without a
        // decision, as for any duplicate inside a backup.
        val first = hotp(5)
        val second = hotp(9, issuer = "VPN copy")
        val plan = planImport(emptyList(), listOf(first, second))
        assertEquals(listOf(first, second), plan.nonConflicting)
        assertEquals(listOf(first, second), applyDecisions(plan, emptyList()).inserts)

        // With a HOTP row with that secret on the device, both conflict with it instead:
        // Skip or Replace each, and only one of them can replace it.
        val withRow = planImport(listOf(existing("h", hotp(100))), listOf(first, second))
        assertEquals(listOf("h", "h"), withRow.conflicts.map { it.existing.id })
        assertThrows(IllegalArgumentException::class.java) { applyDecisions(withRow, listOf(REPLACE, REPLACE)) }
        assertEquals(listOf("h"), applyDecisions(withRow, listOf(SKIP, REPLACE)).replacements.map { it.existingId })
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
