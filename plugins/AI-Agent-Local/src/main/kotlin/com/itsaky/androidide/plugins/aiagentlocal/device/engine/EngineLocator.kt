package com.itsaky.androidide.plugins.aiagentlocal.device.engine

import dalvik.system.BaseDexClassLoader
import java.io.File

/** Finds the extracted engine programs. */
interface EngineLocator {
    /** The executable file of [program], or null when it cannot be found. */
    fun locate(program: EngineProgram): File?
}

/**
 * Looks a program up with the class loader first (`findLibrary("part1_membw")`), then in the directory the host extracts the
 * plugin's native files to: `<dataDir>/app_plugin_native_libs/<pluginId>/lib<name>.so`.
 *
 * The two lookups are passed in, so JVM tests need no Android classes; [forClassLoader] builds the real one.
 */
class NativeLibEngineLocator(
    private val findLibrary: (String) -> String?,
    private val dataDir: File,
    private val pluginId: String,
    private val isFile: (File) -> Boolean = { it.isFile },
) : EngineLocator {

    override fun locate(program: EngineProgram): File? {
        val viaLoader = try {
            findLibrary(program.libraryName)
        } catch (e: Exception) {
            null
        }
        if (!viaLoader.isNullOrEmpty()) {
            val f = File(viaLoader)
            if (isFile(f)) return f
        }
        val fallback = File(File(File(dataDir, "app_plugin_native_libs"), pluginId), program.fileName)
        return if (isFile(fallback)) fallback else null
    }

    companion object {
        /** `(classLoader as BaseDexClassLoader).findLibrary(name)` with the data-directory fallback. */
        fun forClassLoader(classLoader: ClassLoader, dataDir: File, pluginId: String): NativeLibEngineLocator =
            NativeLibEngineLocator({ name -> (classLoader as? BaseDexClassLoader)?.findLibrary(name) }, dataDir, pluginId)
    }
}
