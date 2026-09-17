package org.cubexmc.regions.config

import org.bukkit.configuration.file.YamlConfiguration
import org.cubexmc.regions.capability.BuiltInRegionCapabilities
import org.cubexmc.regions.capability.CapabilityCatalog
import org.cubexmc.regions.capability.CapabilityKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M1.1 术语/键清单（Regions/PLAN.md §4.1）：所有内置能力、状态、启动方式与常用值
 * 在两个内置 locale 下都有显示标签；配置与命令内部继续使用稳定 ID。
 *
 * 能力清单直接来自 [BuiltInRegionCapabilities] 注册后的 [CapabilityCatalog]，
 * 与运行时注册表的一致性由 `RegionsPlugin.verifyCapabilityCatalog` 保证。
 */
class TermLabelsTest {

    private val zh = load("/lang/zh_CN.yml")
    private val en = load("/lang/en_US.yml")

    @Test
    fun `every built-in capability id has a display label in both locales`() {
        val catalog = CapabilityCatalog()
        BuiltInRegionCapabilities.registerAll(catalog)
        val sections = mapOf(
            CapabilityKind.SOURCE to "source",
            CapabilityKind.MODE to "mode",
            CapabilityKind.FLAG to "flag",
            CapabilityKind.EFFECT to "effect",
            CapabilityKind.ACTION to "action",
            CapabilityKind.CONDITION to "condition",
            CapabilityKind.TRIGGER to "trigger",
        )

        for ((kind, section) in sections) {
            val ids = catalog.all(kind).map { it.id }
            assertTrue(ids.isNotEmpty(), "no descriptors registered for $kind")
            for (id in ids) {
                for ((localeName, yaml) in listOf("zh_CN" to zh, "en_US" to en)) {
                    assertTrue(
                        yaml.contains("labels.$section.$id"),
                        "missing labels.$section.$id in $localeName",
                    )
                }
            }
        }
    }

    @Test
    fun `fixed term groups are present in both locales`() {
        val required = listOf(
            "labels.common.on",
            "labels.common.off",
            "labels.common.allow",
            "labels.common.deny",
            "labels.common.pass",
            "labels.start-mode.vote",
            "labels.start-mode.judge",
            "labels.severity.error",
            "labels.severity.warning",
            "labels.state.idle",
            "labels.state.waiting",
            "labels.state.running",
        )
        for (key in required) {
            assertTrue(zh.contains(key), "missing $key in zh_CN")
            assertTrue(en.contains(key), "missing $key in en_US")
        }
    }

    @Test
    fun `state placeholders are consistent across locales`() {
        for (key in listOf("labels.state.waiting", "labels.state.running")) {
            assertEquals(
                placeholders(zh.getString(key)),
                placeholders(en.getString(key)),
                "placeholder sets differ for $key",
            )
        }
    }

    private fun placeholders(value: String?): Set<String> =
        Regex("<([a-z-]+)>").findAll(value ?: "").mapTo(LinkedHashSet()) { it.groupValues[1] }

    private fun load(resource: String): YamlConfiguration {
        val stream = requireNotNull(javaClass.getResourceAsStream(resource)) { "missing resource $resource" }
        return java.io.InputStreamReader(stream, Charsets.UTF_8).use { YamlConfiguration.loadConfiguration(it) }
    }
}
