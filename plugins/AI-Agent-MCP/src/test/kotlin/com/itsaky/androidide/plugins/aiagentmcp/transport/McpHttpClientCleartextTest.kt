package com.itsaky.androidide.plugins.aiagentmcp.transport

import org.json.JSONObject
import org.junit.Test

/** The transport's own [McpUrlPolicy] check, which stops a server saved before Save refused it. */
class McpHttpClientCleartextTest {

    @Test(expected = McpCleartextException::class)
    fun givenAnHttpUrlOffThisDevice_whenPosted_thenItIsRefusedBeforeAnySocketOpens() {
        McpHttpClient().post("http://wycro.example/mcp", "token", JSONObject())
    }

    @Test(expected = McpCleartextException::class)
    fun givenAnHttpUrlOffThisDevice_whenItsSessionIsDeleted_thenItIsRefusedToo() {
        McpHttpClient().deleteSession("http://wycro.example/mcp", "token", "session")
    }
}
