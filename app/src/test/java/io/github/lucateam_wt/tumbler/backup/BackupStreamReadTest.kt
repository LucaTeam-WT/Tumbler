package io.github.lucateam_wt.tumbler.backup

import io.github.lucateam_wt.tumbler.backup.BackupFixtures.PASSWORD
import io.github.lucateam_wt.tumbler.backup.BackupFixtures.entry
import io.github.lucateam_wt.tumbler.backup.BackupFixtures.seal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream

/** The bounded `InputStream` overload of [readBackup]. */
class BackupStreamReadTest {

    private val oneAccount = listOf(entry())
    private val file = seal(oneAccount)
    private val max = BackupFormat.MAX_FILE_BYTES

    /** KDF that must never run. */
    private val noKdf = Kdf { _, _, _, _ -> throw AssertionError("scrypt must not run") }

    /**
     * Serves [length] bytes ([content] first, then [filler]), at most [chunk] per read, then
     * end of stream. Counts what it served, remembers every buffer it was handed, and can
     * throw after [failAfter] bytes.
     */
    private class TestStream(
        private val content: ByteArray,
        private val length: Long = content.size.toLong(),
        private val filler: Byte = 0,
        private val chunk: Int = Int.MAX_VALUE,
        private val failAfter: Long = Long.MAX_VALUE,
        private val zeroReads: Boolean = false,
    ) : InputStream() {
        var served = 0L
            private set
        var closed = false
            private set
        val buffers = mutableListOf<ByteArray>()

        private fun byteAt(position: Long): Byte = if (position < content.size) content[position.toInt()] else filler

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (buffers.none { it === b }) buffers += b
            if (len == 0) return 0
            if (served >= failAfter) throw IOException("simulated read failure")
            if (zeroReads) return 0
            if (served >= length) return -1
            val n = minOf(len.toLong(), chunk.toLong(), length - served, failAfter - served).toInt()
            for (i in 0 until n) b[off + i] = byteAt(served + i)
            served += n
            return n
        }

        override fun close() {
            closed = true
        }
    }

    private fun read(stream: InputStream, kdf: Kdf = BouncyCastleScrypt) =
        readBackup(stream, PASSWORD.toCharArray(), kdf)

    @Test
    fun validFile_isRead() {
        val stream = TestStream(file)
        val result = read(stream)
        assertTrue("$result", result is BackupReadResult.Success)
        assertEquals(oneAccount, (result as BackupReadResult.Success).accounts)
        assertEquals(file.size.toLong(), stream.served)
        assertFalse(stream.closed)
    }

    @Test
    fun validFile_isReadInTinyChunks() {
        for (chunk in listOf(1, 7, 4096)) {
            val result = read(TestStream(file, chunk = chunk))
            assertEquals("chunk $chunk", oneAccount, (result as BackupReadResult.Success).accounts)
        }
    }

    @Test
    fun endlessStream_isTooLargeAfterAtMostTheLimitPlusOneByte() {
        val stream = TestStream(file, length = Long.MAX_VALUE, filler = 0x41)
        assertSame(BackupReadResult.TooLarge, read(stream, kdf = noKdf))
        assertEquals(max + 1L, stream.served)
        assertFalse(stream.closed)
    }

    @Test
    fun limitPlusOne_isTooLarge() {
        val stream = TestStream(file, length = max + 1L)
        assertSame(BackupReadResult.TooLarge, read(stream, kdf = noKdf))
        assertEquals(max + 1L, stream.served)
    }

    @Test
    fun exactLimit_isAcceptedAndReachesTheReader() {
        // A valid file padded with zeros to exactly 4 MiB: not TooLarge; the padding then
        // fails authentication, which proves the bytes reached scrypt and GCM.
        val stream = TestStream(file, length = max.toLong())
        val kdf = CountingKdf()
        assertSame(BackupReadResult.WrongPasswordOrDamaged, read(stream, kdf = kdf))
        assertEquals(1, kdf.calls)
        assertEquals(max.toLong(), stream.served)
    }

    @Test
    fun emptyAndShortStreams_areReadLikeArrays() {
        assertSame(BackupReadResult.NotABackup, read(TestStream(ByteArray(0)), kdf = noKdf))
        assertSame(BackupReadResult.WrongPasswordOrDamaged, read(TestStream(file.copyOf(40)), kdf = noKdf))
    }

    @Test
    fun ioException_isReadFailedAndNothingIsParsed() {
        for (failAfter in listOf(0L, 1L, 58L, file.size - 1L, 100_000L)) {
            val stream = TestStream(file, length = 200_000L, filler = 0x5A, failAfter = failAfter)
            assertSame("fail after $failAfter", BackupReadResult.ReadFailed, read(stream, kdf = noKdf))
            assertFalse(stream.closed)
        }
    }

    @Test
    fun ioException_leavesNoPartialDataInTheBuffers() {
        // 0x5A filler, so any byte that survived would be non-zero.
        val stream = TestStream(ByteArray(0), length = 300_000L, filler = 0x5A, failAfter = 250_000L)
        assertSame(BackupReadResult.ReadFailed, read(stream, kdf = noKdf))
        assertTrue(stream.buffers.size > 1) // the reader grew its buffer before the failure
        for (buffer in stream.buffers) assertTrue(buffer.all { it == 0.toByte() })
    }

    @Test
    fun tooLargeAndSuccess_alsoLeaveTheBuffersZeroed() {
        val endless = TestStream(ByteArray(0), length = Long.MAX_VALUE, filler = 0x5A)
        assertSame(BackupReadResult.TooLarge, read(endless, kdf = noKdf))
        for (buffer in endless.buffers) assertTrue(buffer.all { it == 0.toByte() })

        val valid = TestStream(file)
        assertTrue(read(valid) is BackupReadResult.Success)
        for (buffer in valid.buffers) assertTrue(buffer.all { it == 0.toByte() })
    }

    @Test
    fun completeFile_isZeroedAfterParsing() {
        val seen = mutableListOf<ByteArray>()
        val result = readBounded(TestStream(file)) { bytes ->
            seen += bytes
            assertTrue(bytes.contentEquals(file)) // the parser sees the whole file
            BackupReadResult.NotABackup
        }
        assertSame(BackupReadResult.NotABackup, result)
        assertTrue(seen.single().all { it == 0.toByte() })

        // Also when the parser throws.
        val thrown = mutableListOf<ByteArray>()
        runCatching { readBounded(TestStream(file)) { bytes -> thrown += bytes; throw IllegalStateException("parser") } }
        assertTrue(thrown.single().all { it == 0.toByte() })
    }

    @Test
    fun streamReturningZeroBytes_isReadFailedInsteadOfLooping() {
        assertSame(BackupReadResult.ReadFailed, read(TestStream(file, zeroReads = true), kdf = noKdf))
    }

    @Test
    fun streamIsNeverClosed() {
        val streams = listOf(
            TestStream(file),
            TestStream(file, length = Long.MAX_VALUE),
            TestStream(file, failAfter = 10),
        )
        for (stream in streams) {
            read(stream)
            assertFalse(stream.closed)
        }
    }
}
