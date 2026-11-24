package org.appdevforall.customidebuilder.core

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import java.io.File
import java.security.MessageDigest

class SigningException(message: String, cause: Throwable? = null) : Exception(message, cause)

class VerifyReport(
    val verified: Boolean,
    val v1: Boolean,
    val v2: Boolean,
    val v3: Boolean,
    val signerSha256: String?,
    val errors: List<String>,
)

/** Signs and checks an APK with apksig: v1, v2 and v3 together. */
object Signing {
    /** The newest platform whose verification still checks v1: Android 6.0 (API 23). */
    private const val V1_CHECKED_PLATFORM = 23

    /**
     * Signs [input] into [output] with [key]. [minSdk] is the lowest Android version the APK
     * runs on; it is given rather than read from the APK so apksig never has to parse the
     * manifest, and it decides which schemes are needed (v1 is forced on here regardless).
     * The old v1 signature files were already dropped by [ApkRewriter]; apksig strips any
     * signing block itself.
     */
    fun sign(input: File, output: File, key: SigningKey, minSdk: Int) {
        try {
            val signer = ApkSigner.SignerConfig.Builder("CERT", key.privateKey, key.chain).build()
            ApkSigner.Builder(listOf(signer))
                .setInputApk(input)
                .setOutputApk(output)
                .setMinSdkVersion(minSdk)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .build()
                .sign()
        } catch (e: Exception) {
            throw SigningException("Signing failed: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    /** Verifies every scheme in [apk]; used on the finished file before it is handed to the user. */
    fun verify(apk: File, minSdk: Int): VerifyReport {
        // apksig checks the v1 (JAR) signature only for platforms below 24 when a v2/v3 block is
        // present, so a range starting at minSdk >= 24 always reports v1 unverified. Start at 23 at
        // the latest so the v1 signature this signer wrote is checked too.
        val result = ApkVerifier.Builder(apk).setMinCheckedPlatformVersion(minOf(minSdk, V1_CHECKED_PLATFORM)).build().verify()
        val signer = result.signerCertificates.firstOrNull()
        return VerifyReport(
            verified = result.isVerified,
            v1 = result.isVerifiedUsingV1Scheme,
            v2 = result.isVerifiedUsingV2Scheme,
            v3 = result.isVerifiedUsingV3Scheme,
            signerSha256 = signer?.let { MessageDigest.getInstance("SHA-256").digest(it.encoded).joinToString(":") { b -> "%02X".format(b) } },
            errors = result.errors.map { it.toString() },
        )
    }
}
