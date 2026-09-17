package org.cubexmc.regions.gui

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.cubexmc.regions.RegionsPlugin
import org.cubexmc.regions.model.RegionSourceRef

internal enum class View {
    ACTIVITY_LOBBY,
    GAME_LOBBY,
    WIZARD_MODE,
    WIZARD_SETTINGS,
    MAIN,
    DETAIL,
    DETAIL_ADVANCED,
    SOURCE,
    MODE,
    FLAGS,
    EFFECTS,
    TRIGGERS,
    PUBLISH_PREVIEW,
    OWNED_AREAS,
    TEMPLATES,
    TEMPLATE_CONFIRM,
    ;

    /**
     * 管理视图要求"当前 Source owner + 治理权限"。玩家侧视图（活动大厅、报名页）不要求，
     * 否则普通玩家一点就被关闭界面——那些页面本来就是要给所有人看的。
     */
    val requiresManagement: Boolean
        get() = this != ACTIVITY_LOBBY && this != GAME_LOBBY
}

internal enum class OwnedAreaPurpose { CREATE, BIND }

internal enum class TemplatePurpose { CREATE, APPLY }

internal data class OwnedAreaContext(
    val purpose: OwnedAreaPurpose,
    val targetId: String,
    val targetName: String,
    val page: Int = 0,
)

internal data class TemplateContext(
    val targetId: String,
    val targetName: String,
    val source: RegionSourceRef,
    val page: Int = 0,
    val purpose: TemplatePurpose = TemplatePurpose.CREATE,
)

internal data class TemplateConfirmation(
    val templateId: String,
    val supplied: Map<String, String>,
)

internal class RegionsHolder(
    val view: View,
    val regionId: String? = null,
    val ownedArea: OwnedAreaContext? = null,
    val template: TemplateContext? = null,
    val templateConfirmation: TemplateConfirmation? = null,
    val lobbyPage: Int = 0,
    val returnToPublish: Boolean = false,
    /** 工会战报名时的候选 Nation（稳定 ID → 显示名），按顺序映射到队伍按钮槽位。 */
    val teamChoices: List<Pair<String, String>> = emptyList(),
    /** 发布确认页展示的草稿 revision：确认发布时用它核对草稿没有被别人改过。 */
    val reviewedRevision: Long? = null,
    /** 活动大厅当前的筛选条件；从报名页返回时原样带回。 */
    val lobbyFilter: LobbyFilter = LobbyFilter(),
    /** 向导阶段 3 的"返回上一阶段"目标：true 回选玩法，false 回选地块。 */
    val wizardBackToMode: Boolean = false,
) : InventoryHolder {
    override fun getInventory(): Inventory = Bukkit.createInventory(null, 9)
}


/**
 * Icons shared by several menus.
 *
 * Every menu click is cancelled, but an icon on display is only ever one unhandled exception away
 * from ending up in a player's inventory, so menus never show a block players cannot legitimately
 * obtain — no barrier, no command block. Keep new icons survival-obtainable for the same reason.
 */
internal object GuiIcons {
    val BACK: Material = Material.ARROW
    val CLOSE: Material = Material.RED_STAINED_GLASS_PANE
    val EMPTY: Material = Material.GRAY_STAINED_GLASS_PANE
    val BLOCKED: Material = Material.RED_CONCRETE
    val CLEAR: Material = Material.BUCKET
    val TRIGGER: Material = Material.REPEATER
}

/** Persistent-data keys used to carry a selection through an inventory click. */
internal class GuiKeys(plugin: RegionsPlugin) {
    val region: NamespacedKey = NamespacedKey(plugin, "region_id")
    val land: NamespacedKey = NamespacedKey(plugin, "owned_land")
    val area: NamespacedKey = NamespacedKey(plugin, "owned_area")
    val template: NamespacedKey = NamespacedKey(plugin, "region_template")
    val modePicker: NamespacedKey = NamespacedKey(plugin, "wizard_mode")
}

internal object GuiSlots {
    const val LOBBY_PAGE_SIZE = 45
    const val OWNED_AREA_PAGE_SIZE = 45
    const val TEMPLATE_PAGE_SIZE = 45

    val MODE_CONFIGURATION: Set<Int> = setOf(
        19, 20, 21, 22, 23, 24, 25, 26, 28, 29, 30, 31, 32, 33, 34,
        36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 50, 51,
    )

    /**
     * Slots offered on the flag page. Flags with no runtime are listed but hidden at render time by
     * the registry check, so this map never promises a rule Regions cannot enforce.
     */
    val FLAGS: Map<Int, String> = linkedMapOf(
        10 to "pvp",
        11 to "fly",
        12 to "vanish",
        13 to "item_drop",
        14 to "item_pickup",
        15 to "block_break",
        16 to "block_place",
        19 to "vehicle_enter",
        20 to "commands",
        21 to "teleport_out",
    )

    fun modeConfiguration(type: String): Set<Int> =
        when (type.lowercase(java.util.Locale.ROOT)) {
            "dual_pvp" -> setOf(19, 20, 21, 22, 25, 28, 29, 30, 31, 33, 34, 45, 46, 47, 50)
            "free_for_all" -> setOf(19, 20, 21, 22, 28, 29, 30, 31, 33, 34, 45, 46, 47)
            "union_war" -> setOf(19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 33, 34, 45, 46, 47, 50, 51)
            "run_race", "boat_race", "horse_race" ->
                setOf(19, 20, 24, 26, 32, 36, 37, 38, 39, 40, 41, 42, 43, 44, 51)
            "hide_and_seek" -> setOf(19, 20, 21, 22, 25, 33, 34, 42, 44, 45, 46, 47, 50)
            else -> emptySet()
        }
}
