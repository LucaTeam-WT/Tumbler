package io.github.lucateam_wt.tumbler.ui.backup

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.FileNotFoundException
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

    /** Opens the file for writing, truncating it. */
    fun openOutput(): OutputStream

    fun openInput(): InputStream

    /**
     * Best effort: deletes the file, and if the provider refuses, truncates it to zero bytes
     * so no partial backup is left. Returns true only if the file was deleted. Never throws.
     */
    fun delete(): Boolean
}

/** A [BackupFile] behind a document [uri] from `CreateDocument` or `OpenDocument`. [toString] hides the uri. */
internal class DocumentBackupFile(private val resolver: ContentResolver, private val uri: Uri) : BackupFile {

    // "wt": some providers do not truncate on "w", which could leave old bytes after the backup.
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

    override fun toString(): String = "DocumentBackupFile(<redacted>)"
}
