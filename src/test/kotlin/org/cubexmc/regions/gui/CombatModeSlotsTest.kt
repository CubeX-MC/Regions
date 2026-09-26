package org.cubexmc.regions.gui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 玩法页共用槽位的语义（2026-09-19 实机反馈）。
 *
 * 实机症状是"工会战／大乱斗里点队伍人数、超时这些参数，数字不动"。原因不是数值逻辑，
 * 而是渲染按战斗玩法画、点击却落进了竞速／捉迷藏的同号分支——`when (slot)` 里 45/46/47
 * 出现了两次，Kotlin 只警告不报错，先匹配的赢。
 */
class CombatModeSlotsTest {

    @Test
    fun `union war owns the shared slots it renders`() {
        assertEquals(CombatSlot.SPAWN_A, CombatModeSlots.of("union_war", 45))
        assertEquals(CombatSlot.SPAWN_B, CombatModeSlots.of("union_war", 46))
        assertEquals(CombatSlot.TEAM_SIZE, CombatModeSlots.of("union_war", 47))
        assertEquals(CombatSlot.TIMEOUT_SECONDS, CombatModeSlots.of("union_war", 50))
        assertEquals(CombatSlot.CLEAR_SPAWNS, CombatModeSlots.of("union_war", 51))
        assertEquals(CombatSlot.DIPLOMACY, CombatModeSlots.of("union_war", 24))
    }

    @Test
    fun `free for all keeps spawns and its own timeout`() {
        assertEquals(CombatSlot.SPAWN_A, CombatModeSlots.of("free_for_all", 45))
        assertEquals(CombatSlot.CLEAR_SPAWNS, CombatModeSlots.of("free_for_all", 46))
        assertEquals(CombatSlot.TIMEOUT_SECONDS, CombatModeSlots.of("free_for_all", 47))
        assertNull(CombatModeSlots.of("free_for_all", 50), "free_for_all has no best-of / timeout slot at 50")
    }

    @Test
    fun `duel keeps round seconds and best of`() {
        assertEquals(CombatSlot.SPAWN_A, CombatModeSlots.of("dual_pvp", 45))
        assertEquals(CombatSlot.CLEAR_SPAWNS, CombatModeSlots.of("dual_pvp", 46))
        assertEquals(CombatSlot.ROUND_SECONDS, CombatModeSlots.of("dual_pvp", 47))
        assertEquals(CombatSlot.BEST_OF, CombatModeSlots.of("dual_pvp", 50))
    }

    @Test
    fun `hide and seek and races keep the generic meaning of those slots`() {
        for (mode in listOf("hide_and_seek", "run_race", "boat_race", "horse_race", "free_event")) {
            for (slot in CombatModeSlots.SHARED_SLOTS + 24) {
                assertNull(CombatModeSlots.of(mode, slot), "$mode slot $slot must stay generic")
            }
        }
    }

    /**
     * 回归守卫：战斗玩法只要让某个共用槽位**可见**，就必须在表里声明它的含义，
     * 否则点击会落进通用分支去改别的键——这正是实机上"数字不变"的成因。
     */
    @Test
    fun `every visible shared slot of a combat mode has its own meaning`() {
        for (mode in listOf("dual_pvp", "union_war", "free_for_all")) {
            assertTrue(CombatModeSlots.isCombat(mode), mode)
            val visible = GuiSlots.modeConfiguration(mode)
            for (slot in visible.intersect(CombatModeSlots.SHARED_SLOTS)) {
                assertTrue(
                    CombatModeSlots.of(mode, slot) != null,
                    "$mode renders slot $slot but never says what clicking it should do",
                )
            }
        }
    }

    @Test
    fun `mode names are matched case insensitively`() {
        assertEquals(CombatSlot.TEAM_SIZE, CombatModeSlots.of("UNION_WAR", 47))
        assertTrue(CombatModeSlots.isCombat("Free_For_All"))
    }
}
