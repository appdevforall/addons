package com.itsaky.androidide.plugins.aicore.tool.handlers

import com.itsaky.androidide.plugins.PluginContext
import io.mockk.mockk
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `variables` argument. It reaches a handler in a different shape per backend, and a cast to
 * one of them silently dropped every variable the model sent.
 */
class GenerateFromTemplateHandlerTest {

    private val handler = GenerateFromTemplateHandler(mockk<PluginContext>(relaxed = true))

    @Test
    fun givenANestedObject_whenReadingVariables_thenTheyAreKept() {
        // What both the envelope parser and a native call actually hand over: org.json's own type,
        // never a Kotlin Map, which is why `as? Map` was always null.
        val variables = handler.variablesOf(JSONObject("""{"className":"Main","package":"a.b"}"""))

        assertEquals("Main", variables["className"])
        assertEquals("a.b", variables["package"])
    }

    @Test
    fun givenJsonText_whenReadingVariables_thenItIsParsed() {
        // Gemini cannot declare a free-form object, so it sends one as text.
        val variables = handler.variablesOf("""{"className":"Main"}""")

        assertEquals("Main", variables["className"])
    }

    @Test
    fun givenAMap_whenReadingVariables_thenItIsKept() {
        assertEquals(mapOf("a" to 1), handler.variablesOf(mapOf("a" to 1)))
    }

    @Test
    fun givenNothingOrUnparseableText_whenReadingVariables_thenThereAreNone() {
        assertTrue(handler.variablesOf(null).isEmpty())
        assertTrue(handler.variablesOf("not json").isEmpty())
    }

    @Test
    fun givenTheSchema_whenDeclared_thenTheTemplateNameIsTheOnlyRequiredArgument() {
        @Suppress("UNCHECKED_CAST")
        val properties = handler.parametersSchema["properties"] as Map<String, Any>

        assertEquals(setOf("template_name", "variables"), properties.keys)
        assertEquals(listOf("template_name"), handler.parametersSchema["required"])
    }
}
