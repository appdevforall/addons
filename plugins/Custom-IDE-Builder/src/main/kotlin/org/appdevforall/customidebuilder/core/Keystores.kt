package org.appdevforall.customidebuilder.core

import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.UnrecoverableKeyException
import java.security.cert.X509Certificate

/** What a keystore file's first bytes say it is. */
enum class KeystoreFormat { PKCS12_LIKELY, BKS_LIKELY, JKS, JCEKS, UNKNOWN }

object KeystoreFormats {
    const val CONVERT_JKS =
        "This is a Java KeyStore (JKS) file, which Android cannot read. Convert it on a computer with " +
            "keytool -importkeystore -srckeystore old.jks -srcstoretype JKS -destkeystore new.p12 -deststoretype PKCS12 " +
            "and choose the new .p12 file."

    const val CONVERT_JCEKS =
        "This is a JCEKS keystore, which Android cannot read. Convert it on a computer with " +
            "keytool -importkeystore -srckeystore old.jceks -srcstoretype JCEKS -destkeystore new.p12 -deststoretype PKCS12 " +
            "and choose the new .p12 file."

    /**
     * JKS starts with the magic `FEEDFEED`, JCEKS with `CECECECE`. A PKCS#12 file is a DER
     * SEQUENCE, so it starts with `0x30`. A BouncyCastle (BKS) store starts with its version
     * number as a big-endian int, 1 or 2 in files in use. Anything else is tried as both.
     */
    fun sniff(header: ByteArray): KeystoreFormat {
        if (header.size < 4) return KeystoreFormat.UNKNOWN
        val b = IntArray(4) { header[it].toInt() and 0xFF }
        if (b[0] == 0xFE && b[1] == 0xED && b[2] == 0xFE && b[3] == 0xED) return KeystoreFormat.JKS
        if (b[0] == 0xCE && b[1] == 0xCE && b[2] == 0xCE && b[3] == 0xCE) return KeystoreFormat.JCEKS
        if (b[0] == 0x30) return KeystoreFormat.PKCS12_LIKELY
        if (b[0] == 0 && b[1] == 0 && b[2] == 0 && b[3] in 1..2) return KeystoreFormat.BKS_LIKELY
        return KeystoreFormat.UNKNOWN
    }
}

sealed class KeystoreOpen {
    /** [type] is the `KeyStore` type that read the file. */
    class Opened(val type: String, val store: KeyStore) : KeystoreOpen() {
        /** Aliases that hold a private key, which is what signing needs, in name order. */
        val keyAliases: List<String> get() = store.aliases().toList().filter { store.isKeyEntry(it) }.sorted()
    }

    class Rejected(val message: String) : KeystoreOpen()

    object WrongPassword : KeystoreOpen()
}

class SigningKey(val alias: String, val privateKey: PrivateKey, val chain: List<X509Certificate>) {
    val certificate: X509Certificate get() = chain.first()

    /** SHA-256 of the signing certificate, `AA:BB:..`, to compare between builds. */
    val fingerprint: String
        get() = MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString(":") { "%02X".format(it) }
}

sealed class KeyLoad {
    class Loaded(val key: SigningKey) : KeyLoad()

    class Failed(val message: String) : KeyLoad()
}

object Keystores {
    /** Keystores are a few KB; a file this big is not one. */
    const val MAX_BYTES = 8 * 1024 * 1024

    private val PASSWORD_HINT = Regex("password|integrity|mac invalid|mac verify|corrupt", RegexOption.IGNORE_CASE)

    /**
     * Reads [bytes] as PKCS12, then as BKS (in the other order when the header looks like BKS).
     * Android's own providers read both. A JKS or JCEKS file is rejected with how to convert it.
     * [password] is not modified.
     */
    fun open(bytes: ByteArray, password: CharArray): KeystoreOpen {
        if (bytes.size > MAX_BYTES) return KeystoreOpen.Rejected("This file is too large to be a keystore.")
        val order =
            when (KeystoreFormats.sniff(bytes)) {
                KeystoreFormat.JKS -> return KeystoreOpen.Rejected(KeystoreFormats.CONVERT_JKS)
                KeystoreFormat.JCEKS -> return KeystoreOpen.Rejected(KeystoreFormats.CONVERT_JCEKS)
                KeystoreFormat.BKS_LIKELY -> listOf("BKS", "PKCS12")
                else -> listOf("PKCS12", "BKS")
            }
        var passwordProblem = false
        for (type in order) {
            try {
                val store = KeyStore.getInstance(type)
                store.load(ByteArrayInputStream(bytes), password)
                return KeystoreOpen.Opened(type, store)
            } catch (e: IOException) {
                if (e.chain().any { it is UnrecoverableKeyException } || e.message.orEmpty().contains(PASSWORD_HINT)) passwordProblem = true
            } catch (e: java.security.GeneralSecurityException) {
                // A provider that is not there, or an algorithm it lacks: try the next type.
            }
        }
        return if (passwordProblem) KeystoreOpen.WrongPassword else KeystoreOpen.Rejected("This is not a PKCS12 or BKS keystore, or it is damaged.")
    }

    /**
     * The private key and certificate chain under [alias]. A blank [keyPassword] means the store
     * password, which is how a PKCS12 store is normally made.
     */
    fun loadKey(opened: KeystoreOpen.Opened, alias: String, keyPassword: CharArray, storePassword: CharArray): KeyLoad {
        val password = if (keyPassword.isEmpty()) storePassword else keyPassword
        val key =
            try {
                opened.store.getKey(alias, password)
            } catch (e: UnrecoverableKeyException) {
                return KeyLoad.Failed("The key password for \"$alias\" is wrong.")
            } catch (e: java.security.GeneralSecurityException) {
                return KeyLoad.Failed("The key \"$alias\" could not be read: ${e.message}")
            }
        if (key == null) return KeyLoad.Failed("There is no key named \"$alias\".")
        if (key !is PrivateKey) return KeyLoad.Failed("\"$alias\" is not a private key.")
        val chain = opened.store.getCertificateChain(alias)?.filterIsInstance<X509Certificate>().orEmpty()
        if (chain.isEmpty()) return KeyLoad.Failed("\"$alias\" has no certificate, so it cannot sign.")
        return KeyLoad.Loaded(SigningKey(alias, key, chain))
    }

    private fun Throwable.chain(): Sequence<Throwable> = generateSequence(this) { it.cause }
}
