package com.itsaky.androidide.plugins.aiagentgemini.settings

/**
 * Where a Gemini API key comes from.
 *
 * Open [AI_STUDIO_URL] in a real browser, sign in with Google there, copy the key, paste it into
 * the key field. This plugin never sees a Google password, and never reads the clipboard.
 */
object GeminiKeyOnboarding {

    /**
     * AI Studio, not `console.cloud.google.com`: AI Studio creates a default Cloud project on
     * first use, which is why no console step is needed.
     */
    const val AI_STUDIO_URL = "https://aistudio.google.com/apikey"
}
