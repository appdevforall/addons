package com.itsaky.androidide.plugins.aicore.tool.web

/**
 * The names the web tools go by, in the tool list and between ai-core and the backends. A search
 * goes to the provider already receiving the conversation, so it is offered only when that backend
 * can search; each fetch asks the user first.
 */
object WebAccess {

    const val WEB_SEARCH_TOOL = "web_search"
    const val FETCH_URL_TOOL = "fetch_url"

    /** The built-in tools that reach the internet. */
    val TOOL_NAMES: Set<String> = setOf(WEB_SEARCH_TOOL, FETCH_URL_TOOL)
}
