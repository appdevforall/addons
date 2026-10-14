package com.itsaky.androidide.plugins.aiagentmcp.transport

import java.io.IOException

/**
 * An `http://` endpoint off this device, refused before any socket is opened.
 *
 * Reachable only by a server saved before [McpUrlPolicy] existed: Save refuses such a URL, so this
 * is what keeps an older one from connecting until the user switches it to `https://`.
 *
 * @param detail what was refused, for logcat; the user sees the formatted sentence instead.
 */
class McpCleartextException(detail: String) : IOException(detail)
