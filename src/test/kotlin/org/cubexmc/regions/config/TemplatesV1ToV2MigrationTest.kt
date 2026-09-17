package org.cubexmc.regions.config

import org.cubexmc.config.MigrationRunner
import org.cubexmc.core.CubexPlugin
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File
import java.nio.file.Path
import java.util.logging.Logger

/**
 * templates.yml 1→2 迁移（PLAN.md §4.3/§8.5）：内置模板精确匹配历史默认才转语言键，
 * 自定义模板与自定义文本原样保留，重复启动幂等。
 */
class TemplatesV1ToV2MigrationTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `builtin template texts become keys and custom texts stay`() {
        val file = templatesFile()
        file.writeText(
            """
            templates-version: 1
            templates:
              mini_kingdom:
                name: "小人国"
                description: "玩家进入后缩小，禁飞、禁隐身、禁 PVP。"
                mode:
                  type: free_event
                triggers:
                  on_enter:
                    - name: "入场提示"
                      then:
                        - type: title
                          title: "&a小人国"
                          subtitle: "&7离开区域后自动恢复"
              my_custom:
                name: "我的场地"
                description: "自定义描述"
                triggers:
                  on_enter:
                    - then:
                        - type: broadcast
                          text: "完全自定义的文本"
              half_custom:
                name: "小人国"
                description: "改过的描述"
            """.trimIndent(),
        )

        val report = runner().run(RegionBaseline.plans().first { it.resourcePath() == "templates.yml" })

        assertTrue(report.migrated())
        val yaml = reload(file)
        assertEquals(2, yaml.getInt("templates-version"))
        val mini = yaml.getConfigurationSection("templates.mini_kingdom")!!
        assertEquals("templates.mini_kingdom.name", mini.getString("name-key"))
        assertNull(mini.getString("name"), "matched literal name must be removed")
        assertEquals("templates.mini_kingdom.description", mini.getString("description-key"))
        val action = mini.getMapList("triggers.on_enter").single()["then"] as List<*>
        @Suppress("UNCHECKED_CAST")
        val title = (action.single() as Map<String, String>)
        assertNull(title["title"])
        assertEquals("templates.mini_kingdom.enter.title", title["title-key"])
        assertEquals("templates.mini_kingdom.enter.subtitle", title["subtitle-key"])
        // 自定义模板与自定义文本不受影响。
        val custom = yaml.getConfigurationSection("templates.my_custom")!!
        assertEquals("我的场地", custom.getString("name"))
        val customAction = custom.getMapList("triggers.on_enter").single()["then"] as List<*>
        assertEquals("完全自定义的文本", (customAction.single() as Map<*, *>)["text"])
        // 非内置模板 ID 即使复用了默认文案也整体保留：迁移只针对随插件发布的内置模板本体。
        val half = yaml.getConfigurationSection("templates.half_custom")!!
        assertEquals("小人国", half.getString("name"))
        assertEquals("改过的描述", half.getString("description"))
    }

    @Test
    fun `repeated startup is idempotent`() {
        val file = templatesFile()
        file.writeText("templates-version: 2\ntemplates: {}\n")
        val before = file.readText()

        val report = runner().run(RegionBaseline.plans().first { it.resourcePath() == "templates.yml" })

        assertTrue(report.skipped())
        assertEquals(before, file.readText())
    }

    private fun templatesFile(): File {
        val file = File(tempDir.toFile(), "templates.yml")
        file.parentFile.mkdirs()
        return file
    }

    private fun runner(): MigrationRunner {
        val plugin = mock(CubexPlugin::class.java)
        `when`(plugin.dataFolder).thenReturn(tempDir.toFile())
        `when`(plugin.logger).thenReturn(Logger.getLogger("TemplatesV1ToV2MigrationTest"))
        return MigrationRunner(plugin)
    }

    private fun reload(file: File) = YamlConfiguration.loadConfiguration(file)
}
