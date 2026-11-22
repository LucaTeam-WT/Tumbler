package io.github.lucateam_wt.tumbler.ui.backup

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import io.github.lucateam_wt.tumbler.backup.BackupFixtures.entry
import io.github.lucateam_wt.tumbler.backup.BackupFixtures.longSecret
import io.github.lucateam_wt.tumbler.backup.BackupFixtures.secretA
import io.github.lucateam_wt.tumbler.backup.BackupFixtures.secretB
import io.github.lucateam_wt.tumbler.backup.ConflictDecision.KEEP_BOTH
import io.github.lucateam_wt.tumbler.backup.ConflictDecision.REPLACE
import io.github.lucateam_wt.tumbler.backup.ConflictDecision.SKIP
import io.github.lucateam_wt.tumbler.backup.ExistingAccount
import io.github.lucateam_wt.tumbler.backup.MAX_PASSWORD_CHARACTERS
import io.github.lucateam_wt.tumbler.backup.PasswordCheck
import io.github.lucateam_wt.tumbler.backup.planImport
import io.github.lucateam_wt.tumbler.domain.model.OtpAccount
import io.github.lucateam_wt.tumbler.domain.model.OtpType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BackupUiModelTest {

    private val copies = mutableListOf<CharArray>()
    private val chars: (CharSequence) -> CharArray = { text -> text.toCharArrayCopy().also { copies += it } }

    private fun feedback(password: String, confirmation: String) = newPasswordFeedback(password, confirmation, chars)

    // ---- live password feedback ----

    @Test
    fun feedback_emptyPassword_showsNoErrorAndCannotSubmit() {
        assertEquals(NewPasswordFeedback(password = null, mismatch = false, canSubmit = false), feedback("", ""))
    }

    @Test
    fun feedback_followsTheExportRules() {
        assertEquals(PasswordCheck.TooShort(12), feedback("a".repeat(11), "a".repeat(11)).password)
        assertFalse(feedback("a".repeat(11), "a".repeat(11)).canSubmit)
        assertEquals(PasswordCheck.TooLong(MAX_PASSWORD_CHARACTERS), feedback("a".repeat(1025), "").password)
        assertEquals(PasswordCheck.InvalidCharacters, feedback("a".repeat(12) + "\ud800", "").password)
        assertTrue(feedback("a".repeat(12), "a".repeat(12)).canSubmit)
        assertTrue(feedback("a".repeat(1024), "a".repeat(1024)).canSubmit)
    }

    @Test
    fun feedback_mismatchIsShownOnlyOnceTheConfirmationIsNotAPrefix() {
        val password = "correct horse battery staple"
        assertFalse(feedback(password, "").mismatch)
        assertFalse("still typing", feedback(password, "correct horse").mismatch)
        assertTrue(feedback(password, "correct horsf").mismatch)
        assertTrue("longer", feedback(password, "$password!").mismatch)
        assertFalse(feedback(password, "$password!").canSubmit)
    }

    @Test
    fun feedback_comparesPasswordsAfterNormalization() {
        // "é" typed as one character and as "e" plus a combining accent: the same scrypt input.
        val composed = "caf\u00e9 au lait!!"
        val decomposed = "cafe\u0301 au lait!!"
        assertTrue(feedback(composed, decomposed).canSubmit)
    }

    @Test
    fun feedback_wipesEveryCopyItMakes() {
        for ((password, confirmation) in listOf("a".repeat(11) to "", "a".repeat(12) to "a".repeat(12), "a".repeat(12) to "b")) {
            feedback(password, confirmation)
        }
        assertTrue(copies.isNotEmpty())
        assertTrue(copies.all { copy -> copy.all { it == '\u0000' } })
    }

    @Test
    fun passwordInput_clearEmptiesTheFieldAndHidesItsText() {
        val input = PasswordInput()
        input.state.setTextAndPlaceCursorAtEnd("correct horse battery staple")
        assertFalse(input.isEmpty)
        assertFalse(input.toString().contains("horse"))

        input.clear()

        assertTrue(input.isEmpty)
        assertEquals("", input.state.text.toString())
    }

    // ---- the review ----

    @Test
    fun planReview_countsNewIdenticalAndConflictingAccounts() {
        val existing = listOf(
            ExistingAccount("id-1", OtpAccount("GitHub", "alice", OtpType.TOTP, longSecret(25))),
            ExistingAccount("id-2", OtpAccount("Bank", "bob", OtpType.HOTP, secretB(), counter = 3)),
            ExistingAccount("id-3", OtpAccount("Mail", "carol", OtpType.TOTP, longSecret(30))),
        )
        val plan = planImport(
            existing,
            listOf(
                entry("GitHub", "alice", secret = secretA()),
                entry("Bank", "bob", OtpType.HOTP, secretB(), counter = 9),
                entry("Mail", "carol", secret = longSecret(30)),
                entry("New", "dave", secret = longSecret(35)),
            ),
        )

        val review = planReview(plan, defaultDecisions(plan))

        assertEquals(1, review.newAccounts)
        assertEquals(1, review.identical)
        assertEquals(listOf(0, 1), review.conflicts.map { it.index })
        assertTrue("never preselects Replace", defaultDecisions(plan).all { it == SKIP })
        val (label, hotp) = review.conflicts
        assertEquals(AccountLabel("GitHub", "alice"), label.imported)
        assertNull(label.counters)
        assertEquals(HotpCounters(stored = 3, imported = 9, replaceKeepsLarger = true), hotp.counters)
        assertEquals(listOf(1, 0, 3), listOf(review.toAdd, review.toReplace, review.toSkip))

        val chosen = planReview(plan, listOf(KEEP_BOTH, REPLACE, SKIP))
        assertEquals(listOf(2, 1, 1), listOf(chosen.toAdd, chosen.toReplace, chosen.toSkip))
    }

    @Test
    fun planReview_hotpWithANewSecretUsesTheImportedCounter() {
        val existing = listOf(ExistingAccount("id-1", OtpAccount("Bank", "bob", OtpType.HOTP, secretA(), counter = 50)))
        val plan = planImport(existing, listOf(entry("Bank", "bob", OtpType.HOTP, secretB(), counter = 2)))

        val counters = planReview(plan, defaultDecisions(plan)).conflicts.single().counters

        assertEquals(HotpCounters(stored = 50, imported = 2, replaceKeepsLarger = false), counters)
    }

    @Test
    fun planReview_marksAReplaceTakenByAnotherConflict() {
        val existing = listOf(ExistingAccount("id-1", OtpAccount("GitHub", "alice", OtpType.TOTP, secretA())))
        val plan = planImport(existing, listOf(entry("GitHub", "alice", secret = secretB()), entry("Other", "x", secret = secretA())))

        val review = planReview(plan, listOf(REPLACE, SKIP))

        assertEquals(listOf(false, true), review.conflicts.map { it.replaceTaken })
        assertTrue(review.conflicts.all { it.sharesCounterpart })
        assertFalse(review.toString().contains("alice"))
    }

    // ---- file name and labels ----

    @Test
    fun suggestedFileName_usesTheDateAndTheBackupExtension() {
        assertEquals("tumbler-backup-2026-11-22.lucabkup", suggestedBackupFileName(LocalDate.of(2026, 11, 22)))
        assertEquals("lucabkup", BACKUP_FILE_EXTENSION)
        assertEquals("application/octet-stream", BACKUP_MIME_TYPE)
    }

    @Test
    fun ellipsize_cutsLongLabelsWithoutSplittingASurrogatePair() {
        assertEquals("short", ellipsize("short", 80))
        assertEquals("aaaa…", ellipsize("a".repeat(10), 5))
        val cut = ellipsize("aaa" + "😀".repeat(5), 5)
        assertEquals("aaa…", cut)
        assertFalse(cut.any { Character.isSurrogate(it) })
    }

    @Test
    fun displayName_matchesTheAccountsList() {
        assertEquals("GitHub (alice)", AccountLabel("GitHub", "alice").displayName())
        assertEquals("alice", AccountLabel("", "alice").displayName())
    }
}
