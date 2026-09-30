package com.itsaky.androidide.plugins.aicore.tool.web

/**
 * The names the web tools go by, in the tool list and between ai-core and the backends. Both tools
 * are always offered: a search goes to the provider already receiving the conversation, and each
 * fetch asks the user first.
 */
object WebAccess {

    /**
     * [com.itsaky.androidide.plugins.services.LlmInferenceService.LlmConfig.extraParams] key asking a
     * backend to answer from a web search. Every backend in this repository reads the same literal.
     */
    const val EXTRA_PARAM_WEB_SEARCH = "web_search"

    /**
     * [com.itsaky.androidide.plugins.services.LlmInferenceService.LlmConfig.extraParams] key naming
     * the one declared tool the model must call this turn; see [VerificationPolicy]. A backend that
     * cannot force a call ignores it, and the agent loop asks for the call instead.
     */
    const val EXTRA_PARAM_REQUIRED_TOOL = "required_tool"

    const val WEB_SEARCH_TOOL = "web_search"
    const val FETCH_URL_TOOL = "fetch_url"

    /** The built-in tools that reach the internet. */
    val TOOL_NAMES: Set<String> = setOf(WEB_SEARCH_TOOL, FETCH_URL_TOOL)
}
