package com.itsaky.androidide.plugins.aiagentmcp.transport

import java.io.IOException

/**
 * A redirect this client will not follow.
 *
 * Its own type so the failure reaches the user as one translated sentence rather than as the reason
 * text: every request carries the bearer token and the user's own headers, and a redirect off the
 * configured origin is a change of who receives them, not a transport hiccup.
 *
 * @param detail what was refused, for logcat; the user sees the formatted sentence instead.
 */
class McpRedirectException(detail: String) : IOException(detail)
