package io.github.lucateam_wt.tumbler.backup

import io.github.lucateam_wt.tumbler.domain.model.OtpAccount
import io.github.lucateam_wt.tumbler.domain.model.OtpType
import io.github.lucateam_wt.tumbler.domain.otp.Base32
import io.github.lucateam_wt.tumbler.domain.otp.OtpAlgorithm
import org.bouncycastle.crypto.generators.SCrypt
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Shared test data. Only the two public test secrets below are ever used. */
internal object BackupFixtures {
    const val SECRET_A_BASE32 = "JBSWY3DPEHPK3PXP"
    const val SECRET_B_BASE32 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
    const val PASSWORD = "correct horse battery staple"

    /** Smallest parameters the reader accepts (2 MiB), to keep tests fast. */
    val FAST = ScryptParams(log2N = 14, r = 1, p = 1)

    fun secretA(): ByteArray = Base32.decode(SECRET_A_BASE32)
    fun secretB(): ByteArray = Base32.decode(SECRET_B_BASE32)

    /** [size] bytes made by repeating the public secret B. */
    fun longSecret(size: Int): ByteArray {
        val base = secretB()
        return ByteArray(size) { base[it % base.size] }
    }

    /** 0x00, 0x01, ... (fixed, so test files are reproducible). */
    fun salt(size: Int = BackupFormat.SALT_LENGTH) = ByteArray(size) { it.toByte() }

    /** 0xa0, 0xa1, ..., 0xab. */
    fun nonce() = ByteArray(BackupFormat.NONCE_LENGTH) { (0xA0 + it).toByte() }

    fun entry(
        issuer: String = "ACME",
        accountName: String = "alice@example.com",
        type: OtpType = OtpType.TOTP,
        secret: ByteArray = secretA(),
        algorithm: OtpAlgorithm = OtpAlgorithm.SHA1,
        digits: Int = 6,
        period: Int = 30,
        counter: Long = 0,
        createdAtMillis: Long = 1_700_000_000_000,
    ) = BackupEntry(
        OtpAccount(issuer, accountName, type, secret, algorithm, digits, period, counter),
        createdAtMillis,
    )

    fun seal(
        accounts: List<BackupEntry>,
        password: String = PASSWORD,
        params: ScryptParams = FAST,
        salt: ByteArray = salt(),
        nonce: ByteArray = nonce(),
        kdf: Kdf = BouncyCastleScrypt,
    ): ByteArray = sealBackup(accounts, password.toCharArray(), salt, nonce, params, kdf)

    fun read(bytes: ByteArray, password: String = PASSWORD, kdf: Kdf = BouncyCastleScrypt) =
        readBackup(bytes, password.toCharArray(), kdf)

    fun Byte.flip(mask: Int): Byte = (toInt() xor mask).toByte()

    fun ByteArray.withByte(index: Int, value: Int): ByteArray = copyOf().also { it[index] = value.toByte() }
}

/** [Kdf] that counts derivations and delegates (to scrypt by default). */
internal class CountingKdf(private val delegate: Kdf = BouncyCastleScrypt) : Kdf {
    var calls = 0
        private set

    override fun derive(password: ByteArray, salt: ByteArray, params: ScryptParams, keyLength: Int): ByteArray {
        calls++
        return delegate.derive(password, salt, params, keyLength)
    }
}

/**
 * A second, independent implementation of `docs/backup-format.md` that shares no code
 * with the production codec: Bouncy Castle's SCrypt, JCA AES-GCM and Data streams only.
 * Used to check the spec against the code and to write files the production writer
 * refuses to write (invalid payloads, odd salt lengths).
 */
internal object SpecCodec {

    class Header(val log2N: Int, val r: Int, val p: Int, val salt: ByteArray, val nonce: ByteArray, val length: Int)

