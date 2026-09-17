package org.cubexmc.regions.gui

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 详情页布局（PLAN.md §5.2）：基础页最多突出 **5** 个操作，技术项收进高级页。
 *
 * 这条约定以前只写在方案里，靠人肉记住别加按钮；现在由 [RegionDetailLayout] 的常数 + 本用例钉住：
 * 新增第 6 个基础操作、把标签键写错、或让两个页面的槽位互相覆盖都会失败。
 */
class RegionDetailLayoutTest {

    private val zh = load("/lang/zh_CN.yml")
    private val en = load("/lang/en_US.yml")

    @Test
    fun `the basic page highlights exactly five operations`() {
        assertEquals(5, RegionDetailLayout.BASIC_OPERATIONS.size, "基础页只能有 5 个操作")
        assertEquals(
            listOf("gui.detail.mode", "gui.detail.source", "gui.detail.publish", "gui.detail.manage", "gui.detail.trial-start"),
            RegionDetailLayout.BASIC_OPERATIONS.values.toList(),
        )
    }

    @Test
    fun `basic and advanced entries never collide on a slot`() {
        val basic = RegionDetailLayout.BASIC_OPERATIONS.keys
        val advanced = RegionDetailLayout.ADVANCED_ENTRIES.keys

        // 高级页是独立界面，但两页的槽位语义必须各自唯一，且导航槽不与操作槽重叠。
        assertEquals(basic.size, basic.distinct().size)
        assertEquals(advanced.size, advanced.distinct().size)
        assertTrue(RegionDetailLayout.ADVANCED_SLOT !in basic, "高级入口不能占用基础操作槽位")
        assertTrue(RegionDetailLayout.BACK_SLOT !in basic, "返回键不能占用基础操作槽位")
        assertTrue(RegionDetailLayout.INFO_SLOT !in basic, "信息格不能占用基础操作槽位")
    }

    @Test
    fun `every entry has a bilingual label`() {
        val keys = RegionDetailLayout.BASIC_OPERATIONS.values +
            RegionDetailLayout.ADVANCED_ENTRIES.values +
            listOf("gui.detail.advanced", "gui.advanced.title", "gui.advanced.back")

        for (key in keys) {
            // 按钮文案走 GuiText.item → `<key>.name`；纯文本按钮直接用 `<key>`。
            val candidates = listOf("$key.name", key)
            for ((locale, yaml) in listOf("zh_CN" to zh, "en_US" to en)) {
                assertTrue(
                    candidates.any { yaml.contains(it) },
                    "missing $key in $locale",
                )
            }
        }
    }

    private fun load(resource: String): YamlConfiguration {
        val stream = requireNotNull(javaClass.getResourceAsStream(resource)) { "missing resource $resource" }
        return java.io.InputStreamReader(stream, Charsets.UTF_8).use { YamlConfiguration.loadConfiguration(it) }
    }
}
