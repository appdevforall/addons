package com.itsaky.androidide.plugins.aiagentopenai.settings

import com.itsaky.androidide.plugins.aiagentopenai.R

/**
 * One entry in the server picker: a label and the base URL it fills in.
 *
 * @param labelRes this plugin's string for the server's name
 * @param url the base URL to write into the field, or null for the free-text "Custom" entry
 */
internal data class ServerPreset(val labelRes: Int, val url: String?)

/**
 * The servers offered as one-tap presets.
 *
 * Every one speaks the same `chat/completions` protocol, which is why they are a list of URLs and
 * not a list of backends. Selecting a preset only fills the URL field; nothing is saved until the
 * user saves.
 */
internal object ServerPresets {

    /** Ollama's OpenAI-compatible port, running in the bundled Termux or on this device. */
    private const val OLLAMA_LOCAL = "http://localhost:11434/v1"

    /** LM Studio's server default; the host part is what the user usually edits. */
    private const val LM_STUDIO = "http://localhost:1234/v1"

    /** `llama-server` from llama.cpp, the third common on-device or LAN server. */
    private const val LLAMA_SERVER = "http://localhost:8080/v1"

    /** OpenRouter, which fronts many models — including free-tier ones — behind one key. */
    private const val OPENROUTER = "https://openrouter.ai/api/v1"

    /** Presets in the order shown, OpenAI first because it is the default. */
    val ALL: List<ServerPreset> = listOf(
        ServerPreset(R.string.preset_openai, BaseUrlPolicy.DEFAULT_BASE_URL),
        ServerPreset(R.string.preset_ollama, OLLAMA_LOCAL),
        ServerPreset(R.string.preset_lm_studio, LM_STUDIO),
        ServerPreset(R.string.preset_llama_server, LLAMA_SERVER),
        ServerPreset(R.string.preset_openrouter, OPENROUTER),
        ServerPreset(R.string.preset_custom, null),
    )

    /**
     * The preset whose URL matches [url], for restoring the picker's position.
     *
     * @return the index in [ALL], or the index of the "Custom" entry when nothing matches
     */
    fun indexOf(url: String?): Int {
        val normalized = (BaseUrlPolicy.normalize(url) as? BaseUrlResult.Accepted)?.url
        val match = ALL.indexOfFirst { it.url != null && it.url == normalized }
        return if (match >= 0) match else ALL.indexOfFirst { it.url == null }
    }
}
