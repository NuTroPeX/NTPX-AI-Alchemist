package com.ntpx.alchemist.registry

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** JSON catalog format shipped in app assets or imported by the user. */
@Serializable
data class ModelCatalog(
    val schemaVersion: Int,
    val models: List<ModelDescriptor>,
)

sealed interface CatalogResult {
    data class Success(val catalog: ModelCatalog) : CatalogResult
    data class Failure(val reason: String) : CatalogResult
}

object ModelCatalogParser {
    const val SUPPORTED_SCHEMA_VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun parse(text: String): CatalogResult {
        val catalog = try {
            json.decodeFromString(ModelCatalog.serializer(), text)
        } catch (e: SerializationException) {
            return CatalogResult.Failure("Malformed catalog: ${e.message}")
        } catch (e: IllegalArgumentException) {
            return CatalogResult.Failure("Invalid model entry: ${e.message}")
        }
        if (catalog.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            return CatalogResult.Failure(
                "Unsupported schemaVersion ${catalog.schemaVersion}; expected $SUPPORTED_SCHEMA_VERSION",
            )
        }
        val duplicates = catalog.models.groupBy { it.id }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) {
            return CatalogResult.Failure("Duplicate model ids: ${duplicates.sorted().joinToString()}")
        }
        return CatalogResult.Success(catalog)
    }

    fun encode(catalog: ModelCatalog): String = json.encodeToString(ModelCatalog.serializer(), catalog)
}
