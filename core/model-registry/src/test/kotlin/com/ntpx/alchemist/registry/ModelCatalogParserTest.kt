package com.ntpx.alchemist.registry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogParserTest {

    private val valid = """
        {
          "schemaVersion": 1,
          "models": [
            {
              "id": "ntpx/echo-local",
              "displayName": "Echo",
              "provider": "NTPX",
              "placement": "on_device",
              "capabilities": ["chat", "text_generation"],
              "backend": "echo",
              "availability": "ready",
              "futureField": "ignored"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun parsesValidCatalogAndIgnoresUnknownKeys() {
        val result = ModelCatalogParser.parse(valid)
        val catalog = (result as CatalogResult.Success).catalog
        val model = catalog.models.single()
        assertEquals("ntpx/echo-local", model.id)
        assertEquals(Placement.ON_DEVICE, model.placement)
        assertEquals(setOf(Capability.CHAT, Capability.TEXT_GENERATION), model.capabilities)
    }

    @Test
    fun roundTripsThroughEncode() {
        val catalog = (ModelCatalogParser.parse(valid) as CatalogResult.Success).catalog
        val again = ModelCatalogParser.parse(ModelCatalogParser.encode(catalog))
        assertEquals(catalog, (again as CatalogResult.Success).catalog)
    }

    @Test
    fun rejectsUnsupportedSchemaVersion() {
        val result = ModelCatalogParser.parse(valid.replace("\"schemaVersion\": 1", "\"schemaVersion\": 9"))
        assertTrue(result is CatalogResult.Failure)
    }

    @Test
    fun rejectsDuplicateIds() {
        val dup = """
            {"schemaVersion":1,"models":[
              {"id":"a","displayName":"A","provider":"p","placement":"cloud","capabilities":["chat"],"backend":"x"},
              {"id":"a","displayName":"A2","provider":"p","placement":"cloud","capabilities":["chat"],"backend":"x"}
            ]}
        """.trimIndent()
        val result = ModelCatalogParser.parse(dup) as CatalogResult.Failure
        assertTrue(result.reason.contains("a"))
    }

    @Test
    fun rejectsMalformedJsonAndInvalidEntries() {
        assertTrue(ModelCatalogParser.parse("{not json") is CatalogResult.Failure)
        val emptyCaps = """
            {"schemaVersion":1,"models":[
              {"id":"a","displayName":"A","provider":"p","placement":"cloud","capabilities":[],"backend":"x"}
            ]}
        """.trimIndent()
        assertTrue(ModelCatalogParser.parse(emptyCaps) is CatalogResult.Failure)
    }
}
