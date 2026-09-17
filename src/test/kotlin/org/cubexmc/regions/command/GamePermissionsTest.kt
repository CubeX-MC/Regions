package org.cubexmc.regions.command

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M2.5 权限落点（PLAN.md §5.4）：普通参与叶节点由 `regions.use` 携带，管理叶节点
 * （start/end）只挂在 `regions.admin` 下、不随 `regions.use` 获得；
 * 旧权限名与行为入口全部保留。
 */
class GamePermissionsTest {

    private val yaml: YamlConfiguration by lazy {
        val stream = requireNotNull(javaClass.getResourceAsStream("/plugin.yml")) { "missing plugin.yml" }
        java.io.InputStreamReader(stream, Charsets.UTF_8).use { YamlConfiguration.loadConfiguration(it) }
    }

    @Test
    fun `normal participation leaves ride on regions use`() {
        val children = yaml.getConfigurationSection("permissions.regions.use.children")
        for (leaf in listOf(
            "regions.game.view",
            "regions.game.join",
            "regions.game.ready",
            "regions.game.spectate",
            "regions.language.select",
        )) {
            assertEquals(true, children?.getBoolean(leaf, false), leaf)
        }
    }

    @Test
    fun `management leaves are admin children, not use children`() {
        val useChildren = yaml.getConfigurationSection("permissions.regions.use.children")
        for (leaf in listOf("regions.game.start", "regions.game.end")) {
            assertEquals(false, useChildren?.getBoolean(leaf, false), "$leaf must not ride on regions.use")
        }
        val adminChildren = yaml.getConfigurationSection("permissions.regions.admin.children")
        for (leaf in listOf("regions.game.start", "regions.game.end")) {
            assertEquals(true, adminChildren?.getBoolean(leaf, false), leaf)
        }
    }

    /**
     * 声明了却没人检查的权限是**假承诺**：服主拿 `plugin.yml` 配权限插件，
     * 会以为那个能力已经存在（f87d915 已经为此清过一轮死节点）。
     * 本用例把叶节点集合钉死，新增一个就必须同时改这里——
     * M3 接通报名与观战时再把 `regions.game.join` / `regions.game.spectate` 加回来。
     */
    @Test
    fun `declared game leaves match the ones the code actually checks`() {
        // YamlConfiguration 把 '.' 当路径分隔符，所以 `regions.game.view:` 落在 permissions.regions.game 下。
        val declared = yaml.getConfigurationSection("permissions.regions.game")?.getKeys(false).orEmpty().toSet()
        assertEquals(setOf("view", "join", "ready", "spectate", "start", "end"), declared)
    }

    @Test
    fun `every declared game leaf has its own node, not only a children entry`() {
        val permissions = requireNotNull(yaml.getConfigurationSection("permissions"))
        for (leaf in listOf(
            "regions.game.view",
            "regions.game.join",
            "regions.game.ready",
            "regions.game.spectate",
        )) {
            assertTrue(
                permissions.isConfigurationSection(leaf),
                "a node the command checks must be declared, not just listed under regions.use children",
            )
        }
    }

    @Test
    fun `permission select leaf is declared, not only referenced as a child`() {
        val permissions = requireNotNull(yaml.getConfigurationSection("permissions"))
        assertTrue(permissions.isConfigurationSection("regions.language.select"),
            "a node the command checks must be declared, not just listed under regions.use children")
    }

    @Test
    fun `legacy names stay declared`() {
        val permissions = yaml.getConfigurationSection("permissions")
        for (name in listOf("regions.use", "regions.admin", "regions.superadmin")) {
            assertTrue(permissions?.contains(name) == true, name)
        }
    }
}
