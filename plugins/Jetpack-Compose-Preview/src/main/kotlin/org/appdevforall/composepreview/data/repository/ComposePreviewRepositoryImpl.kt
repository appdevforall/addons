package org.appdevforall.composepreview.data.repository

import android.content.Context
import org.appdevforall.composepreview.compiler.CompileDiagnostic
import org.appdevforall.composepreview.compiler.CompilerDaemon
import org.appdevforall.composepreview.compiler.ComposeClasspathManager
import org.appdevforall.composepreview.compiler.ComposeCompiler
import org.appdevforall.composepreview.compiler.ComposeDexCompiler
import org.appdevforall.composepreview.compiler.DexCache
import org.appdevforall.composepreview.compiler.SharedCompilerDaemon
import org.appdevforall.composepreview.data.source.ProjectContext
import org.appdevforall.composepreview.data.source.ProjectContextSource
import org.appdevforall.composepreview.domain.model.ParsedPreviewSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File

class ComposePreviewRepositoryImpl(
    private val projectContextSource: ProjectContextSource = ProjectContextSource()
) : ComposePreviewRepository {

    private var classpathManager: ComposeClasspathManager? = null
    private var compiler: ComposeCompiler? = null
    private var compilerDaemon: CompilerDaemon? = null
    private var dexCompiler: ComposeDexCompiler? = null
    private var dexCache: DexCache? = null
    private var workDir: File? = null

    private var runtimeDex: File? = null
    private var projectContext: ProjectContext? = null
    private var daemonInitialized = false
    private var cachedClasspath: String? = null

    companion object {
        private val LOG = LoggerFactory.getLogger(ComposePreviewRepositoryImpl::class.java)
    }

    override suspend fun initialize(
        context: Context,
        filePath: String
    ): Result<InitializationResult> = withContext(Dispatchers.IO) {
        runCatching {
            val ctx = projectContextSource.resolveContext(filePath)
            projectContext = ctx

            if (ctx.needsBuild && ctx.modulePath != null) {
                LOG.warn("No intermediate classes found - build required before initialization")
                return@runCatching InitializationResult.NeedsBuild(ctx.modulePath, ctx.variantName)
            }

            val cpManager = initializeInfrastructure(context)

            if (!cpManager.ensureComposeJarsExtracted()) {
                return@runCatching InitializationResult.Failed(
                    "Failed to initialize Compose dependencies"
                )
            }

            runtimeDex = cpManager.getOrCreateRuntimeDex()
            if (runtimeDex == null) {
                LOG.error("Failed to create Compose runtime DEX")
                return@runCatching InitializationResult.Failed(
                    "Failed to create Compose runtime. Check that Android SDK build-tools are installed."
                )
            }

            LOG.info("Compose runtime DEX ready: {}", runtimeDex?.absolutePath)

            try {
                compilerDaemon?.startEagerly()
                LOG.info("Compiler daemon pre-started")
            } catch (e: Exception) {
                LOG.warn("Failed to pre-start compiler daemon (non-fatal)", e)
            }

            LOG.info("Repository initialized, runtimeDex={}", runtimeDex?.absolutePath ?: "null")
            InitializationResult.Ready(runtimeDex, ctx)
        }
    }

    private fun initializeInfrastructure(context: Context): ComposeClasspathManager {
        val cacheDir = context.cacheDir
        val work = SharedCompilerDaemon.workDirFor(context)
        workDir = work

        dexCache = DexCache(File(cacheDir, "compose_dex_cache"))

        val cpManager = ComposeClasspathManager(context)
        classpathManager = cpManager
        compiler = ComposeCompiler(cpManager, work)
        compilerDaemon = SharedCompilerDaemon.get(cpManager, work)
        dexCompiler = ComposeDexCompiler(cpManager)
        return cpManager
    }

    private fun <T> requireInitialized(value: T?, name: String): T {
        return value ?: throw IllegalStateException("Repository not initialized: $name is null. Call initialize() first.")
    }

    private data class SourceCompileResult(
        val success: Boolean,
        val error: String,
        val diagnostics: List<CompileDiagnostic> = emptyList()
    )

    override suspend fun compilePreview(
        source: String,
        parsedSource: ParsedPreviewSource
    ): Result<CompilationResult> = withContext(Dispatchers.IO) {
        runCatching {
            val cache = requireInitialized(dexCache, "dexCache")
            val compiler = requireInitialized(this@ComposePreviewRepositoryImpl.compiler, "compiler")
            val compilerDaemon = this@ComposePreviewRepositoryImpl.compilerDaemon
            val dexCompiler = requireInitialized(this@ComposePreviewRepositoryImpl.dexCompiler, "dexCompiler")
            val workDir = requireInitialized(this@ComposePreviewRepositoryImpl.workDir, "workDir")
            val classpathManager = requireInitialized(this@ComposePreviewRepositoryImpl.classpathManager, "classpathManager")
            val context = requireInitialized(projectContext, "projectContext")

            val fileName = parsedSource.className?.removeSuffix("Kt") ?: "Preview"
            val generatedClassName = "${fileName}Kt"
            val fullClassName = "${parsedSource.packageName}.$generatedClassName"

            val projectDex = projectClassesDex(context, cache, compilerDaemon, workDir)
            val startedAt = System.currentTimeMillis()
            val projectDexFiles = projectDex.files + context.projectDexFiles
            val sourceHash = cache.computeSourceHash(source + '\u0000' + projectDex.signature)

            val cached = cache.getCachedDex(sourceHash)
            if (cached != null) {
                LOG.info("Using cached DEX for hash: {}, runtimeDex={}, projectDexFiles={}",
                    sourceHash, runtimeDex?.absolutePath ?: "null", projectDexFiles.size)
                return@runCatching CompilationResult(
                    dexFile = cached.dexFile,
                    className = fullClassName,
                    runtimeDex = runtimeDex,
                    projectDexFiles = projectDexFiles
                )
            }

            val sourceDir = File(workDir, "src")
            val packageDir = File(sourceDir, parsedSource.packageName.replace('.', '/'))
            packageDir.mkdirs()

            val sourceFile = File(packageDir, "$fileName.kt")
            sourceFile.writeText(source)

            val classesDir = File(workDir, "classes").apply {
                deleteRecursively()
                mkdirs()
            }

            LOG.debug("Compiling source: {}", sourceFile.absolutePath)
            LOG.info("Using {} project classpaths for compilation", context.compileClasspaths.size)

            val classpath = cachedClasspath
                ?: classpathManager.getCompilationClasspath(context.compileClasspaths).also {
                    cachedClasspath = it
                }

            var compileResult: SourceCompileResult? = null

            if (compilerDaemon != null) {
                val daemonResult = try {
                    compilerDaemon.compile(
                        sourceFiles = listOf(sourceFile),
                        outputDir = classesDir,
                        classpath = classpath,
                        composePlugin = classpathManager.getCompilerPlugin()
                    )
                } catch (e: Exception) {
                    LOG.warn("Daemon compilation failed, falling back to regular compiler", e)
                    null
                }

                if (daemonResult != null) {
                    if (daemonResult.success && !daemonInitialized) {
                        daemonInitialized = true
                        LOG.info("Daemon initialized successfully")
                    }
                    compileResult = SourceCompileResult(
                        success = daemonResult.success,
                        error = daemonResult.errorOutput.ifEmpty { daemonResult.output }
                    )
                }
            }

            if (compileResult == null) {
                val result = compiler.compile(listOf(sourceFile), classesDir, context.compileClasspaths)
                compileResult = SourceCompileResult(
                    success = result.success,
                    error = result.errorOutput.ifEmpty {
                        result.diagnostics
                            .filter { it.severity == CompileDiagnostic.Severity.ERROR }
                            .joinToString("\n") { it.message }
                    },
                    diagnostics = result.diagnostics
                )
            }

            if (!compileResult.success) {
                LOG.error("Compilation failed: {}", compileResult.error)
                throw CompilationException(
                    message = compileResult.error.ifEmpty { "Compilation failed" },
                    diagnostics = compileResult.diagnostics
                )
            }

            val compiledAt = System.currentTimeMillis()
            val dexDir = File(workDir, "dex").apply { mkdirs() }

            LOG.debug("Converting to DEX")

            var dexFile: File? = null

            if (compilerDaemon != null) {
                val daemonDex = try {
                    compilerDaemon.dex(classesDir, dexDir)
                } catch (e: Exception) {
                    LOG.warn("Daemon D8 failed, falling back to subprocess", e)
                    null
                }

                if (daemonDex != null && daemonDex.success && daemonDex.dexFile != null) {
                    dexFile = daemonDex.dexFile
                }
            }

            if (dexFile == null) {
                val dexResult = dexCompiler.compileToDex(classesDir, dexDir)
                if (!dexResult.success || dexResult.dexFile == null) {
                    LOG.error("DEX compilation failed: {}", dexResult.errorMessage)
                    throw CompilationException(
                        message = dexResult.errorMessage.ifEmpty { "DEX compilation failed" }
                    )
                }
                dexFile = dexResult.dexFile
            }

            try {
                cache.cacheDex(
                    sourceHash,
                    dexFile,
                    fullClassName,
                    parsedSource.previewConfigs.firstOrNull()?.functionName ?: ""
                )
            } catch (e: Exception) {
                LOG.warn("Failed to cache DEX file (non-fatal): {}", e.message)
            }

            val finishedAt = System.currentTimeMillis()
            LOG.info("Preview ready: {} with {} previews, {} project DEX files; kotlinc {} ms, d8 {} ms, total {} ms",
                fullClassName, parsedSource.previewConfigs.size, projectDexFiles.size,
                compiledAt - startedAt, finishedAt - compiledAt, finishedAt - startedAt)

            CompilationResult(
                dexFile = dexFile,
                className = fullClassName,
                runtimeDex = runtimeDex,
                projectDexFiles = projectDexFiles
            )
        }
    }

    private data class ProjectDex(val signature: String, val files: List<File>)

    private suspend fun projectClassesDex(
        context: ProjectContext,
        cache: DexCache,
        daemon: CompilerDaemon?,
        workDir: File
    ): ProjectDex {
        val inputs = context.intermediateClasspaths
            .flatMap { root ->
                if (root.isDirectory) root.walkTopDown().filter { it.extension == "class" }.toList() else listOf(root)
            }
            .filter { it.isFile }
            .sortedBy { it.absolutePath }
        val signature = cache.computeSourceHash(
            inputs.joinToString("|") { "${it.absolutePath}:${it.lastModified()}:${it.length()}" }
        )
        if (inputs.isEmpty() || daemon == null) return ProjectDex(signature, emptyList())

        val root = File(workDir, "project-dex")
        val outDir = File(root, signature)
        val done = File(outDir, "done")
        if (!done.exists()) {
            root.deleteRecursively()
            val startedAt = System.currentTimeMillis()
            val result = daemon.dex(inputs, outDir, context.compileClasspaths)
            if (!result.success) {
                LOG.warn("Could not dex the project's compiled classes: {}", result.errorOutput)
                return ProjectDex(signature, emptyList())
            }
            done.createNewFile()
            LOG.info("Dexed {} project class inputs in {} ms", inputs.size, System.currentTimeMillis() - startedAt)
        }
        return ProjectDex(signature, outDir.listFiles { file -> file.extension == "dex" }.orEmpty().sortedBy { it.name })
    }

    override fun computeSourceHash(source: String): String {
        val cache = dexCache
        if (cache == null) {
            LOG.warn("DexCache not initialized, using non-deterministic hash fallback")
            return source.hashCode().toString()
        }
        return cache.computeSourceHash(source)
    }

    override fun reset() {
        classpathManager = null
        compiler = null
        compilerDaemon = null
        dexCompiler = null
        daemonInitialized = false
        cachedClasspath = null
        projectContext = null
        runtimeDex = null
        LOG.debug("Repository reset")
    }
}
