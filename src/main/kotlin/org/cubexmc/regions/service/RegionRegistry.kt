package org.cubexmc.regions.service

import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.storage.RegionStorage

class RegionRegistry(
    private val storage: RegionStorage,
    private val validator: RegionValidationService,
) {
    fun all(): List<RegionDefinition> = storage.all()

    fun find(id: String): RegionDefinition? = storage.find(id)

    internal fun put(region: RegionDefinition): ServiceResult {
        val issues = validator.validate(region).filter { it.severity.name == "ERROR" }
        if (issues.isNotEmpty()) {
            return ServiceResult.failCoded(
                "region-validation-failed",
                mapOf("count" to issues.size.toString()),
                issues.joinToString("; ") { it.message },
            )
        }
        storage.put(region)
        return persistOrReload()
    }

    internal fun putSystem(region: RegionDefinition): ServiceResult {
        storage.put(region)
        return persistOrReload()
    }

    internal fun remove(id: String): ServiceResult {
        if (!storage.remove(id)) {
            return ServiceResult.failCoded("region-not-found", mapOf("id" to id), "Region not found: $id")
        }
        return persistOrReload()
    }

    private fun persistOrReload(): ServiceResult {
        if (storage.flushIfDirty()) return ServiceResult.ok()
        storage.load()
        return ServiceResult.failCoded(
            "persist-failed",
            diagnostic = "Failed to persist regions.yml; the previous on-disk state was restored.",
        )
    }
}
