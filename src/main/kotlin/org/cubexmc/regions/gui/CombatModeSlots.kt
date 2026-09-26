package org.cubexmc.regions.gui

import java.util.Locale

/**
 * 战斗玩法（`dual_pvp` / `union_war` / `free_for_all`）在玩法页上复用了竞速与捉迷藏的槽位，
 * 但含义不同。这张表是**渲染与点击共同的唯一出处**。
 *
 * 为什么要有它：上一版把两边分开写，渲染无条件覆盖 45/46/47，点击却留在同一个
 * `when (slot)` 里排在 `seekers` / `hide-seconds` / `round-seconds` **之后**——
 * Kotlin 对重复的 `when` 标签只给警告，先匹配的分支赢，于是战斗玩法下：
 * 点"添加出生点"实际改了 `seekers`、点"队伍人数"实际改了 `round-seconds`，
 * 界面上的数字自然纹丝不动（2026-09-19 实机反馈）。
 */
internal enum class CombatSlot {
    /** 出生点（工会战是 A 队；其余玩法就是唯一一组）。 */
    SPAWN_A,

    /** 工会战 B 队出生点。 */
    SPAWN_B,

    /** 清空本玩法的全部出生点。 */
    CLEAR_SPAWNS,

    /** 单回合时限。 */
    ROUND_SECONDS,

    /** 工会战每队人数。 */
    TEAM_SIZE,

    /** 整场超时。 */
    TIMEOUT_SECONDS,

    /** 决斗赛制 BO1 / BO3。 */
    BEST_OF,

    /** 工会战的外交前置（agreed / enemy-only）。 */
    DIPLOMACY,
}

internal object CombatModeSlots {

    /** 这个玩法是不是三类战斗之一——渲染时据此决定要不要改写共用槽位。 */
    fun isCombat(modeType: String): Boolean = modeType.lowercase(Locale.ROOT) in COMBAT_MODES

    /** 该玩法在该槽位上的含义；`null` = 这个槽位仍按通用（竞速／捉迷藏）语义处理。 */
    fun of(modeType: String, slot: Int): CombatSlot? = when (modeType.lowercase(Locale.ROOT)) {
        "dual_pvp" -> when (slot) {
            45 -> CombatSlot.SPAWN_A
            46 -> CombatSlot.CLEAR_SPAWNS
            47 -> CombatSlot.ROUND_SECONDS
            50 -> CombatSlot.BEST_OF
            else -> null
        }

        "union_war" -> when (slot) {
            24 -> CombatSlot.DIPLOMACY
            45 -> CombatSlot.SPAWN_A
            46 -> CombatSlot.SPAWN_B
            47 -> CombatSlot.TEAM_SIZE
            50 -> CombatSlot.TIMEOUT_SECONDS
            51 -> CombatSlot.CLEAR_SPAWNS
            else -> null
        }

        "free_for_all" -> when (slot) {
            45 -> CombatSlot.SPAWN_A
            46 -> CombatSlot.CLEAR_SPAWNS
            47 -> CombatSlot.TIMEOUT_SECONDS
            else -> null
        }

        else -> null
    }

    /**
     * 这些槽位在通用分支里另有含义（seekers / hide-seconds / round-seconds /
     * found-becomes-seeker / timeout-seconds）。战斗玩法只要让它们可见，就必须在
     * [of] 里给出自己的含义，否则点击会落进通用分支去改别的键——
     * `CombatModeSlotsTest` 用这份名单把这条约束钉死。
     */
    val SHARED_SLOTS: Set<Int> = setOf(45, 46, 47, 50, 51)

    private val COMBAT_MODES = setOf("dual_pvp", "union_war", "free_for_all")
}
