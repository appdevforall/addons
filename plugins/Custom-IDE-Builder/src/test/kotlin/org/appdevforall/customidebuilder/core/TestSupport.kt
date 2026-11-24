package org.appdevforall.customidebuilder.core

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date
import java.util.Random
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object TestSupport {
    init {
        if (Security.getProvider("BC") == null) Security.addProvider(BouncyCastleProvider())
    }

    class Material(val key: java.security.PrivateKey, val cert: X509Certificate)

    fun material(commonName: String = "Test"): Material {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name = X500Name("CN=$commonName")
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(name, BigInteger.valueOf(now), Date(now - 86_400_000), Date(now + 86_400_000L * 365), name, pair.public)
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(pair.private)
        return Material(pair.private, JcaX509CertificateConverter().getCertificate(builder.build(signer)))
    }

    fun signingKey(commonName: String = "Test"): SigningKey = material(commonName).let { SigningKey("key", it.key, listOf(it.cert)) }

    fun keystoreBytes(type: String, m: Material, storePassword: String, keyPassword: String = storePassword, alias: String = "key"): ByteArray {
        val store = if (type == "BKS") KeyStore.getInstance("BKS", "BC") else KeyStore.getInstance(type)
        store.load(null, null)
        store.setKeyEntry(alias, m.key, keyPassword.toCharArray(), arrayOf(m.cert))
        return ByteArrayOutputStream().also { store.store(it, storePassword.toCharArray()) }.toByteArray()
    }

    /** Deterministic incompressible-ish and compressible content. */
    fun noise(size: Int, seed: Long = 1): ByteArray = ByteArray(size).also { Random(seed).nextBytes(it) }

    fun text(size: Int): ByteArray = "the quick brown fox jumps over the lazy dog\n".repeat(size / 44 + 1).toByteArray().copyOf(size)

    /**
     * An APK-shaped zip made with java.util.zip: deflated entries (which ZipOutputStream writes with data
     * descriptors), stored entries, an old add-on bundle and an old v1 signature.
     */
    fun syntheticApk(file: File, extra: Map<String, Pair<Int, ByteArray>> = emptyMap()): Map<String, ByteArray> {
        val content = linkedMapOf<String, Pair<Int, ByteArray>>(
            "AndroidManifest.xml" to (ZipEntry.DEFLATED to text(3000)),
            "classes.dex" to (ZipEntry.DEFLATED to text(200_000)),
            "resources.arsc" to (ZipEntry.STORED to noise(5001, 2)),
            "lib/arm64-v8a/libnative.so" to (ZipEntry.STORED to noise(10_001, 3)),
            "res/raw/data.bin" to (ZipEntry.STORED to noise(777, 4)),
            "assets/keep.txt" to (ZipEntry.DEFLATED to text(500)),
            "assets/plugins/old.cgp" to (ZipEntry.STORED to noise(100, 5)),
            "assets/templates/old.cgt" to (ZipEntry.DEFLATED to text(100)),
            "assets/bundled-addons.tsv" to (ZipEntry.DEFLATED to "plugins/old.cgp\t100\t${"0".repeat(64)}\n".toByteArray()),
            "META-INF/MANIFEST.MF" to (ZipEntry.DEFLATED to "Manifest-Version: 1.0\n".toByteArray()),
            "META-INF/OLD.SF" to (ZipEntry.DEFLATED to "Signature-Version: 1.0\n".toByteArray()),
            "META-INF/OLD.RSA" to (ZipEntry.DEFLATED to noise(300, 6)),
            "META-INF/services/example.Service" to (ZipEntry.DEFLATED to "x\n".toByteArray()),
        )
        content.putAll(extra)
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, pair) in content) {
                val (method, data) = pair
                val entry = ZipEntry(name)
                entry.time = 1_700_000_000_000L
                if (method == ZipEntry.STORED) {
                    entry.method = ZipEntry.STORED
                    entry.size = data.size.toLong()
                    entry.compressedSize = data.size.toLong()
                    entry.crc = CRC32().apply { update(data) }.value
                }
                zip.putNextEntry(entry)
                zip.write(data)
                zip.closeEntry()
            }
        }
        return content.mapValues { it.value.second }
    }

    fun staged(dir: File, kind: AddonKind, fileName: String, bytes: ByteArray): StagedAddon {
        val f = File(dir, "src-$fileName")
        f.writeBytes(bytes)
        return StagedAddon(kind, fileName, f)
    }

    fun rawBytes(zip: RawZipFile, entry: RawZipEntry): ByteArray =
        ByteArrayOutputStream().also { zip.copyRaw(entry, it) }.toByteArray()

}
