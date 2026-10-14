package org.appdevforall.maps.testing

import android.content.res.AssetManager
import java.io.File
import java.io.InputStream
import java.lang.invoke.MethodHandles
import java.nio.file.Files
import java.util.function.Function
import javax.tools.ToolProvider

/**
 * Builds [AssetManager] instances whose `open(path)` is served by a Kotlin function, so code
 * that reads plugin assets through `Context.getAssets()` can run on a plain JVM.
 *
 * `AssetManager` is final in the compile-time android.jar but not in the mockable runtime
 * jar, so the subclass is compiled at test runtime against the runtime jar and defined in
 * `android.content.res` (its constructor is package-private).
 */
object TestAssetManagers {

    private const val CLASS_NAME = "android.content.res.MapsTestAssetManager"

    private val SOURCE = """
        package android.content.res;

        public class MapsTestAssetManager extends AssetManager {
            private final java.util.function.Function<String, java.io.InputStream> opener;

            public MapsTestAssetManager(java.util.function.Function<String, java.io.InputStream> opener) {
                this.opener = opener;
            }

            @Override
            public java.io.InputStream open(String path) throws java.io.IOException {
                java.io.InputStream in = opener.apply(path);
                if (in == null) throw new java.io.FileNotFoundException(path);
                return in;
            }
        }
    """.trimIndent()

    private val generatedClass: Class<*> by lazy { compileAndDefine() }

    /** An [AssetManager] whose `open(path)` returns [opener]'s stream, or throws when it returns null. */
    fun create(opener: (String) -> InputStream?): AssetManager {
        val ctor = generatedClass.getConstructor(Function::class.java)
        return ctor.newInstance(Function<String, InputStream?> { opener(it) }) as AssetManager
    }

    /** An [AssetManager] that serves `open(path)` from files under [root]. */
    fun fromDirectory(root: File): AssetManager = create { path ->
        File(root, path).takeIf { it.isFile }?.inputStream()
    }

    private fun compileAndDefine(): Class<*> {
        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler()) {
            "TestAssetManagers needs a JDK (javax.tools compiler) to run the unit tests"
        }
        val runtimeAndroidJar = File(AssetManager::class.java.protectionDomain.codeSource.location.toURI())
        val work = Files.createTempDirectory("maps-test-assets").toFile()
        try {
            val src = File(work, "android/content/res/MapsTestAssetManager.java")
            src.parentFile.mkdirs()
            src.writeText(SOURCE)
            val exit = compiler.run(
                null, null, null,
                "-classpath", runtimeAndroidJar.absolutePath,
                "-d", work.absolutePath,
                src.absolutePath,
            )
            check(exit == 0) { "javac failed ($exit) compiling $CLASS_NAME" }
            val bytes = File(work, "android/content/res/MapsTestAssetManager.class").readBytes()
            // Called reflectively: the unit-test compile classpath puts android.jar's older
            // java.lang.invoke ahead of the JDK's, and it lacks these two methods.
            val lookup = MethodHandles::class.java
                .getMethod("privateLookupIn", Class::class.java, MethodHandles.Lookup::class.java)
                .invoke(null, AssetManager::class.java, MethodHandles.lookup())
            return MethodHandles.Lookup::class.java
                .getMethod("defineClass", ByteArray::class.java)
                .invoke(lookup, bytes) as Class<*>
        } finally {
            work.deleteRecursively()
        }
    }
}
