package com.itsaky.androidide.plugins.vectorsearch

import com.itsaky.androidide.plugins.extensions.PluginTooltipButton
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry

/**
 * This plugin's in-app help: the tags its controls carry and the entries the host registers under
 * them. Kept apart from the plugin so the search code is not interleaved with help copy.
 */
object VectorSearchHelp {

    const val TAG_PLUGIN = "plugin_vector_search"

    // Tags for the controls on the Semantic Search screen (see SemanticSearchSettingsFragment).
    const val TAG_BACK = "vector_search_back"
    const val TAG_BACKEND = "vector_search_backend"
    const val TAG_EMBEDDING_MODEL = "vector_search_embedding_model"
    const val TAG_PRIVACY = "vector_search_privacy"
    const val TAG_INDEX_STATUS = "vector_search_index_status"
    const val TAG_CLEAR_INDEX = "vector_search_clear_index"

    /** Every tooltip this plugin registers, under [VectorSearchPlugin.TOOLTIP_CATEGORY]. */
    fun entries(): List<PluginTooltipEntry> = listOf(
        PluginTooltipEntry(
            tag = TAG_PLUGIN,
            summary = "Vector Search adds semantic, meaning-based matches to project search.",
            detail = """
                <p><b>Vector Search</b> chunks project files, embeds them with
                the AI backend you selected in AI settings, and ranks matches by
                semantic similarity instead of only exact text.</p>
                <p>It needs a backend that produces embeddings, which today means
                a cloud one. With no such backend selected it simply contributes
                no results, rather than quietly matching on words and presenting
                that as semantic search.</p>
                <p>Changing the backend or its embedding model builds the index
                again: vectors from two different models cannot be compared.</p>
            """.trimIndent(),
            buttons = listOf(guideButton()),
        ),
        PluginTooltipEntry(
            tag = TAG_BACK,
            summary = "Back to Preferences.",
            detail = """
                <p>Leaves the Semantic Search screen. Nothing here needs saving:
                the embedding model is stored as soon as you pick it.</p>
            """.trimIndent(),
        ),
        PluginTooltipEntry(
            tag = TAG_BACKEND,
            summary = "The AI backend search embeds with, and whether it supports Vector Search.",
            detail = """
                <p>Vector Search uses the backend selected in <b>AI settings</b>,
                the same one the Agent chats with. This shows its name, its chat
                model, and whether it can produce embeddings.</p>
                <p><b>Not supported</b> means the backend cannot embed, or its
                plugin is too old to offer its embedding models here. Update its
                plugin, since newer Gemini and OpenAI plugins support this, or
                select a backend that does in AI settings. Local never embeds.</p>
                <p>The screen updates on its own when you switch backend.</p>
            """.trimIndent(),
            buttons = listOf(guideButton()),
        ),
        PluginTooltipEntry(
            tag = TAG_EMBEDDING_MODEL,
            summary = "Which model turns your code into vectors for search. Never used for chat.",
            detail = """
                <p>The list holds the embedding models your backend can use right
                now, such as the Gemini models that support
                <code>embedContent</code>. The choice is stored by the backend
                plugin itself.</p>
                <p>Changing it changes the vector space, so the next search builds
                the index again from scratch with the new model. Vectors from two
                different models cannot be compared.</p>
                <p>If the list cannot load (no API key, no network) the field still
                shows the model in use, with the reason beneath it.</p>
            """.trimIndent(),
            buttons = listOf(guideButton()),
        ),
        PluginTooltipEntry(
            tag = TAG_PRIVACY,
            summary = "Indexing sends your project's source code to the selected backend.",
            detail = """
                <p>To embed your code, Vector Search sends each chunk of the
                project's source files to the provider behind the selected
                backend. The vectors that come back are stored only on this
                device.</p>
                <p>Your query is sent the same way each time you search.</p>
            """.trimIndent(),
            buttons = listOf(guideButton()),
        ),
        PluginTooltipEntry(
            tag = TAG_INDEX_STATUS,
            summary = "Whether the open project's index is ready, and what it holds.",
            detail = """
                <p>The first search in a project builds its index, and later
                searches reuse it.</p>
                <p><b>Up to date</b>: built with the selected backend and model.
                <b>Out of date</b>: built with another model, so the next search
                rebuilds it. <b>Indexing</b>: a build is running; the bar shows how
                many chunks are embedded. <b>Last build failed</b>: nothing was
                stored; fix the backend's key or quota, then clear the index to
                try again. <b>Not indexed yet</b>: no search has run here since the
                index was built or cleared.</p>
                <p>Below that: how many chunks and files are indexed, the model and
                vector size, and when the index was last built.</p>
            """.trimIndent(),
        ),
        PluginTooltipEntry(
            tag = TAG_CLEAR_INDEX,
            summary = "Deletes the stored index of every project, after you confirm.",
            detail = """
                <p>Clears the index for <b>every</b> project, not only the open
                one. Nothing is deleted until you confirm.</p>
                <p>The next search builds the index again, which sends the
                project's source to the selected backend again.</p>
            """.trimIndent(),
            buttons = listOf(guideButton()),
        ),
    )

    /** The Tier 3 guide, which covers the settings screen as well as search itself. */
    private fun guideButton() = PluginTooltipButton(
        description = "Vector Search guide",
        uri = "index.html",
        order = 0,
    )
}
