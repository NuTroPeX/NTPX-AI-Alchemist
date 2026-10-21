package com.ntpx.alchemist.mcp

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

class McpTest {

    private class NetTool : ToolProvider {
        override val definition = ToolDefinition(
            name = "web.fetch",
            description = "test",
            inputSchema = stringArgsSchema(required = listOf("url")),
            meta = buildJsonObject { put(PERMISSIONS_META_KEY, JsonArray(listOf(JsonPrimitive("network")))) },
        )

        override suspend fun invoke(arguments: JsonObject): CallToolResult = error("offline")
    }

    private val server = McpServer(Implementation("test-server", "1.0"), builtinTools() + NetTool())
    private val client = McpClient(LoopbackTransport(server), Implementation("test-client", "1.0"))

    @Test
    fun handshakeNegotiatesVersionAndMarksInitialized() = runTest {
        val info = client.initialize()
        assertEquals(MCP_PROTOCOL_VERSION, info.protocolVersion)
        assertEquals("test-server", info.serverInfo.name)
        assertTrue(server.initialized)
        assertEquals(info, client.initialize()) // idempotent
    }

    @Test
    fun listsToolsSortedWithDeclaredPermissions() = runTest {
        val tools = client.listTools()
        assertEquals(listOf("clock.now", "math.evaluate", "text.stats", "web.fetch"), tools.map { it.name })
        assertEquals(setOf("network"), tools.first { it.name == "web.fetch" }.declaredPermissions)
        assertEquals(setOf("text"), tools.first { it.name == "text.stats" }.requiredArguments)
    }

    @Test
    fun callsTextStats() = runTest {
        val result = client.callTool("text.stats", mapOf("text" to "Forge the core. Ship it!\nAgain"))
        assertFalse(result.isError)
        assertEquals("words=6 chars=30 lines=2 sentences=3", result.text)
    }

    @Test
    fun missingAndUnknownArgumentsAreInvalidParams() = runTest {
        expectError(JsonRpcError.INVALID_PARAMS) { client.callTool("text.stats", emptyMap()) }
        expectError(JsonRpcError.INVALID_PARAMS) { client.callTool("text.stats", mapOf("text" to "a", "extra" to "b")) }
        expectError(JsonRpcError.INVALID_PARAMS) { client.callTool("nope", emptyMap()) }
    }

    @Test
    fun toolExceptionsAreReportedInBand() = runTest {
        val result = client.callTool("web.fetch", mapOf("url" to "https://example.com"))
        assertTrue(result.isError)
        assertTrue(result.text.contains("offline"))
    }

    @Test
    fun serverRejectsMalformedAndUnknownMethods() = runTest {
        val parse = JsonRpcCodec.json.parseToJsonElement(server.handle("{oops")!!).jsonObject
        assertEquals(JsonRpcError.PARSE_ERROR, parse["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)

        val notFound = server.handle("""{"jsonrpc":"2.0","id":7,"method":"resources/list"}""")!!
        val decoded = JsonRpcCodec.decode(notFound) as JsonRpcResponse
        assertEquals(JsonPrimitive(7), decoded.id)
        assertEquals(JsonRpcError.METHOD_NOT_FOUND, decoded.error?.code)

        val wrongVersion = JsonRpcCodec.decode(server.handle("""{"jsonrpc":"1.0","id":1,"method":"ping"}""")!!) as JsonRpcResponse
        assertEquals(JsonRpcError.INVALID_REQUEST, wrongVersion.error?.code)
    }

    @Test
    fun notificationsGetNoResponse() = runTest {
        assertNull(server.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
    }

    @Test
    fun duplicateOrInvalidToolNamesRejected() {
        try {
            McpServer(Implementation("s", "1"), listOf(TextStatsTool(), TextStatsTool()))
            fail("duplicates accepted")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun mathEvaluatesWithPrecedenceAndRejectsAbuse() = runTest {
        val math = MathTool()
        assertEquals("15.2", math.evaluate("(2 + 3) * 4 ^ 2 / 5 + -0.8").stripTrailingZeros().toPlainString())
        assertEquals("512", math.evaluate("2 ^ 3 ^ 2").toPlainString())
        assertEquals("1", math.evaluate("10 % 3").toPlainString())
        assertTrue(client.callTool("math.evaluate", mapOf("expression" to "1/0")).isError)
        assertTrue(client.callTool("math.evaluate", mapOf("expression" to "2 ^ 100000")).isError)
        assertTrue(client.callTool("math.evaluate", mapOf("expression" to "2 ^ 4294967296")).isError)
        assertTrue(client.callTool("math.evaluate", mapOf("expression" to "2 ^ -4294967297")).isError)
        assertTrue(client.callTool("math.evaluate", mapOf("expression" to "exec(1)")).isError)
        assertTrue(client.callTool("math.evaluate", mapOf("expression" to "(".repeat(100) + "1" + ")".repeat(100))).isError)
    }

    @Test
    fun clockUsesRequestedZone() = runTest {
        val clock = ClockTool { Instant.parse("2026-10-21T13:50:00Z") }
        val result = clock.invoke(buildJsonObject { put("zone", JsonPrimitive("America/New_York")) })
        assertEquals("2026-10-21T09:50:00-04:00 [America/New_York]", result.text)
        assertTrue(clock.invoke(buildJsonObject { put("zone", JsonPrimitive("Mars/Base")) }).isError)
    }

    private suspend fun expectError(code: Int, block: suspend () -> Unit) {
        try {
            block()
            fail("Expected MCP error $code")
        } catch (e: McpProtocolException) {
            assertEquals(code, e.error.code)
        }
    }

    @Test
    fun sentenceCountIncludesUnpunctuatedTailAndIgnoresBarePunctuation() = runTest {
        suspend fun sentences(text: String) = client.callTool("text.stats", mapOf("text" to text)).text.substringAfter("sentences=")
        assertEquals("2", sentences("First. Second"))
        assertEquals("1", sentences("Just one"))
        assertEquals("0", sentences("   "))
        assertEquals("2", sentences("Wait. What?!"))
        assertEquals("1", sentences("?! Really?"))
        assertEquals("0", sentences("..."))
    }

    @Test
    fun wellFormedJsonWithWrongShapesGetsInvalidRequest() = runTest {
        listOf(
            """{"jsonrpc":{},"id":1,"method":"ping"}""",
            """{"jsonrpc":"2.0","id":{},"method":"ping"}""",
            """{"jsonrpc":"2.0","id":1,"error":"boom"}""",
            """{"jsonrpc":"2.0","id":1,"method":5}""",
            """{"jsonrpc":"2.0","id":1,"method":{}}""",
            """{"jsonrpc":"2.0","id":1}""",
            """[1,2,3]""",
        ).forEach { raw ->
            val response = server.handle(raw)
            val code = (JsonRpcCodec.decode(response!!) as JsonRpcResponse).error?.code
            assertTrue("Unexpected code $code for $raw", code == JsonRpcError.INVALID_REQUEST || code == JsonRpcError.PARSE_ERROR)
        }
    }
}