    fun header(file: ByteArray): Header {
        require(String(file, 0, 8, Charsets.US_ASCII) == "LUCABKUP")
        require(file[8].toInt() == 1 && file[9].toInt() == 1)
        val saltLength = file[13].toInt() and 0xFF
        return Header(
            log2N = file[10].toInt() and 0xFF,
            r = file[11].toInt() and 0xFF,
            p = file[12].toInt() and 0xFF,
            salt = file.copyOfRange(14, 14 + saltLength),
            nonce = file.copyOfRange(14 + saltLength, 26 + saltLength),
            length = 26 + saltLength,
        )
    }

    fun encrypt(
        payload: ByteArray,
        passwordUtf8: ByteArray,
        salt: ByteArray,
        nonce: ByteArray,
        log2N: Int = 14,
        r: Int = 1,
        p: Int = 1,
    ): ByteArray {
        val header = ByteArrayOutputStream().apply {
            write("LUCABKUP".toByteArray(Charsets.US_ASCII))
            write(byteArrayOf(1, 1, log2N.toByte(), r.toByte(), p.toByte(), salt.size.toByte()))
            write(salt)
            write(nonce)
        }.toByteArray()
        val key = SCrypt.generate(passwordUtf8, salt, 1 shl log2N, r, p, 32)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(header)
        return header + cipher.doFinal(payload)
    }

    /** Decrypts [file]; [authenticateHeader] = false leaves the associated data out. */
    fun decrypt(file: ByteArray, passwordUtf8: ByteArray, authenticateHeader: Boolean = true): ByteArray {
        val h = header(file)
        val key = SCrypt.generate(passwordUtf8, h.salt, 1 shl h.log2N, h.r, h.p, 32)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, h.nonce))
        if (authenticateHeader) cipher.updateAAD(file, 0, h.length)
        return cipher.doFinal(file, h.length, file.size - h.length)
    }

    /** Payload v1 decoded by the spec, without any validation. */
    class Record(
        val type: Int,
        val algorithm: Int,
        val digits: Int,
        val period: Int,
        val counter: Long,
        val createdAt: Long,
        val issuer: String,
        val accountName: String,
        val secret: ByteArray,
    )

    fun decodePayload(plaintext: ByteArray): List<Record> {
        val input = DataInputStream(ByteArrayInputStream(plaintext))
        require(input.readUnsignedByte() == 1)
        val records = List(input.readInt()) {
            Record(
                type = input.readUnsignedByte(),
                algorithm = input.readUnsignedByte(),
                digits = input.readUnsignedByte(),
                period = input.readInt(),
                counter = input.readLong(),
                createdAt = input.readLong(),
                issuer = String(ByteArray(input.readUnsignedShort()).also(input::readFully), Charsets.UTF_8),
                accountName = String(ByteArray(input.readUnsignedShort()).also(input::readFully), Charsets.UTF_8),
                secret = ByteArray(input.readUnsignedShort()).also(input::readFully),
            )
        }
        require(input.available() == 0)
        return records
    }

    /** Builds payload bytes field by field, with no validation, so tests can break any field. */
    class PayloadBuilder(version: Int = 1, count: Int) {
        private val bytes = ByteArrayOutputStream()
        private val out = DataOutputStream(bytes).apply {
            writeByte(version)
            writeInt(count)
        }

        fun record(
            type: Int = 1,
            algorithm: Int = 1,
            digits: Int = 6,
            period: Int = 30,
            counter: Long = 0,
            createdAt: Long = 0,
            issuer: ByteArray = "ACME".toByteArray(),
            accountName: ByteArray = "alice".toByteArray(),
            secret: ByteArray = BackupFixtures.secretA(),
            issuerLength: Int = issuer.size,
            accountNameLength: Int = accountName.size,
            secretLength: Int = secret.size,
        ) = apply {
            out.writeByte(type)
            out.writeByte(algorithm)
            out.writeByte(digits)
            out.writeInt(period)
            out.writeLong(counter)
            out.writeLong(createdAt)
            out.writeShort(issuerLength)
            out.write(issuer)
            out.writeShort(accountNameLength)
            out.write(accountName)
            out.writeShort(secretLength)
            out.write(secret)
        }

        fun raw(extra: ByteArray) = apply { out.write(extra) }

        fun build(): ByteArray = bytes.toByteArray()
    }
}
