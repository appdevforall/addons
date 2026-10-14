package com.itsaky.androidide.plugins.aiagentlocal.model

import android.llama.cpp.LLamaAndroid
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the first rung of new_context's fallback ladder on a device: a quantized KV cache refused,
 * then retried at f16 with the caller's fallback context. JVM tests cannot load the native library,
 * so without this the rung only ever runs for a user on a model nobody here picked.
 */
@RunWith(AndroidJUnit4::class)
class NativeContextFallbackTest {

    private val llama = LLamaAndroid.instance()

    private lateinit var model: File

    @Before
    fun copyModelOutOfAssets() {
        // llama.cpp opens a filesystem path; the model ships in the test APK, never in the .cgp.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        model = File(instrumentation.targetContext.cacheDir, MODEL_ASSET)
        instrumentation.context.assets.open(MODEL_ASSET).use { input ->
            model.outputStream().use { input.copyTo(it) }
        }
    }

    @After
    fun unload() {
        runBlocking { llama.unload() }
        // A failed @Before leaves it unset; touching it here would mask that failure.
        if (::model.isInitialized) model.delete()
    }

    @Test
    fun givenAHeadWidthQ8_0CannotDivide_whenLoadedWithAQuantizedCache_thenItRetriesAtF16WithTheFallbackContext() =
        runBlocking {
            llama.load(model.absolutePath, REQUESTED_N_CTX, quantizeKv = true, fallbackNCtx = FALLBACK_N_CTX)

            // q8_0 would have kept REQUESTED_N_CTX and the floor rung needs more than 4096, so only
            // the f16 retry can produce this size.
            assertEquals("Context not created by the f16 retry", FALLBACK_N_CTX, llama.getContextSize())

            val tokens = llama.send(PROMPT).take(GENERATED_TOKENS).toList()
            assertTrue("The fallback context generated nothing", tokens.isNotEmpty())
        }

    private companion object {
        /**
         * llama2.c's stories260K (MIT), as ggml-org ships it for llama.cpp's own CI: 1.2 MB, and a
         * head width of 64 / 8 = 8, which q8_0's 32-wide blocks cannot divide, so llama.cpp refuses
         * the quantized cache before allocating anything. Trained for 2048, so neither size clamps.
         */
        const val MODEL_ASSET = "stories260K.gguf"

        // Distinct sizes, so the created context shows which attempt produced it.
        const val REQUESTED_N_CTX = 1024
        const val FALLBACK_N_CTX = 512

        const val PROMPT = "Once upon a time"
        const val GENERATED_TOKENS = 4
    }
}
