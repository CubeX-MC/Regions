package org.cubexmc.regions.gui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 玩法页的三块标签（2026-09-19 用户确认拆分）。
 *
 * 关键不变量：**每个可见的设置槽位恰好属于一块标签**。漏掉一个，那个设置就从界面上
 * 消失了却仍在配置里生效；分到两块则会在两处显示同一个值——两种都是实机上最难查的那类。
 */
class ModeTabsTest {

    private val modes = listOf(
        "free_event", "run_race", "boat_race", "horse_race",
        "hide_and_seek", "dual_pvp", "union_war", "free_for_all",
    )

    @Test
    fun `every configurable slot of every mode lands in exactly one tab`() {
        for (mode in modes) {
            val visible = GuiSlots.modeConfiguration(mode)
            val byTab = ModeTab.entries.associateWith { ModeTabs.slotsFor(mode, it) }
            val covered = byTab.values.flatten()
            assertEquals(
                visible.sorted(),
                covered.sorted(),
                "$mode 的槽位没有全部分到标签里（漏掉的设置会从界面消失）",
            )
            assertEquals(covered.size, covered.toSet().size, "$mode 有槽位被分进了两块标签")
        }
    }

    @Test
    fun `combat modes put their spawn points in the spawns tab`() {
        for (mode in listOf("dual_pvp", "union_war", "free_for_all")) {
            assertEquals(ModeTab.SPAWNS, ModeTabs.tabOf(mode, 45), mode)
            assertEquals(ModeTab.SPAWNS, ModeTabs.tabOf(mode, 46), mode)
            assertEquals(ModeTab.FORMAT, ModeTabs.tabOf(mode, 47), mode)
        }
        assertEquals(ModeTab.SPAWNS, ModeTabs.tabOf("union_war", 51))
        assertEquals(ModeTab.FORMAT, ModeTabs.tabOf("union_war", 26), "对阵属于赛制")
        assertEquals(ModeTab.FORMAT, ModeTabs.tabOf("union_war", 24), "外交前置属于赛制")
    }

    @Test
    fun `hide and seek keeps its own meaning for the shared slots`() {
        // 45/46/47 在捉迷藏下是寻找者/躲藏时长/回合时长，属于赛制而不是点位。
        for (slot in listOf(45, 46, 47, 50)) {
            assertEquals(ModeTab.FORMAT, ModeTabs.tabOf("hide_and_seek", slot), slot.toString())
        }
    }

    @Test
    fun `races put start finish and checkpoints in the spawns tab`() {
        for (slot in listOf(36, 37, 38, 39)) {
            assertEquals(ModeTab.SPAWNS, ModeTabs.tabOf("run_race", slot), slot.toString())
        }
        assertEquals(ModeTab.BASIC, ModeTabs.tabOf("run_race", 24), "载具检查是基础参数")
    }

    @Test
    fun `an empty tab is not offered`() {
        // free_event 没有任何玩法参数，三块都不该出现标签按钮。
        for (tab in ModeTab.entries) {
            assertFalse(ModeTabs.hasContent("free_event", tab), tab.name)
        }
        // 战斗玩法三块都有内容：参数、出生点清单、赛制。
        for (tab in ModeTab.entries) {
            assertTrue(ModeTabs.hasContent("free_for_all", tab), tab.name)
        }
        // 竞速没有"赛制"之外的战斗项，但起终点属于点位。
        assertTrue(ModeTabs.hasContent("run_race", ModeTab.SPAWNS))
    }

    @Test
    fun `tab buttons never collide with settings`() {
        assertTrue(ModeTabs.TAB_SLOTS.none { GuiSlots.MODE_CONFIGURATION.contains(it) })
    }
}
