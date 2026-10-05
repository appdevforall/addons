package com.itsaky.androidide.plugins.aiagentclaude.settings

/**
 * Where a Claude API key comes from.
 *
 * Open [API_KEYS_URL] in a real browser, sign in there, copy the key, paste it into the key field.
 * This plugin never sees a password, and never reads the clipboard.
 */
object ClaudeKeyOnboarding {

    /**
     * The Claude Console's API keys page.
     *
     * Note there is no free tier: API usage is billed to prepaid credit, separate from a Claude.ai
     * subscription. The free paths are the other backend plugins; the settings pane and the guide
     * both say so.
     */
    const val API_KEYS_URL = "https://platform.claude.com/settings/keys"
}
