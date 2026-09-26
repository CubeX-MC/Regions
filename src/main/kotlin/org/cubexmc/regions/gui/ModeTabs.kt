package org.cubexmc.regions.gui

import java.util.Locale

/** 玩法页的三块标签：基础参数 / 点位 / 赛制。 */
internal enum class ModeTab {
    BASIC,
    SPAWNS,
    FORMAT,
    ;

    val slot: Int
        get() = when (this) {
            BASIC -> 0
            SPAWNS -> 1
            FORMAT -> 2
        }

    val key: String
        get() = "gui.mode.tab." + name.lowercase(Locale.ROOT)

    /** 标题跟着标签走，进来就知道自己在哪一块。 */
    val titleKey: String
        get() = "gui.mode.title." + name.lowercase(Locale.ROOT)
}

/**
 * 每个设置槽位属于哪一块标签——**渲染与点击共用这一张表**。
 *
 * 分三块是因为一页 54 格已经装不下：出生点要列成可删的清单（PLAN.md §5.2），
 * 再往里塞只会变成又一个"看不出点了有没有用"的页面。
 *
 * 同一槽位在不同玩法下含义不同（24 是载具检查还是外交、26 是起点载具还是对阵、
 * 45/46/47 是捉迷藏参数还是出生点），所以判定必须带上 modeType；
 * 这与 [CombatModeSlots] 是同一条纪律：**两边不允许各写一份**。
 */
internal object ModeTabs {

    val TAB_SLOTS: Set<Int> = ModeTab.entries.map { it.slot }.toSet()

    fun tabOf(modeType: String, slot: Int): ModeTab? {
        val combat = CombatModeSlots.isCombat(modeType)
        val union = modeType.equals("union_war", ignoreCase = true)
        return when (slot) {
            19, 20, 21, 22, 23, 28, 29, 30, 31, 32 -> ModeTab.BASIC
            24 -> if (union) ModeTab.FORMAT else ModeTab.BASIC
            26 -> if (union) ModeTab.FORMAT else ModeTab.BASIC
            25, 33, 34, 36, 37, 38, 39 -> ModeTab.SPAWNS
            44 -> if (combat) ModeTab.SPAWNS else ModeTab.FORMAT
            40, 41, 42, 43 -> ModeTab.FORMAT
            45, 46 -> if (combat) ModeTab.SPAWNS else ModeTab.FORMAT
            51 -> if (combat) ModeTab.SPAWNS else ModeTab.FORMAT
            47, 50 -> ModeTab.FORMAT
            else -> null
        }
    }

    /** 该玩法在该标签下可见的槽位 = 玩法本身允许的槽位 ∩ 这一块标签。 */
    fun slotsFor(modeType: String, tab: ModeTab): Set<Int> =
        GuiSlots.modeConfiguration(modeType).filterTo(HashSet()) { tabOf(modeType, it) == tab }

    /** 这块标签在该玩法下有没有内容——空标签不显示，免得点进去一片空白。 */
    fun hasContent(modeType: String, tab: ModeTab): Boolean =
        tab == ModeTab.SPAWNS && CombatModeSlots.isCombat(modeType) || slotsFor(modeType, tab).isNotEmpty()
}
