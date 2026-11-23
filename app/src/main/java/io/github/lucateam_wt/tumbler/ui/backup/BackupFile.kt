package io.github.lucateam_wt.tumbler.ui.backup

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate

/**
 * MIME type of a backup file for the Storage Access Framework. The format has no registered
 * type, so this is the generic binary one; the file is recognised by [BACKUP_FILE_EXTENSION].
 */
internal const val BACKUP_MIME_TYPE = "application/octet-stream"

/** Extension of a backup file, without the dot. The one place it is defined. */
internal const val BACKUP_FILE_EXTENSION = "lucabkup"

/** The name suggested for a new backup: `tumbler-backup-YYYY-MM-DD.lucabkup`. */
internal fun suggestedBackupFileName(date: LocalDate): String = "tumbler-backup-$date.$BACKUP_FILE_EXTENSION"

/**
 * A file the user picked through the Storage Access Framework. A seam so tests can use
 * streams that fail. Opening and deleting may block (the provider can be remote), so call
 * them off the main thread.
 */
internal interface BackupFile {

    /**
     * Opens the file for writing, truncating it: whatever it held is gone from here on. An
     * export calls it only through [OpenOnFirstWriteOutput], once the encrypted backup is
     * complete in memory.
     */
    fun openOutput(): OutputStream

    fun openInput(): InputStream

    /**
     * Best effort: deletes the file, and if the provider refuses, truncates it to zero bytes
     * so no partial backup is left. Returns true only if the file was deleted. Never throws.
     */
    fun delete(): Boolean

    /**
     * True only if the file is known to hold no bytes (as one the picker has just created);
     * false if it holds bytes or that cannot be found out. Reads at most one byte, writes
     * nothing. Never throws.
     */
    fun isKnownEmpty(): Boolean
}

/**
 * The output an export writes to: [file] is opened (which truncates it) only when the first
 * byte is written. [io.github.lucateam_wt.tumbler.backup.BackupService.exportAccounts]
 * encrypts the whole backup in memory and then writes it with one call, so the file is not
 * opened, truncated or written by any failure before that point, and a file the picker
 * returned with content keeps it. The open runs on the thread that writes (the service's
 * dispatcher).
 *
 * The open is refused once [caller] (the export's job) is no longer active: scrypt blocks
 * and does not stop when the caller is cancelled, so the service may still write after the
 * cancellation. The write then throws the job's `CancellationException` without opening the
 * file, which the service does not catch, and [isOpen] and [openFailed] stay false. A
 * cancellation that lands after this check, while the file is being opened or written, is
 * not refused: the file may be truncated and is then deleted like any other partial file.
 *
 * If the open fails ([IOException], [SecurityException], [IllegalArgumentException] or
 * [UnsupportedOperationException], which providers throw), [openFailed] is set and the write
 * throws an [IOException], which the service reports as a failed write. [flush] and [close]
 * do nothing until the file is open.
 */
internal class OpenOnFirstWriteOutput(private val file: BackupFile, private val caller: Job) : OutputStream() {

    @Volatile
    private var stream: OutputStream? = null

    /** True once the file was opened, so it may have been truncated and partly written. */
    val isOpen: Boolean get() = stream != null

    /** True if opening the file failed: nothing was written. */
    @Volatile
    var openFailed: Boolean = false
        private set

    private fun opened(): OutputStream {
        stream?.let { return it }
        if (openFailed) throw FileNotOpenedException()
        // Never open (and so truncate) the file for a caller that was cancelled meanwhile.
        caller.ensureActive()
        val opened = try {
            file.openOutput()
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: UnsupportedOperationException) {
            null
        }
        if (opened == null) {
            openFailed = true
            throw FileNotOpenedException()
        }
        stream = opened
        return opened
    }

    override fun write(b: Int) = opened().write(b)

    override fun write(b: ByteArray, off: Int, len: Int) = opened().write(b, off, len)

    override fun flush() {
        stream?.flush()
    }

    override fun close() {
        stream?.close()
    }

    /** No message and no cause: a provider's message could name the document. */
    private class FileNotOpenedException : IOException()
}

/** A [BackupFile] behind a document [uri] from `CreateDocument` or `OpenDocument`. [toString] hides the uri. */
internal class DocumentBackupFile(private val resolver: ContentResolver, private val uri: Uri) : BackupFile {

    // "wt": some providers do not truncate on "w", which could leave old bytes after the backup.
    // SAF has no atomic replace, so from here on the file's earlier content is gone.
    override fun openOutput(): OutputStream =
        resolver.openOutputStream(uri, "wt") ?: throw FileNotFoundException("The document cannot be opened")

    override fun openInput(): InputStream =
        resolver.openInputStream(uri) ?: throw FileNotFoundException("The document cannot be opened")

    // Providers throw many kinds of exceptions here (FileNotFound, Security, UnsupportedOperation,
    // IllegalArgument...); none may stop the cleanup of a failed export.
    override fun delete(): Boolean {
        val deleted = try {
            DocumentsContract.deleteDocument(resolver, uri)
        } catch (e: Exception) {
            false
        }
        if (deleted) return true
        try {
            resolver.openOutputStream(uri, "wt")?.close()
        } catch (e: Exception) {
            // Nothing more can be done; the message tells the user to delete the file.
        }
        return false
    }

    override fun isKnownEmpty(): Boolean =
        try {
            resolver.openInputStream(uri)?.use { it.read() == -1 } ?: false
        } catch (e: Exception) {
            false
        }

    override fun toString(): String = "DocumentBackupFile(<redacted>)"
}
