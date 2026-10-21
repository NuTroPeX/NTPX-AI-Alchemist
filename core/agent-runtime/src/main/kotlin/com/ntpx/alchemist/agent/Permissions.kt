package com.ntpx.alchemist.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Everything an agent step can be gated on. */
enum class Permission(val label: String, val description: String) {
    READ_MEMORY("Read memory", "Search and read entries the user has stored"),
    WRITE_MEMORY("Write memory", "Create new memory entries"),
    INVOKE_LOCAL_MODEL("Local models", "Run inference on-device or on the local network"),
    INVOKE_CLOUD_MODEL("Cloud models", "Send prompts to a remote model provider"),
    INVOKE_TOOL("Tools", "Call MCP / plugin tools"),
    NETWORK("Network", "Open connections to the internet"),
    FILE_READ("Read files", "Read files the user has shared with the app"),
    FILE_WRITE("Write files", "Create or modify files"),
}

/** Standing decision for a permission. */
enum class Grant { ALLOW, ASK, DENY }

/** Answer from the user when a step needs consent. */
enum class ConsentDecision { ALLOW_ONCE, ALLOW_ALWAYS, DENY }

data class ConsentRequest(
    val agentId: String,
    val taskId: String,
    val permission: Permission,
    val reason: String,
)

/** Implemented by the UI (dialog) or by tests. Must not block the caller's thread. */
fun interface ConsentProvider {
    suspend fun requestConsent(request: ConsentRequest): ConsentDecision
}

/** Denies every request. The safe default when no UI is attached (headless/scheduled runs). */
object DenyAllConsent : ConsentProvider {
    override suspend fun requestConsent(request: ConsentRequest): ConsentDecision = ConsentDecision.DENY
}

/**
 * Per-agent grant table. Anything not set falls back to [defaults]; anything not in
 * [defaults] is [Grant.DENY]. This makes the runtime fail closed.
 */
class GrantTable(
    val defaults: Map<Permission, Grant> = DEFAULT_GRANTS,
    initial: Map<String, Map<Permission, Grant>> = emptyMap(),
) {
    private val state = MutableStateFlow(initial)

    val overrides: StateFlow<Map<String, Map<Permission, Grant>>> = state.asStateFlow()

    fun grantFor(agentId: String, permission: Permission): Grant =
        state.value[agentId]?.get(permission) ?: defaults[permission] ?: Grant.DENY

    fun set(agentId: String, permission: Permission, grant: Grant) {
        state.update { current ->
            val forAgent = current[agentId].orEmpty() + (permission to grant)
            current + (agentId to forAgent)
        }
    }

    fun reset(agentId: String) {
        state.update { it - agentId }
    }

    fun snapshot(agentId: String): Map<Permission, Grant> =
        Permission.entries.associateWith { grantFor(agentId, it) }

    companion object {
        val DEFAULT_GRANTS: Map<Permission, Grant> = mapOf(
            Permission.READ_MEMORY to Grant.ALLOW,
            Permission.INVOKE_LOCAL_MODEL to Grant.ALLOW,
            Permission.WRITE_MEMORY to Grant.ASK,
            Permission.INVOKE_TOOL to Grant.ASK,
            Permission.INVOKE_CLOUD_MODEL to Grant.DENY,
            Permission.NETWORK to Grant.DENY,
            Permission.FILE_READ to Grant.DENY,
            Permission.FILE_WRITE to Grant.DENY,
        )
    }
}

/** Resolves grants, asks for consent when needed, and records every decision. */
class PermissionGate(
    private val grants: GrantTable,
    private val consent: ConsentProvider,
    private val audit: AuditLog,
) {
    suspend fun authorize(agentId: String, taskId: String, permission: Permission, reason: String): Boolean {
        val allowed = when (grants.grantFor(agentId, permission)) {
            Grant.ALLOW -> true
            Grant.DENY -> false
            Grant.ASK -> when (consent.requestConsent(ConsentRequest(agentId, taskId, permission, reason))) {
                ConsentDecision.ALLOW_ONCE -> true
                ConsentDecision.ALLOW_ALWAYS -> {
                    grants.set(agentId, permission, Grant.ALLOW)
                    true
                }
                ConsentDecision.DENY -> false
            }
        }
        audit.append(
            agentId = agentId,
            taskId = taskId,
            type = if (allowed) AuditType.PERMISSION_GRANTED else AuditType.PERMISSION_DENIED,
            detail = "${permission.name}: $reason",
        )
        return allowed
    }
}
