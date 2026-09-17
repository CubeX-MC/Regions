package org.cubexmc.regions.config

import org.cubexmc.config.MigrationException
import org.cubexmc.config.MigrationRunner
import org.cubexmc.core.CubexPlugin
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File
import java.nio.file.Path
import java.util.logging.Logger

/**
 * 语言 6→7 迁移的行为覆盖（[Regions/PLAN.md](../../../../../../PLAN.md) M0.1）：
 * 双语默认值、服主自定义值与未知键保留、lore 结构转换、自定义 lore 告警、
 * 损坏 YAML 不被改写、写入/备份失败不落版本号、重复启动幂等。
 */
class LangV6ToV7MigrationTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `zh v6 file gets the new keys, keeps custom values and unknown keys`() {
        val file = langFile("zh_CN")
        file.writeText(
            """
            lang-version: 6
            prefix: "<#69DB7C>[Regions] "
            custom-top-level: 保留我
            gui:
              detail:
                other-key: "<red>自定义内容"
                apply-template:
                  name: "<yellow>我的自定义按钮名"
              template:
                entry:
                  name: "<aqua><name>"
                  lore:
                    - "<gray><description>"
                    - "<dark_gray>将应用以下配置："
                    - "<gray>Mode: <mode>"
                    - "<gray>Flags: <flags>"
                    - "<gray>Effects: <effects>"
                    - "<gray>Triggers: <triggers>"
                    - "<green>点击验证并创建场地。"
                failed: "<red>自定义失败提示"
            """.trimIndent(),
        )

        val report = runMigration("lang/zh_CN.yml")

