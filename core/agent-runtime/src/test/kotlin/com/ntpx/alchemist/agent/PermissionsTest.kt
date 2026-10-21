package com.ntpx.alchemist.agent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionsTest {

    @Test
    fun unknownPermissionsFailClosed() {
        val table = GrantTable(defaults = emptyMap())
        Permission.entries.forEach { assertEquals(Grant.DENY, table.grantFor("x", it)) }
    }

    @Test
    fun overridesArePerAgentAndResettable() {
        val table = GrantTable()
        table.set("a", Permission.NETWORK, Grant.ALLOW)
        assertEquals(Grant.ALLOW, table.grantFor("a", Permission.NETWORK))
        assertEquals(Grant.DENY, table.grantFor("b", Permission.NETWORK))
        table.reset("a")
        assertEquals(Grant.DENY, table.grantFor("a", Permission.NETWORK))
    }

    @Test
    fun denyAllConsentRejectsAskPermissions() = runTest {
        val audit = InMemoryAuditLog()
        val gate = PermissionGate(GrantTable(), DenyAllConsent, audit)
        assertFalse(gate.authorize("a", "t", Permission.WRITE_MEMORY, "reason"))
        assertTrue(gate.authorize("a", "t", Permission.READ_MEMORY, "reason"))
        assertEquals(
            listOf(AuditType.PERMISSION_DENIED, AuditType.PERMISSION_GRANTED),
            audit.entries.value.map { it.type },
        )
    }

    @Test
    fun plannerRejectsBlankGoals() {
        try {
            RecallGenerateRememberPlanner().plan("a", "   ")
            throw AssertionError("expected failure")
        } catch (_: IllegalArgumentException) {
        }
    }
}
