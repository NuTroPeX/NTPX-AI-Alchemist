package com.ntpx.alchemist.mcp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

const val MCP_PROTOCOL_VERSION = "2025-06-18"

/** Key under a tool's `_meta` where NTPX tools declare the permissions they need. */
const val PERMISSIONS_META_KEY = "ntpx/permissions"

@Serializable
data class Implementation(val name: String, val version: String)

@Serializable
data class InitializeResult(
    val protocolVersion: String,
    val capabilities: JsonObject,
    val serverInfo: Implementation,
    val instructions: String? = null,
)

@Serializable
data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    @SerialName("_meta") val meta: JsonObject? = null,
) {
    /** Permission names declared via `_meta["ntpx/permissions"]`, e.g. `["network"]`. */
    val declaredPermissions: Set<String>
        get() = (meta?.get(PERMISSIONS_META_KEY) as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            ?.toSet()
            .orEmpty()

    val requiredArguments: Set<String>
        get() = (inputSchema["required"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            ?.toSet()
            .orEmpty()
}

@Serializable
data class ListToolsResult(val tools: List<ToolDefinition>)

@Serializable
data class TextContent(val type: String = "text", val text: String)

@Serializable
data class CallToolResult(
    val content: List<TextContent>,
    val isError: Boolean = false,
) {
    val text: String get() = content.joinToString("\n") { it.text }

    companion object {
        fun text(value: String) = CallToolResult(listOf(TextContent(text = value)))
        fun error(value: String) = CallToolResult(listOf(TextContent(text = value)), isError = true)
    }
}

/** Small helper for declaring JSON Schemas of string-typed tool arguments. */
fun stringArgsSchema(required: List<String>, optional: List<String> = emptyList(), descriptions: Map<String, String> = emptyMap()): JsonObject =
    buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            (required + optional).forEach { name ->
                putJsonObject(name) {
                    put("type", "string")
                    descriptions[name]?.let { put("description", it) }
                }
            }
        }
        put("required", kotlinx.serialization.json.JsonArray(required.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        put("additionalProperties", false)
    }
