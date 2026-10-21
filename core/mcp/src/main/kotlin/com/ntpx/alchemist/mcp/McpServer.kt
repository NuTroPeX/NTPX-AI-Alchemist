package com.ntpx.alchemist.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** A plugin-provided tool. Implementations must treat [arguments] as untrusted input. */
interface ToolProvider {
    val definition: ToolDefinition
    suspend fun invoke(arguments: JsonObject): CallToolResult
}

/**
 * In-process MCP server hosting [ToolProvider]s. Speaks the JSON-RPC subset needed for
 * tools: `initialize`, `notifications/initialized`, `ping`, `tools/list`, `tools/call`.
 */
class McpServer(
    private val info: Implementation,
    providers: Collection<ToolProvider>,
    private val instructions: String? = null,
) {
    private val tools: Map<String, ToolProvider>

    init {
        val byName = providers.groupBy { it.definition.name }
        val duplicates = byName.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate tool names: ${duplicates.joinToString()}" }
        byName.keys.forEach { require(TOOL_NAME.matches(it)) { "Invalid tool name '$it'" } }
        tools = byName.mapValues { it.value.single() }
    }

    @Volatile
    var initialized: Boolean = false
        private set

    /** Handles one raw message. Returns the response text, or null for notifications. */
    suspend fun handle(raw: String): String? {
        val message = try {
            JsonRpcCodec.decode(raw)
        } catch (e: McpProtocolException) {
            return JsonRpcCodec.encode(JsonRpcResponse(JsonRpcCodec.peekId(raw), null, e.error))
        }
        return when (message) {
            is JsonRpcNotification -> {
                if (message.method == "notifications/initialized") initialized = true
                null
            }
            is JsonRpcResponse -> null
            is JsonRpcRequest -> JsonRpcCodec.encode(respond(message))
        }
    }

    private suspend fun respond(request: JsonRpcRequest): JsonRpcResponse = try {
        JsonRpcResponse(request.id, dispatch(request), null)
    } catch (e: McpProtocolException) {
        JsonRpcResponse(request.id, null, e.error)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        JsonRpcResponse(request.id, null, JsonRpcError(JsonRpcError.INTERNAL_ERROR, e.message ?: "Internal error"))
    }

    private suspend fun dispatch(request: JsonRpcRequest): JsonElement = when (request.method) {
        "initialize" -> JsonRpcCodec.json.encodeToJsonElement(
            InitializeResult.serializer(),
            InitializeResult(
                protocolVersion = MCP_PROTOCOL_VERSION,
                capabilities = buildJsonObject { putJsonObject("tools") { put("listChanged", false) } },
                serverInfo = info,
                instructions = instructions,
            ),
        )
        "ping" -> JsonObject(emptyMap())
        "tools/list" -> JsonRpcCodec.json.encodeToJsonElement(
            ListToolsResult.serializer(),
            ListToolsResult(tools.values.map { it.definition }.sortedBy { it.name }),
        )
        "tools/call" -> JsonRpcCodec.json.encodeToJsonElement(CallToolResult.serializer(), callTool(request.params))
        else -> throw McpProtocolException(JsonRpcError(JsonRpcError.METHOD_NOT_FOUND, "Method not found: ${request.method}"))
    }

    private suspend fun callTool(params: JsonObject?): CallToolResult {
        val name = (params?.get("name") as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw invalidParams("tools/call requires a string 'name'")
        val provider = tools[name] ?: throw invalidParams("Unknown tool: $name")
        val arguments = when (val raw = params["arguments"]) {
            null, JsonNull -> JsonObject(emptyMap())
            is JsonObject -> raw
            else -> throw invalidParams("'arguments' must be an object")
        }
        val missing = provider.definition.requiredArguments - arguments.keys
        if (missing.isNotEmpty()) throw invalidParams("Missing required arguments: ${missing.sorted().joinToString()}")
        val properties = provider.definition.inputSchema["properties"] as? JsonObject
        val additionalAllowed = provider.definition.inputSchema["additionalProperties"]?.jsonPrimitive?.content != "false"
        if (properties != null && !additionalAllowed) {
            val unknown = arguments.keys - properties.keys
            if (unknown.isNotEmpty()) throw invalidParams("Unknown arguments: ${unknown.sorted().joinToString()}")
        }
        return try {
            provider.invoke(arguments)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Tool failures are reported in-band so the model/agent can see and react to them.
            CallToolResult.error("Tool '$name' failed: ${e.message ?: e::class.simpleName}")
        }
    }

    private fun invalidParams(message: String) = McpProtocolException(JsonRpcError(JsonRpcError.INVALID_PARAMS, message))

    companion object {
        val TOOL_NAME = Regex("^[A-Za-z0-9_.-]{1,64}$")
    }
}
