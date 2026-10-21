package com.ntpx.alchemist.mcp

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicLong

/**
 * Request/response transport. Notifications are sent with [notify] and get no reply.
 * The loopback transport is in-process; network transports (Streamable HTTP) plug in here.
 */
interface McpTransport {
    suspend fun request(message: String): String
    suspend fun notify(message: String)
}

/** Connects a client directly to an in-process [McpServer]. */
class LoopbackTransport(private val server: McpServer) : McpTransport {
    override suspend fun request(message: String): String =
        server.handle(message) ?: error("Server returned no response to a request")

    override suspend fun notify(message: String) {
        server.handle(message)
    }
}

class McpClient(
    private val transport: McpTransport,
    private val clientInfo: Implementation,
) {
    private val ids = AtomicLong(1)
    private val initLock = Mutex()

    @Volatile
    var serverInfo: InitializeResult? = null
        private set

    suspend fun initialize(): InitializeResult = initLock.withLock {
        serverInfo?.let { return it }
        val result = JsonRpcCodec.json.decodeFromJsonElement(
            InitializeResult.serializer(),
            call(
                "initialize",
                buildJsonObject {
                    put("protocolVersion", MCP_PROTOCOL_VERSION)
                    put("capabilities", JsonObject(emptyMap()))
                    put("clientInfo", JsonRpcCodec.json.encodeToJsonElement(Implementation.serializer(), clientInfo))
                },
            ),
        )
        transport.notify(JsonRpcCodec.encode(JsonRpcNotification("notifications/initialized", null)))
        serverInfo = result
        result
    }

    suspend fun listTools(): List<ToolDefinition> {
        initialize()
        return JsonRpcCodec.json.decodeFromJsonElement(ListToolsResult.serializer(), call("tools/list", null)).tools
    }

    suspend fun callTool(name: String, arguments: Map<String, String>): CallToolResult {
        initialize()
        val params = buildJsonObject {
            put("name", name)
            put("arguments", JsonObject(arguments.mapValues { JsonPrimitive(it.value) }))
        }
        return JsonRpcCodec.json.decodeFromJsonElement(CallToolResult.serializer(), call("tools/call", params))
    }

    suspend fun ping() {
        call("ping", null)
    }

    private suspend fun call(method: String, params: JsonObject?): JsonElement {
        val id = JsonPrimitive(ids.getAndIncrement())
        val raw = transport.request(JsonRpcCodec.encode(JsonRpcRequest(id, method, params)))
        val response = JsonRpcCodec.decode(raw) as? JsonRpcResponse
            ?: throw McpProtocolException(JsonRpcError(JsonRpcError.INVALID_REQUEST, "Expected a response to $method"))
        if (response.id != id) {
            throw McpProtocolException(JsonRpcError(JsonRpcError.INVALID_REQUEST, "Response id ${response.id} does not match $id"))
        }
        response.error?.let { throw McpProtocolException(it) }
        return response.result ?: JsonObject(emptyMap())
    }
}
