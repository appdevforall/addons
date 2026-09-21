package com.itsaky.androidide.plugins.aiagentopenai.settings

/**
 * Where an OpenAI API key comes from.
 *
 * Open [API_KEYS_URL] in a real browser, sign in there, copy the key, paste it into the key field.
 * This plugin never sees a password, and never reads the clipboard.
 */
object OpenAiKeyOnboarding {

    /**
     * OpenAI's API keys page.
     *
     * Note there is no free tier: a key needs a prepaid balance and a ChatGPT subscription does not
     * include API access. The free paths are a local server — which the URL field reaches — or one
     * of the other backend plugins; the settings pane and the guide both say so.
     */
    const val API_KEYS_URL = "https://platform.openai.com/api-keys"
}
