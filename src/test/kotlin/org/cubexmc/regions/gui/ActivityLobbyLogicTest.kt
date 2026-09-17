package org.cubexmc.regions.gui

import org.bukkit.configuration.file.YamlConfiguration
import org.cubexmc.regions.mode.GamePhase
import org.cubexmc.regions.mode.GameStatus
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionLifecycle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 活动大厅纯逻辑（PLAN.md M2.1 / §5.1）：分页规模、可报名判定与不可报名原因。
 * 判定输入全部显式传入，不触碰 Bukkit。
 */
class ActivityLobbyLogicTest {

    private fun region(
        id: String = "arena",
        type: String = "dual_pvp",
        values: Map<String, String> = emptyMap(),
        lifecycle: RegionLifecycle = RegionLifecycle.PUBLISHED,
    ): RegionDefinition = RegionDefinition(
        id = id,
        name = id,
        source = org.cubexmc.regions.model.RegionSourceRef("cuboid"),
        mode = org.cubexmc.regions.model.ModeConfig(type, values),
        lifecycle = lifecycle,
    )

    @Test
    fun `page count handles the 46th venue and beyond`() {
        assertEquals(1, ActivityLobbyLogic.pageCount(0))
        assertEquals(1, ActivityLobbyLogic.pageCount(45))
        assertEquals(2, ActivityLobbyLogic.pageCount(46))
        assertEquals(2, ActivityLobbyLogic.pageCount(90))
        assertEquals(3, ActivityLobbyLogic.pageCount(91))
    }

    @Test
    fun `filters keep the order and never drop venues when inactive`() {
        val entries = listOf(
            entry("duel", "dual_pvp", available = true),
            entry("war", "union_war", available = false),
            entry("brawl", "free_for_all", available = true),
        )

        assertEquals(entries, ActivityLobbyLogic.applyFilter(entries, LobbyFilter()))
        assertEquals(listOf("duel", "brawl"), ActivityLobbyLogic.applyFilter(entries, LobbyFilter(joinableOnly = true)).map { it.region.id })
        assertEquals(listOf("war"), ActivityLobbyLogic.applyFilter(entries, LobbyFilter(modeType = "union_war")).map { it.region.id })
        // 玩法 + 可报名叠加时两个条件都要满足。
        assertTrue(ActivityLobbyLogic.applyFilter(entries, LobbyFilter(modeType = "union_war", joinableOnly = true)).isEmpty())
    }

    @Test
    fun `mode filter cycles through the modes actually present and back to all`() {
        val modes = listOf("dual_pvp", "free_for_all", "union_war")

        var filter = LobbyFilter()
        filter = filter.cycleMode(modes)
        assertEquals("dual_pvp", filter.modeType)
        filter = filter.cycleMode(modes)
        assertEquals("free_for_all", filter.modeType)
        filter = filter.cycleMode(modes)
        assertEquals("union_war", filter.modeType)
        // 一轮之后回到"全部"。
        filter = filter.cycleMode(modes)
        assertNull(filter.modeType)
        assertFalse(filter.isActive)

        // 列表里没有玩法时不会造出一个筛不出来的值。
        assertNull(LobbyFilter(modeType = "dual_pvp").cycleMode(emptyList()).modeType)
    }

    @Test
    fun `available modes come from the cards and are stable`() {
        val entries = listOf(
            entry("duel", "dual_pvp", available = true),
            entry("duel2", "dual_pvp", available = false),
            entry("brawl", "free_for_all", available = true),
        )

        assertEquals(listOf("dual_pvp", "free_for_all"), ActivityLobbyLogic.availableModes(entries))
    }

    /** 可报名与否通过"是否处于上一局恢复窗口"表达，避免夹具自己造出与判定无关的输入。 */
    private fun entry(id: String, modeType: String, available: Boolean): LobbyEntry =
        ActivityLobbyLogic.entry(
            region(id = id, type = modeType),
            GameStatus(id, modeType, GamePhase.IDLE),
            ending = !available,
            sourceAvailable = true,
            sourceLabel = "cuboid",
            unionsAvailable = true,
        )

    @Test
    fun `a joinable venue has no reason`() {
        val entry = ActivityLobbyLogic.entry(
            region(), GameStatus("arena", "dual_pvp", GamePhase.WAITING, players = 1, ready = 1),
            ending = false, sourceAvailable = true, sourceLabel = "立方体场地", unionsAvailable = true,
        )
        assertTrue(entry.available)
        assertNull(entry.reasonKey)
    }

    @Test
    fun `unavailable venues always carry an explicit reason`() {
        fun reason(
            status: GameStatus = GameStatus("arena", "dual_pvp", GamePhase.WAITING),
            ending: Boolean = false,
            sourceAvailable: Boolean = true,
            unions: Boolean = true,
            type: String = "dual_pvp",
        ) = ActivityLobbyLogic.entry(region(type = type), status, ending, sourceAvailable, "Lands", unions).reasonKey

        assertEquals("gui.lobby.reason.source-unavailable", reason(sourceAvailable = false))
        assertEquals("gui.lobby.reason.unions-unavailable", reason(unions = false, type = "union_war"))
        assertEquals("gui.lobby.reason.restoring", reason(ending = true))
        assertEquals("gui.lobby.reason.running", reason(status = GameStatus("arena", "dual_pvp", GamePhase.RUNNING, players = 2)))
        // 满员原因依赖 max-players 设置，单独在 full 用例里验证。
    }

    @Test
    fun `full only applies when max-players is set and reached`() {
        val status = GameStatus("arena", "dual_pvp", GamePhase.WAITING, players = 2)
        val full = ActivityLobbyLogic.entry(
            region(values = mapOf("max-players" to "2")), status,
            ending = false, sourceAvailable = true, sourceLabel = "Lands", unionsAvailable = true,
        )
        assertFalse(full.available)
        assertEquals("gui.lobby.reason.full", full.reasonKey)

        val cappedButBelow = ActivityLobbyLogic.entry(
            region(values = mapOf("max-players" to "4")), status,
            ending = false, sourceAvailable = true, sourceLabel = "Lands", unionsAvailable = true,
        )
        assertTrue(cappedButBelow.available)

        val unlimited = ActivityLobbyLogic.entry(
            region(), status,
            ending = false, sourceAvailable = true, sourceLabel = "Lands", unionsAvailable = true,
        )
        assertTrue(unlimited.available)
    }

    @Test
    fun `restoring outranks nothing but source and unions come first`() {
        // 依赖缺失优先于其余原因：玩家要先装依赖，恢复/满员是临时态。
        val entry = ActivityLobbyLogic.entry(
            region(), GameStatus("arena", "dual_pvp", GamePhase.RUNNING, players = 2),
            ending = true, sourceAvailable = false, sourceLabel = "Lands", unionsAvailable = false,
        )
        assertEquals("gui.lobby.reason.source-unavailable", entry.reasonKey)
    }
}
