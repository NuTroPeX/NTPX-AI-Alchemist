package com.ntpx.alchemist.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** JSON-RPC 2.0 messages as used by the Model Context Protocol. */
sealed interface JsonRpcMessage

data class JsonRpcRequest(val id: JsonElement, val method: String, val params: JsonObject?) : JsonRpcMessage
data class JsonRpcNotification(val method: String, val params: JsonObject?) : JsonRpcMessage
data class JsonRpcResponse(val id: JsonElement, val result: JsonElement?, val error: JsonRpcError?) : JsonRpcMessage

data class JsonRpcError(val code: Int, val message: String, val data: JsonElement? = null) {
    companion object {
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603
    }
}

class McpProtocolException(val error: JsonRpcError) : Exception("MCP error ${error.code}: ${error.message}")

object JsonRpcCodec {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    fun encode(message: JsonRpcMessage): String = json.encodeToString(JsonObject.serializer(), toJson(message))

    fun toJson(message: JsonRpcMessage): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        when (message) {
            is JsonRpcRequest -> {
                put("id", message.id)
                put("method", message.method)
                message.params?.let { put("params", it) }
            }
            is JsonRpcNotification -> {
                put("method", message.method)
                message.params?.let { put("params", it) }
            }
            is JsonRpcResponse -> {
                put("id", message.id)
                if (message.error != null) {
                    put(
                        "error",
                        buildJsonObject {
                            put("code", message.error.code)
                            put("message", message.error.message)
                            message.error.data?.let { put("data", it) }
                        },
                    )
                } else {
                    put("result", message.result ?: JsonObject(emptyMap()))
                }
            }
        }
    }

    /** Decodes one message or throws [McpProtocolException] with a JSON-RPC error code. */
    fun decode(text: String): JsonRpcMessage {
        val obj = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw McpProtocolException(JsonRpcError(JsonRpcError.PARSE_ERROR, "Parse error"))
        }
        // Every structural check uses safe casts: well-formed JSON with wrong shapes must become
        // a JSON-RPC error, never an exception escaping the server.
        if ((obj["jsonrpc"] as? JsonPrimitive)?.takeIf { it.isString }?.content != "2.0") {
            throw invalidRequest("jsonrpc must be \"2.0\"")
        }
        val id = when (val raw = obj["id"]) {
            null, JsonNull -> null
            is JsonPrimitive -> raw
            else -> throw invalidRequest("id must be a string or number")
        }
        val method = when (val raw = obj["method"]) {
            null -> null
            is JsonPrimitive -> raw.takeIf { it.isString }?.content ?: throw invalidRequest("method must be a string")
            else -> throw invalidRequest("method must be a string")
        }
        val params = obj["params"]?.let {
            it as? JsonObject ?: throw McpProtocolException(JsonRpcError(JsonRpcError.INVALID_PARAMS, "params must be an object"))
        }
        return when {
            method != null && id != null -> JsonRpcRequest(id, method, params)
            method != null -> JsonRpcNotification(method, params)
            "result" in obj || "error" in obj -> {
                val error = when (val raw = obj["error"]) {
                    null, JsonNull -> null
                    is JsonObject -> JsonRpcError(
                        code = (raw["code"] as? JsonPrimitive)?.intOrNull ?: JsonRpcError.INTERNAL_ERROR,
                        message = (raw["message"] as? JsonPrimitive)?.contentOrNull ?: "Unknown error",
                        data = raw["data"],
                    )
                    else -> throw invalidRequest("error must be an object")
                }
                JsonRpcResponse(id ?: JsonNull, obj["result"], error)
            }
            else -> throw invalidRequest("Expected a request (method), or a response (result or error)")
        }
    }

    private fun invalidRequest(message: String) = McpProtocolException(JsonRpcError(JsonRpcError.INVALID_REQUEST, message))

    /** Best-effort id extraction so error responses can still be correlated by the caller. */
    fun peekId(text: String): JsonElement = runCatching {
        (json.parseToJsonElement(text) as? JsonObject)?.get("id")?.takeIf { it is JsonPrimitive }
    }.getOrNull() ?: JsonNull
}
