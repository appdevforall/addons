package org.appdevforall.customidebuilder.core

import java.io.File

enum class BuildStage(val label: String) {
    CHECKING("Checking"),
    REWRITING("Copying the app and the add-ons"),
    SIGNING("Signing"),
    VERIFYING("Checking the signature"),
}

class BuildException(message: String, cause: Throwable? = null) : Exception(message, cause)

class BuildInput(
    val hostApk: File,
    /** `ApplicationInfo.splitSourceDirs`; any entry means the host is a split install. */
    val hostSplitDirs: List<String>,
    val addons: List<StagedAddon>,
    val key: SigningKey,
    val minSdk: Int,
    /** Where the intermediate and final files go; created if missing. */
    val workDir: File,
    /** The final file's name, inside [workDir]. */
    val outputName: String,
    /** The copy's own name and icon. The screen always gives one; null (add-ons only, the host's own name and icon) is for tests of the rewrite and signing alone. */
    val branding: Branding? = null,
)

class BuildOutput(val apk: File, val rewrite: RewriteReport, val verify: VerifyReport)

object Pipeline {
    /** Room kept beyond the files themselves: signing writes a second copy and apksig buffers. */
    private const val MARGIN = 64L * 1024 * 1024

    /** Bytes the build needs in [BuildInput.workDir]: the rewritten APK and the signed one, plus margin. */
    fun spaceNeeded(apkSize: Long, addonBytes: Long): Long = 2 * (apkSize + addonBytes) + MARGIN

    fun run(
        input: BuildInput,
        checkCancelled: () -> Unit = {},
        onProgress: (BuildStage, Double) -> Unit = { _, _ -> },
    ): BuildOutput {
        onProgress(BuildStage.CHECKING, 0.0)
        if (input.hostSplitDirs.isNotEmpty()) {
            throw BuildException(
                "This copy of Code on the Go was installed as split APKs, so there is no single APK to copy. " +
                    "Install the universal APK of Code on the Go and try again.",
            )
        }
        if (!input.hostApk.isFile) throw BuildException("The installed app's APK (${input.hostApk.path}) cannot be read.")
        if (input.hostApk.name.endsWith(".cgp", ignoreCase = true)) {
            throw BuildException("That is a plugin file, not the Code on the Go APK.")
        }
        input.workDir.mkdirs()
        val addonBytes = input.addons.sumOf { it.file.length() }
        val needed = spaceNeeded(input.hostApk.length(), addonBytes)
        if (input.workDir.usableSpace < needed) {
            throw BuildException(
                "Not enough free space: the build needs about ${needed / (1024 * 1024)} MB and " +
                    "${input.workDir.usableSpace / (1024 * 1024)} MB is free.",
            )
        }

        val unsigned = File(input.workDir, "unsigned.apk.tmp")
        val signed = File(input.workDir, input.outputName)
        try {
            val report =
                try {
                    ApkRewriter.rewrite(input.hostApk, unsigned, input.addons, input.branding, checkCancelled) { onProgress(BuildStage.REWRITING, it) }
                } catch (e: BrandingException) {
                    throw BuildException("Changing the name or icon failed: ${e.message}", e)
                } catch (e: java.io.IOException) {
                    throw BuildException("Copying the app failed: ${e.message}", e)
                }
            checkCancelled()
            onProgress(BuildStage.SIGNING, 0.0)
            try {
                Signing.sign(unsigned, signed, input.key, input.minSdk)
            } catch (e: SigningException) {
                throw BuildException(e.message ?: "Signing failed", e)
            }
            unsigned.delete()
            checkCancelled()
            onProgress(BuildStage.VERIFYING, 0.0)
            val verify = Signing.verify(signed, input.minSdk)
            if (!verify.verified) {
                throw BuildException("The signed APK did not verify: ${verify.errors.joinToString("; ").ifEmpty { "unknown reason" }}")
            }
            if (!verify.v1 || !verify.v2 || !verify.v3) {
                throw BuildException("The signed APK is missing a signature scheme (v1=${verify.v1}, v2=${verify.v2}, v3=${verify.v3}).")
            }
            val misaligned = ZipAlignment.misaligned(signed)
            if (misaligned.isNotEmpty()) {
                throw BuildException("The signed APK has uncompressed files that are not aligned, such as ${misaligned.first()}; Android may refuse to install it.")
            }
            return BuildOutput(signed, report, verify)
        } catch (t: Throwable) {
            signed.delete()
            throw t
        } finally {
            unsigned.delete()
        }
    }
}
