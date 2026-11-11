package org.appdevforall.shareyourapps

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

/** Reads an APK's version, package name and minimum Android version, with Android's own PackageManager. */
object ApkReader {
    fun read(ctx: Context, apk: File): ApkFacts? = try {
        val pm = ctx.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) pm.getPackageArchiveInfo(apk.path, PackageManager.PackageInfoFlags.of(0))
            else @Suppress("DEPRECATION") pm.getPackageArchiveInfo(apk.path, 0)
        info?.let { ApkFacts(it.versionName, it.longVersionCode, it.packageName, it.applicationInfo?.minSdkVersion) }
    } catch (e: Exception) {
        null   // not a readable APK, such as the demo's placeholders: the page simply leaves those facts out
    }
}
