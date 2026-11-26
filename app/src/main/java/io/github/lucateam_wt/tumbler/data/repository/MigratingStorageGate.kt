package io.github.lucateam_wt.tumbler.data.repository

import io.github.lucateam_wt.tumbler.data.crypto.DataKeyHolder
import io.github.lucateam_wt.tumbler.data.crypto.WriteKeyStatus
import io.github.lucateam_wt.tumbler.domain.repository.StorageGate
import io.github.lucateam_wt.tumbler.domain.repository.StorageState
import io.github.lucateam_wt.tumbler.domain.repository.WriteAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [StorageGate] over a [SecretMigrator]: one run at a time, in the process's [scope], so a run is
 * never cancelled by a screen going away (a run killed with the process resumes on the next start).
 *
 * A run converts the legacy rows ([SecretMigrator.run], which reports "N of M"), then checks the
 * write key without creating one ([DataKeyHolder.writeKeyStatus]). On a cold start after the
 * migration that is one query and one unwrap; nothing else here calls the Keystore. A key is only
 * ever made by the migrator's first run on an install with legacy rows (version 1), or by
 * [startNewKey], which the user confirmed.
 *
 * Every failure of a run ends in [StorageState.Ready] with a retry advised, never in a crash or a
 * state that never ends.
 */
internal class MigratingStorageGate(
    private val migrator: SecretMigrator,
    private val dataKeys: DataKeyHolder,
    private val scope: CoroutineScope,
) : StorageGate {

    private val _state = MutableStateFlow<StorageState>(StorageState.Preparing())
    override val state: StateFlow<StorageState> = _state.asStateFlow()

    private val started = AtomicBoolean(false)
    private val runs = Mutex()

    /** Runs that have ended; written under [runs]. */
    private var generation = 0

    override fun start() {
        if (started.compareAndSet(false, true)) scope.launch { run() }
    }

    override fun retry() {
        started.set(true)
        // Preparing at once, so a backup that waits in [awaitReady] does not go on with the old outcome.
        _state.value = StorageState.Preparing()
        scope.launch { run(forgetFailures = true) }
    }

    override suspend fun startNewKey(): Boolean {
        started.set(true)
        _state.value = StorageState.Preparing()
        // In the process scope: leaving the screen does not cancel a key half made, or the run after it.
        return scope.async { run(forgetFailures = true, newKey = true) }.await()
    }

    override suspend fun awaitReady() {
        start()
        state.first { it is StorageState.Ready }
    }

    /** @return false if [newKey] was asked for and no key could be made or checked. */
    private suspend fun run(forgetFailures: Boolean = false, newKey: Boolean = false): Boolean = runs.withLock {
        _state.value = StorageState.Preparing()
        if (forgetFailures) dataKeys.clearFailures()
        val keyMade = !newKey || attempt { dataKeys.startNewKey() } != null
        val result = attempt { migrator.run { done, total -> _state.value = StorageState.Preparing(done, total) } }
        val access = when (attempt { dataKeys.writeKeyStatus() }) {
            WriteKeyStatus.NOT_CREATED, WriteKeyStatus.READY -> WriteAccess.AVAILABLE
            WriteKeyStatus.NEW_KEY_REQUIRED -> WriteAccess.NEW_KEY_REQUIRED
            WriteKeyStatus.TEMPORARILY_UNAVAILABLE, null -> WriteAccess.TEMPORARILY_UNAVAILABLE
        }
        generation++
        _state.value = StorageState.Ready(
            writeAccess = access,
            retryAdvised = !keyMade || result == null || result.retryAdvised || access == WriteAccess.TEMPORARILY_UNAVAILABLE,
            generation = generation,
        )
        keyMade
    }

    /** [block]'s result, or `null` if it failed; cancellation is never caught. Nothing is logged. */
    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
