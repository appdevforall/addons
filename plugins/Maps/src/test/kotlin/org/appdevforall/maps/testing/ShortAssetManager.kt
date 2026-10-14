package org.appdevforall.maps.testing

import android.content.res.AssetFileDescriptor
import android.content.res.AssetManager
import java.io.File
import java.lang.invoke.MethodHandles
import java.nio.file.Files
import javax.tools.ToolProvider

/**
 * An [AssetManager] whose `openFd` claims a length the file behind it does not have, and whose
 * stream's `skip` always returns 0. Drives the template server's failed-skip path on a plain JVM.
 */
object ShortAssetManager {

    private val SOURCE = """
        package android.content.res;

        class MapsShortStream extends java.io.FileInputStream {
            MapsShortStream(java.io.File f) throws java.io.FileNotFoundException { super(f); }
            @Override public long skip(long n) { return 0; }
        }

        class MapsShortFd extends AssetFileDescriptor {
            private final java.io.File file;
            private final long claimed;
            MapsShortFd(java.io.File file, long claimed) { super(null, 0, claimed); this.file = file; this.claimed = claimed; }
            @Override public long getLength() { return claimed; }
            @Override public java.io.FileInputStream createInputStream() throws java.io.IOException { return new MapsShortStream(file); }
            @Override public void close() {}
        }

        public class MapsShortAssetManager extends AssetManager {
            private final java.io.File file;
            private final long claimed;
            public MapsShortAssetManager(java.io.File file, long claimed) { this.file = file; this.claimed = claimed; }
            @Override public AssetFileDescriptor openFd(String path) { return new MapsShortFd(file, claimed); }
        }
    """.trimIndent()

    private val managerClass: Class<*> by lazy { compileAndDefine() }

    /** An [AssetManager] serving [file] from every `openFd`, reporting [claimedLength] bytes. */
    fun create(file: File, claimedLength: Long): AssetManager =
        managerClass.getConstructor(File::class.java, Long::class.javaPrimitiveType)
            .newInstance(file, claimedLength) as AssetManager

    private fun compileAndDefine(): Class<*> {
        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler()) { "needs a JDK compiler" }
        val androidJar = File(AssetManager::class.java.protectionDomain.codeSource.location.toURI())
        val work = Files.createTempDirectory("maps-short-assets").toFile()
        try {
            val src = File(work, "android/content/res/MapsShortAssetManager.java")
            src.parentFile.mkdirs()
            src.writeText(SOURCE)
            val exit = compiler.run(null, null, null, "-classpath", androidJar.absolutePath, "-d", work.absolutePath, src.absolutePath)
            check(exit == 0) { "javac failed ($exit) compiling MapsShortAssetManager" }
            // Same reflective lookup as TestAssetManagers: the classes must live in android.content.res.
            val lookup = MethodHandles::class.java
                .getMethod("privateLookupIn", Class::class.java, MethodHandles.Lookup::class.java)
                .invoke(null, AssetFileDescriptor::class.java, MethodHandles.lookup())
            val define = MethodHandles.Lookup::class.java.getMethod("defineClass", ByteArray::class.java)
            lateinit var manager: Class<*>
            for (name in listOf("MapsShortStream", "MapsShortFd", "MapsShortAssetManager")) {
                val c = define.invoke(lookup, File(work, "android/content/res/$name.class").readBytes()) as Class<*>
                if (name == "MapsShortAssetManager") manager = c
            }
            return manager
        } finally {
            work.deleteRecursively()
        }
    }
}