        assertTrue(report.migrated())
        assertEquals(6, report.fromVersion())
        val yaml = reload(file)
        assertEquals(8, yaml.getInt("lang-version"), "6→7→8 的链要一路走完")
        // lore 的最后一行拆成了 create-hint，其余行不动。
        val lore = yaml.getStringList("gui.template.entry.lore")
        assertEquals(6, lore.size)
        assertEquals("<gray>Triggers: <triggers>", lore.last())
        assertEquals("<green>点击验证并创建场地。", yaml.getString("gui.template.entry.create-hint"))
        assertEquals("<yellow>点击检查并替换当前配置。", yaml.getString("gui.template.entry.apply-hint"))
        assertEquals("<red>返回领地详情", yaml.getString("gui.template.back-to-detail"))
        // 自定义值与未知键原样保留。
        assertEquals("<yellow>我的自定义按钮名", yaml.getString("gui.detail.apply-template.name"))
        assertEquals("<red>自定义内容", yaml.getString("gui.detail.other-key"))
        assertEquals("<red>自定义失败提示", yaml.getString("gui.template.failed"))
        assertEquals("保留我", yaml.getString("custom-top-level"))
        assertEquals("<#69DB7C>[Regions] ", yaml.getString("prefix"))
        // 缺的键按历史默认补齐。
        assertEquals(
            listOf("<gray>为这个领地重新选择一套完整场地预设。", "<yellow>确认后会整体替换 Mode、Flags、Effects 和 Triggers。"),
            yaml.getStringList("gui.detail.apply-template.lore"),
        )
        assertEquals("<dark_green>应用模板: <id>", yaml.getString("gui.template.confirm.title"))
        assertEquals("<aqua>新预设: <name>", yaml.getString("gui.template.confirm.summary.name"))
        assertEquals(
            listOf("<gray>Mode: <mode>", "<gray>Flags: <flags>", "<gray>Effects: <effects>", "<gray>Triggers: <triggers>"),
            yaml.getStringList("gui.template.confirm.summary.lore"),
        )
        assertEquals("<red>替换当前玩法配置", yaml.getString("gui.template.confirm.warning.name"))
        assertEquals(
            listOf("<red>当前 Mode、Flags、Effects 和 Triggers 会被清空。", "<gray>领地 ID、来源、主人、优先级和历史版本保持不变。"),
            yaml.getStringList("gui.template.confirm.warning.lore"),
        )
        assertEquals("<green>确认并保存为草稿", yaml.getString("gui.template.confirm.apply.name"))
        assertEquals(
            listOf("<gray>在预览并发布这个草稿前，线上场地不会改变。"),
            yaml.getStringList("gui.template.confirm.apply.lore"),
        )
    }

    @Test
    fun `en v6 file is converted with the english defaults`() {
        val file = langFile("en_US")
        file.writeText(
            """
            lang-version: 6
            gui:
              template:
                entry:
                  lore:
                    - "<gray><description>"
                    - "<dark_gray>Applies the following configuration:"
                    - "<gray>Mode: <mode>"
                    - "<gray>Flags: <flags>"
                    - "<gray>Effects: <effects>"
                    - "<gray>Triggers: <triggers>"
                    - "<green>Click to validate and create the venue."
            """.trimIndent(),
        )

        val report = runMigration("lang/en_US.yml")

        assertTrue(report.migrated())
        val yaml = reload(file)
        assertEquals(8, yaml.getInt("lang-version"))
        assertEquals(6, yaml.getStringList("gui.template.entry.lore").size)
        assertEquals("<green>Click to validate and create the venue.", yaml.getString("gui.template.entry.create-hint"))
        assertEquals("<yellow>Click to review replacing the current configuration.", yaml.getString("gui.template.entry.apply-hint"))
        assertEquals("<red>Back to region details", yaml.getString("gui.template.back-to-detail"))
        assertEquals("<yellow>Apply a template", yaml.getString("gui.detail.apply-template.name"))
        assertEquals("<dark_green>Apply template: <id>", yaml.getString("gui.template.confirm.title"))
        assertEquals("<green>Confirm and save as draft", yaml.getString("gui.template.confirm.apply.name"))
    }

    @Test
    fun `a custom entry lore list is kept and explained instead of transformed`() {
        val file = langFile("zh_CN")
        file.writeText(
            """
            lang-version: 6
            gui:
              template:
                entry:
                  lore:
                    - "<gray>我的自定义描述"
                    - "<green>点我创建！"
            """.trimIndent(),
        )

        val report = runMigration("lang/zh_CN.yml")

        assertTrue(report.migrated())
        // 6→7 的自定义 lore 说明 + 7→8 的补键摘要。
        assertEquals(2, report.warnings().size)
        assertTrue(
            report.warnings().any { it.contains("gui.template.entry.lore") },
            "缺少自定义 lore 的说明：${report.warnings()}",
        )
        val yaml = reload(file)
        assertEquals(listOf("<gray>我的自定义描述", "<green>点我创建！"), yaml.getStringList("gui.template.entry.lore"))
        // 新键仍然补齐：GUI 会用到它们，语言服务也有内置回退。
        assertEquals("<green>点击验证并创建场地。", yaml.getString("gui.template.entry.create-hint"))
        assertEquals("<yellow>点击检查并替换当前配置。", yaml.getString("gui.template.entry.apply-hint"))
    }

    @Test
    fun `a v6 file with no gui sections gets every new key`() {
        val file = langFile("zh_CN")
        file.writeText("lang-version: 6\n")

        val report = runMigration("lang/zh_CN.yml")

        assertTrue(report.migrated())
        // 7→8 会写一条"补了 N 个键"的摘要；这里要保证的是没有"保留/冲突"类警告。
        assertTrue(
            report.warnings().none { it.contains("kept as-is") || it.contains("已保留") },
            "空文件不该产生保留类警告：${report.warnings()}",
        )
        val yaml = reload(file)
        assertEquals(8, yaml.getInt("lang-version"))
        assertEquals("<yellow>应用模板", yaml.getString("gui.detail.apply-template.name"))
        assertEquals("<green>点击验证并创建场地。", yaml.getString("gui.template.entry.create-hint"))
        assertEquals("<yellow>点击检查并替换当前配置。", yaml.getString("gui.template.entry.apply-hint"))
        assertEquals("<red>返回领地详情", yaml.getString("gui.template.back-to-detail"))
        assertEquals("<dark_green>应用模板: <id>", yaml.getString("gui.template.confirm.title"))
    }

    @Test
    fun `a v7 file receives the keys added after v7 and keeps everything else`() {
        val file = langFile("zh_CN")
        file.writeText(
            """
            lang-version: 7
            prefix: "kept"
            labels:
              state:
                idle: "我的自定义空闲"
            custom-top-level: "保留我"
            """.trimIndent(),
        )

        val report = runMigration("lang/zh_CN.yml")

        assertTrue(report.migrated())
        val yaml = YamlConfiguration().apply { load(file) }
        assertEquals(8, yaml.getInt("lang-version"))
        // 服主写过的值与未知键原样保留。
        assertEquals("kept", yaml.getString("prefix"))
        assertEquals("我的自定义空闲", yaml.getString("labels.state.idle"))
        assertEquals("保留我", yaml.getString("custom-top-level"))
        // v7 之后新增的键按同语言内置文本补齐（错误码词典是最典型的一批）。
        assertTrue(yaml.isSet("labels.state.waiting"))
        assertTrue(yaml.isSet("labels.severity.error"))
        assertTrue(yaml.isSet("errors.funding-unavailable"))
        assertTrue(yaml.isSet("game.match.join.ok"))
    }

    @Test
    fun `a v7 file that already has every key is left untouched`() {
        val file = langFile("zh_CN")
        val shipped = requireNotNull(javaClass.getResourceAsStream("/lang/zh_CN.yml")).use {
            YamlConfiguration.loadConfiguration(it.reader(Charsets.UTF_8))
        }
        shipped.save(file)
        val before = file.readText()

        val report = runMigration("lang/zh_CN.yml")

        assertTrue(report.skipped(), "已经是目标版本的文件不该再迁移")
        assertEquals(before, file.readText())
    }

    @Test
    fun `repeated startup is idempotent`() {
        val file = langFile("zh_CN")
        file.writeText("lang-version: 6\n")
        runMigration("lang/zh_CN.yml")
        val afterFirst = file.readText()

        val second = runMigration("lang/zh_CN.yml")

        assertTrue(second.skipped())
        assertEquals(afterFirst, file.readText())
    }

    @Test
    fun `a corrupt yaml file is not migrated or rewritten`() {
        val file = langFile("zh_CN")
        val corrupt = "lang-version: 6\n{{{ 不是合法 YAML ::["
        file.writeText(corrupt)
        // Bukkit 的加载器在解析失败时通过 Bukkit.getLogger() 报告；单测里没有 server，
        // 反射挂一个带 logger 的 mock，让"损坏 → 跳过"这条路径可以走到断言。
        // （不能走 Bukkit.setServer：它内部读取 ServerBuildInfo，单测 classpath 下必然抛错。）
        installBukkitServer(Logger.getLogger("LangV6ToV7MigrationTest-corrupt"))
        try {
            val report = runMigration("lang/zh_CN.yml")

            // 版本键缺失按"新文件"处理，因此迁移整体跳过、原文件不动；
            // 服主修好语法后下一次启动会按 v6 正常迁移。
            assertTrue(report.skipped())
            assertEquals(corrupt, file.readText())
        } finally {
            bukkitServerField().set(null, null)
        }
    }

    private fun installBukkitServer(logger: Logger) {
        val server = mock(org.bukkit.Server::class.java)
        `when`(server.logger).thenReturn(logger)
        bukkitServerField().set(null, server)
    }

    /** `Bukkit.setServer` 的参数标注为非空且依赖 ServerBuildInfo，只能直接操作静态字段。 */
    private fun bukkitServerField(): java.lang.reflect.Field =
        org.bukkit.Bukkit::class.java.getDeclaredField("server").apply { isAccessible = true }

    @Test
    fun `a failed migration leaves version and original file untouched`() {
        val file = langFile("zh_CN")
        file.writeText(
            """
            lang-version: 6
            gui:
              template:
                entry:
                  lore:
                    - "<gray><description>"
                    - "<green>点击验证并创建场地。"
            """.trimIndent(),
        )
        val before = file.readText()
        // 备份根被一个同名文件占住，MigrationRunner 在改动任何文件之前就会失败。
        File(dataFolder(), "backups").writeText("not a directory")

        val failure = runCatching { runMigration("lang/zh_CN.yml") }.exceptionOrNull()

        assertTrue(failure is MigrationException, "expected a MigrationException but got $failure")
        assertEquals(before, file.readText())
        assertEquals(6, reload(file).getInt("lang-version", -1))
    }

    private fun dataFolder(): File = tempDir.toFile()

    private fun langFile(locale: String): File {
        val file = File(dataFolder(), "lang/$locale.yml")
        file.parentFile.mkdirs()
        return file
    }

    private fun runMigration(resourcePath: String) = runner().run(planFor(resourcePath))

    private fun planFor(resourcePath: String) =
        RegionBaseline.plans().first { it.resourcePath() == resourcePath }

    private fun runner(): MigrationRunner {
        val plugin = mock(CubexPlugin::class.java)
        `when`(plugin.dataFolder).thenReturn(dataFolder())
        `when`(plugin.logger).thenReturn(Logger.getLogger("LangV6ToV7MigrationTest"))
        return MigrationRunner(plugin)
    }

    private fun reload(file: File) = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file)
}
