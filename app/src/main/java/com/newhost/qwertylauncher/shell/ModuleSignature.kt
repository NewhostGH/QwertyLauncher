package com.newhost.qwertylauncher.shell

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.cert.Certificate
import java.util.jar.JarFile

/**
 * Who signed a module zip. Modules are signed the way jars are (the
 * `signModule` Gradle task runs the JDK's jarsigner), which Android checks
 * itself; a key is known by its certificate's SHA-256 [fingerprint].
 */
internal object ModuleSignature {

    /**
     * The fingerprint of the key that signed every file in [zip], or null if
     * none is signed. Throws [IOException] if it's signed but a file was
     * changed afterwards, some files aren't signed, or two keys signed it.
     * Not on the main thread: it reads the whole zip.
     */
    fun signer(zip: File): String? {
        val signers = mutableSetOf<String?>()
        try {
            JarFile(zip, true).use { jar ->
                for (entry in jar.entries()) {
                    if (entry.isDirectory || entry.name.startsWith("META-INF/")) continue
                    // Certificates are known, and checked against the signature, only once it's all read.
                    jar.getInputStream(entry).use { input -> input.copyTo(NullOutput) }
                    signers += entry.certificates?.firstOrNull()?.let(::fingerprint)
                }
            }
        } catch (e: SecurityException) {
            throw IOException("a file in it was changed after it was signed", e)
        }
        return when {
            signers.isEmpty() || signers == setOf(null) -> null
            null in signers -> throw IOException("only some of its files are signed")
            signers.size > 1 -> throw IOException("it's signed with more than one key")
            else -> signers.single()
        }
    }

    /** A certificate's SHA-256, as hex. */
    fun fingerprint(certificate: Certificate): String = fingerprint(certificate.encoded)

    fun fingerprint(encoded: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") { "%02X".format(it) }

    /** The start of a fingerprint, in groups of four, short enough for one line: "1A2B 3C4D 5E6F 7A8B". */
    fun short(fingerprint: String): String = fingerprint.take(16).chunked(4).joinToString(" ")

    /** Discards what's written: reading an entry is what verifies it. */
    private object NullOutput : java.io.OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }
}
