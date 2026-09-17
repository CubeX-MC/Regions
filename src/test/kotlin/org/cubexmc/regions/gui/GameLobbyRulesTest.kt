package org.cubexmc.regions.gui

import org.cubexmc.regions.model.ModeConfig
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionSourceRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** M2.4：报名页单页规则摘要的纯逻辑——装备条款、单命/回合/时限与最低人数。 */
class GameLobbyRulesTest {

    private fun region(type: String, values: Map<String, String> = emptyMap()): RegionDefinition =
        RegionDefinition(
            id = "arena",
            name = "arena",
            source = RegionSourceRef("cuboid"),
            mode = ModeConfig(type, values),
        )

    @Test
    fun `kit or replace-gear promises gear escrow`() {
        for (values in listOf(
            mapOf("replace-gear" to "true"),
            mapOf("kit" to "stone_sword:1"),
            mapOf("armor" to "iron_chestplate:1"),
        )) {
            val keys = GameLobbyRules.ruleLines(region("dual_pvp", values)).map { it.first }
            assertTrue("gui.game.rules.gear-escrow" in keys, values.toString())
            assertFalse("gui.game.rules.gear-own" in keys)
        }
    }

    @Test
    fun `no kit means players keep their own gear`() {
        val keys = GameLobbyRules.ruleLines(region("dual_pvp")).map { it.first }
        assertTrue("gui.game.rules.gear-own" in keys)
    }

    @Test
    fun `life and round rules follow the mode`() {
        assertTrue(
            GameLobbyRules.ruleLines(region("dual_pvp")).any { it.first == "gui.game.rules.duel-life" },
        )
        assertTrue(
            GameLobbyRules.ruleLines(region("union_war")).any { it.first == "gui.game.rules.nation-life" },
        )
        val round = GameLobbyRules.ruleLines(region("hide_and_seek", mapOf("round-seconds" to "120")))
        assertEquals("gui.game.rules.round" to mapOf("seconds" to "120"), round.last { it.first == "gui.game.rules.round" })
    }

    @Test
    fun `races advertise their timeout and min players`() {
        val lines = GameLobbyRules.ruleLines(region("run_race", mapOf("timeout-seconds" to "180", "min-players" to "2")))
        assertTrue("gui.game.rules.timeout" to mapOf("seconds" to "180") in lines)
        assertTrue("gui.game.rules.min-players" to mapOf("count" to "2") in lines)
    }

    @Test
    fun `free event has no ready action`() {
        assertFalse(GameLobbyRules.hasReady("free_event"))
        assertTrue(GameLobbyRules.hasReady("dual_pvp"))
        assertTrue(GameLobbyRules.hasReady("hide_and_seek"))
    }
}
