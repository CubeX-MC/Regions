package org.cubexmc.regions.gui

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataContainer
import org.cubexmc.core.CubexLogger
import org.cubexmc.core.CubexText
import org.cubexmc.regions.RegionsPlugin
import org.cubexmc.regions.config.LanguageManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.`when`
import org.mockito.Mockito.argThat
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import java.io.File
import java.nio.file.Path
import java.util.logging.Logger

/**
 * M1.4：GUI 文案必须按**正在看这块菜单的玩家**的 locale 渲染（PLAN.md §4.2）。
 *
 * 这里用真的 [LanguageManager]（真 `I18nService`、真的双语资源文件），只把 Bukkit 侧的
 * [RegionsPlugin] / [Player] 打成 mock：两个玩家的客户端语言分别是 `zh_CN` 与 `en_US`，
 * `locale-mode: player` 下同一个键必须渲染出两种不同的字符串。断言比对的是实际渲染结果，
 * 不是"调用过某个方法"，所以如果哪天 GUI 又退回服务器语言，这些用例会红。
 */
class GuiTextLocaleTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var plugin: RegionsPlugin
    private lateinit var lang: LanguageManager
    private lateinit var text: GuiText

    @BeforeEach
    fun setUp() {
        copyLocale("zh_CN")
        copyLocale("en_US")

        plugin = mock(RegionsPlugin::class.java)
        `when`(plugin.config).thenReturn(
            YamlConfiguration().apply {
                // 服务器语言是中文：英文玩家看到英文，只可能是按 viewer locale 解析出来的。
                set("language", "zh_CN")
                set("locale-mode", "player")
            },
        )
        `when`(plugin.dataFolder).thenReturn(tempDir.toFile())
        `when`(plugin.log()).thenReturn(CubexLogger(Logger.getLogger("GuiTextLocaleTest")))
        `when`(plugin.logger).thenReturn(Logger.getLogger("GuiTextLocaleTest"))
        `when`(plugin.text()).thenReturn(CubexText())
        // NamespacedKey(plugin, "locale") 走 Plugin#namespace()，mock 上默认是 null。
        `when`(plugin.name).thenReturn("Regions")
        `when`(plugin.namespace()).thenReturn("regions")

        lang = LanguageManager(plugin)
        `when`(plugin.lang()).thenReturn(lang)
        lang.reload()

        text = GuiText(plugin)
    }

    @Test
    fun `a mode label renders in each viewer's own locale`() {
        val chinese = player("zh_CN")
        val english = player("en_US")

        val zh = text.text(chinese, "labels.mode.dual_pvp")
        val en = text.text(english, "labels.mode.dual_pvp")

        assertEquals("双人决斗", zh)
        assertEquals("Duel", en)
        assertNotEquals(zh, en, "两个玩家的语言不同，渲染结果却一样")
    }

    @Test
    fun `validation translates legacy field arguments without a field path`() {
        val args = mapOf("field" to "respawn")
        assertTrue(lang.issueLine("location-required", args).contains("返回点"))
        assertTrue(lang.issueLineFor(player("zh_CN"), "location-required", args).contains("返回点"))
        assertTrue(lang.issueLineFor(player("en_US"), "location-required", args).contains("Respawn point"))
    }

    @Test
    fun `lore, components and boolean labels follow the viewer too`() {
        val chinese = player("zh_CN")
        val english = player("en_US")
        val placeholders = mapOf(
            "id" to "arena",
            "source" to "lands",
            "lifecycle" to "draft",
            "revision" to "1",
            "mode" to "free_event",
            "flags" to "0",
            "effects" to "0",
        )

        val zhLore = text.lore(chinese, "gui.item.region.lore", placeholders)
        val enLore = text.lore(english, "gui.item.region.lore", placeholders)

        assertEquals(6, zhLore.size)
        assertEquals(6, enLore.size)
        assertTrue(zhLore.any { it.contains("玩法: free_event") }, "中文玩家的 lore 里没有中文标签: $zhLore")
        assertTrue(enLore.any { it.contains("Mode: free_event") }, "英文玩家的 lore 里没有英文标签: $enLore")

        val zhTitle = plain(text.component(chinese, "gui.lobby.title", mapOf("page" to "1", "pages" to "1")))
        val enTitle = plain(text.component(english, "gui.lobby.title", mapOf("page" to "1", "pages" to "1")))
        assertTrue(zhTitle.contains("活动大厅"), "中文玩家的标题不是中文: $zhTitle")
        assertTrue(enTitle.contains("Activity lobby"), "英文玩家的标题不是英文: $enTitle")

        assertEquals("开启", stripColors(text.boolDisplay(chinese, "true")))
        assertEquals("On", stripColors(text.boolDisplay(english, "true")))
    }

    @Test
    fun `send hands the viewer's own locale to the recipient`() {
        val chinese = player("zh_CN")
        val english = player("en_US")

        text.send(chinese, "gui.common.back")
        text.send(english, "gui.common.back")

        verify(chinese).sendMessage(argThat<Component> { plain(it).contains("返回") })
        verify(english).sendMessage(argThat<Component> { plain(it).contains("Back") })
    }

    @Test
    fun `the helpers resolve through the per-viewer entry points`() {
        // 机制层面的兜底断言：GuiText 必须把 viewer 原样交给 messageFor/componentFor/messageListFor，
        // 而不是解析成服务器语言。只有用 mock 的 LanguageManager 才看得见这次委托。
        val viewer = player("en_US")
        val resolver = mock(LanguageManager::class.java)
        val stub = mock(RegionsPlugin::class.java)
        `when`(stub.lang()).thenReturn(resolver)
        `when`(resolver.messageFor(viewer, "gui.common.back", emptyMap())).thenReturn("back")
        `when`(resolver.componentFor(viewer, "gui.lobby.title", emptyMap())).thenReturn(Component.text("title"))
        `when`(resolver.messageListFor(viewer, "gui.item.region.lore", emptyMap())).thenReturn(listOf("line"))

        val guiText = GuiText(stub)

        assertEquals("back", guiText.text(viewer, "gui.common.back"))
        assertEquals("title", plain(guiText.component(viewer, "gui.lobby.title")))
        assertEquals(listOf("line"), guiText.lore(viewer, "gui.item.region.lore"))

        verify(resolver).messageFor(viewer, "gui.common.back", emptyMap())
        verify(resolver).componentFor(viewer, "gui.lobby.title", emptyMap())
        verify(resolver).messageListFor(viewer, "gui.item.region.lore", emptyMap())
    }

    /** 玩家侧的 mock：`locale`（客户端语言）决定解析结果，PDC 为空表示没有手动选择。 */
    private fun player(clientLocale: String): Player {
        val player = mock(Player::class.java)
        @Suppress("DEPRECATION")
        `when`(player.locale).thenReturn(clientLocale)
        `when`(player.persistentDataContainer).thenReturn(mock(PersistentDataContainer::class.java))
        return player
    }

    private fun copyLocale(locale: String) {
        val stream = requireNotNull(javaClass.getResourceAsStream("/lang/$locale.yml")) { "missing /lang/$locale.yml" }
        val target = File(tempDir.toFile(), "lang/$locale.yml")
        target.parentFile.mkdirs()
        stream.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
    }

    private fun plain(component: Component): String = PlainTextComponentSerializer.plainText().serialize(component)

    /** i18n 渲染结果是 legacy `§` 串，去掉颜色码再比对文字。 */
    private fun stripColors(rendered: String): String = rendered.replace(Regex("§."), "")
}
