package org.cubexmc.regions.service

import org.bukkit.Location
import org.cubexmc.regions.RegionsPlugin
import org.cubexmc.regions.capability.BuiltInRegionCapabilities
import org.cubexmc.regions.capability.CapabilityCatalog
import org.cubexmc.regions.capability.CapabilityDescriptor
import org.cubexmc.regions.capability.CapabilityKind
import org.cubexmc.regions.effect.ScopedEffectService
import org.cubexmc.regions.flag.RegionFlagRegistry
import org.cubexmc.regions.integration.RegionSource
import org.cubexmc.regions.integration.RegionSourceRegistry
import org.cubexmc.regions.mode.RegionModeRegistry
import org.cubexmc.regions.model.ExternalRegion
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionSourceRef
import org.cubexmc.regions.model.ValidationSeverity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import java.nio.file.Path
import java.util.UUID

/**
 * 内置模板必须真的能走到"发布"这一步。
 *
 * 此前只有"参数齐不齐""Mode 里有没有被校验直接判死的字段"这类局部断言，**没有**把模板套用后的
 * Region 丢进真正的 [RegionValidationService]。结果是：校验规则一收紧（例如出生点数量、点位必须
 * 在场内、人数范围），自带模板就可能悄悄变成"套用后卡在发布页"——插件自己的模板被插件自己的
 * 校验拦住。这条用例把每个模板 + 合法点位参数跑一遍完整校验。
 */
class ShippedTemplatePublishabilityTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `every shipped template survives the full publish validation once its locations are filled in`() {
        val service = loadShippedTemplates()
        val validation = validationForAllModes()
        val base = RegionDefinition("venue", "Venue", RegionSourceRef(TEST_SOURCE))

        val problems = ArrayList<String>()
        for (template in service.all()) {
            val locations = template.parameters.values.filter { it.type == TemplateParameterType.LOCATION }
            val supplied = locations.mapIndexed { index, parameter ->
                parameter.id to location(index)
            }.toMap()
            val applied = service.apply(template.id, base, supplied)
            if (!applied.success) {
                problems += "${template.id}: 套用失败 ${applied.errors}"
                continue
            }
            val region = requireNotNull(applied.region) { "${template.id} applied without a region" }
            val errors = validation.validate(region).filter { it.severity == ValidationSeverity.ERROR }
            for (error in errors) {
                problems += "${template.id}: ${error.code} ${error.args} (${error.fieldPath ?: "-"})"
            }
        }

        assertEquals(emptyList<String>(), problems, "内置模板套用后无法通过发布校验")
    }

    @Test
    fun `every shipped template uses a mode the runtime actually registers`() {
        val service = loadShippedTemplates()
        val modes = RegionModeRegistry().apply { RuntimeModes.forEach(::register) }

        for (template in service.all()) {
            val type = template.mode?.type ?: continue
            assertTrue(modes.isRegistered(type), "${template.id} uses unregistered mode $type")
        }
    }

    /** 与 `RegionsPlugin` 启动时注册的玩法清单一致（新增玩法时这里也要补，否则上面的用例会漏）。 */
    private val RuntimeModes = listOf(
        "free_event",
        "dual_pvp",
        "union_war",
        "free_for_all",
        "run_race",
        "boat_race",
        "horse_race",
        "hide_and_seek",
    )

    private fun loadShippedTemplates(): RegionTemplateService {
        val file = tempDir.resolve("shipped-templates.yml").toFile()
        val stream = requireNotNull(javaClass.getResourceAsStream("/templates.yml")) { "missing shipped templates.yml" }
        stream.use { input -> file.outputStream().use { input.copyTo(it) } }
        return RegionTemplateService(file).apply { load() }
    }

    private fun validationForAllModes(): RegionValidationService {
        val sources = RegionSourceRegistry().apply { register(AlwaysAvailableSource()) }
        val modes = RegionModeRegistry().apply { RuntimeModes.forEach(::register) }
        val flags = RegionFlagRegistry().apply { registerDefaults() }
        val effects = ScopedEffectService(mock(RegionsPlugin::class.java)).apply { registerDefaults() }
        val actions = RegionActionRegistry().apply { registerDefaults() }
        val conditions = RegionConditionRegistry().apply { registerDefaults() }
        val catalog = CapabilityCatalog().apply {
            BuiltInRegionCapabilities.registerAll(this)
            register(CapabilityDescriptor(CapabilityKind.SOURCE, TEST_SOURCE))
        }
        return RegionValidationService(sources, modes, flags, effects, actions, conditions, catalog)
    }

    /** 每个坐标参数给一个不同但合法的点位；同队点位分开，避免"重复点位"类校验误判。 */
    private fun location(index: Int): String = "arena,${12 + index * 8},64,-30"

    private class AlwaysAvailableSource : RegionSource {
        override val type: String = TEST_SOURCE

        override fun isAvailable(): Boolean = true

        override fun resolve(ref: RegionSourceRef): ExternalRegion = ExternalRegion(ref.describe(), "Test", type)

        override fun contains(ref: RegionSourceRef, location: Location): Boolean = true

        override fun getOwnedRegions(playerId: UUID): List<ExternalRegion> = emptyList()

        override fun isOwner(ref: RegionSourceRef, playerId: UUID): Boolean = true
    }

    private companion object {
        const val TEST_SOURCE = "test"
    }
}
