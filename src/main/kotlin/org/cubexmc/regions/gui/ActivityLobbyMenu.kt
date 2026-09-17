package org.cubexmc.regions.gui

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.cubexmc.regions.mode.GamePhase
import org.cubexmc.regions.mode.gameStatusLine
import org.cubexmc.regions.mode.GameStatus
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionLifecycle
import org.cubexmc.regions.service.RegionOverlapResolver
import kotlin.math.ceil
import kotlin.math.max

/** 一张大厅卡片的可报名判定（PLAN.md §5.1：不能给点击后无响应的灰色按钮，要显示原因）。 */
internal data class LobbyEntry(
    val region: RegionDefinition,
    val available: Boolean,
    val reasonKey: String? = null,
    val reasonArgs: Map<String, String> = emptyMap(),
)

/** 大厅筛选（PLAN.md §5.1：玩法／可报名筛选）。null 玩法表示不按玩法过滤。 */
internal data class LobbyFilter(
    val modeType: String? = null,
    val joinableOnly: Boolean = false,
) {
    val isActive: Boolean get() = modeType != null || joinableOnly

    /** 循环切换玩法筛选：全部 → 每个出现的玩法 → 全部。 */
    fun cycleMode(availableModes: List<String>): LobbyFilter {
        if (availableModes.isEmpty()) return copy(modeType = null)
        val index = modeType?.let { availableModes.indexOf(it) } ?: -1
        val next = if (index + 1 >= availableModes.size) null else availableModes[index + 1]
        return copy(modeType = next)
    }
}

/**
 * 活动大厅的纯判定逻辑，独立于 Bukkit GUI 以便测试：
 * 分页规模、"能否报名"与不可报名的原因码、玩法/可报名筛选。
 */
internal object ActivityLobbyLogic {

    const val PAGE_SIZE = GuiSlots.LOBBY_PAGE_SIZE

    fun pageCount(entryCount: Int): Int = max(1, ceil(entryCount / PAGE_SIZE.toDouble()).toInt())

    /** 按筛选条件过滤卡片；筛选后仍按原顺序（优先级 + id）排列。 */
    fun applyFilter(entries: List<LobbyEntry>, filter: LobbyFilter): List<LobbyEntry> =
        entries.filter { entry ->
            val modeOk = filter.modeType?.let { entry.region.mode?.type?.equals(it, ignoreCase = true) == true } ?: true
            val joinableOk = !filter.joinableOnly || entry.available
            modeOk && joinableOk
        }

    /** 卡片上出现过的玩法，稳定排序，用于筛选按钮的循环顺序。 */
    fun availableModes(entries: List<LobbyEntry>): List<String> =
        entries.mapNotNull { it.region.mode?.type }.distinct().sorted()

    fun entry(
        region: RegionDefinition,
        status: GameStatus,
        ending: Boolean,
        sourceAvailable: Boolean,
        sourceLabel: String,
        unionsAvailable: Boolean,
    ): LobbyEntry {
        if (!sourceAvailable) {
            return LobbyEntry(region, false, "gui.lobby.reason.source-unavailable", mapOf("source" to sourceLabel))
        }
        if (region.mode?.type.equals("union_war", true) && !unionsAvailable) {
            return LobbyEntry(region, false, "gui.lobby.reason.unions-unavailable")
        }
        if (ending) {
            return LobbyEntry(region, false, "gui.lobby.reason.restoring")
        }
        if (status.phase == GamePhase.RUNNING) {
            return LobbyEntry(region, false, "gui.lobby.reason.running")
        }
        val maxPlayers = region.mode?.values?.get("max-players")?.toIntOrNull() ?: 0
        if (maxPlayers > 0 && status.players >= maxPlayers) {
            return LobbyEntry(region, false, "gui.lobby.reason.full")
        }
        return LobbyEntry(region, true)
    }
}

/** 玩家侧活动大厅（`/regions`）：已发布且启用的场地、可报名原因与分页。 */
internal class ActivityLobbyMenu(private val gui: RegionsGui) {
    private val plugin get() = gui.plugin
    private val text get() = gui.text

    fun open(player: Player, page: Int = 0, filter: LobbyFilter = LobbyFilter()) {
        val all = publishedRegions(player).map { entryFor(player, it) }
        val entries = ActivityLobbyLogic.applyFilter(all, filter)
        val pages = ActivityLobbyLogic.pageCount(entries.size)
        val safePage = page.coerceIn(0, pages - 1)
        val inventory = Bukkit.createInventory(
            RegionsHolder(View.ACTIVITY_LOBBY, lobbyPage = safePage, lobbyFilter = filter),
            54,
            text.component(
                player,
                "gui.lobby.title",
                mapOf("page" to (safePage + 1).toString(), "pages" to pages.toString()),
            ),
        )
        for ((index, entry) in entries.drop(safePage * ActivityLobbyLogic.PAGE_SIZE).take(ActivityLobbyLogic.PAGE_SIZE).withIndex()) {
            inventory.setItem(index, card(player, entry))
        }
        if (safePage > 0) inventory.setItem(45, text.named(GuiIcons.BACK, text.text(player, "gui.common.previous-page")))
        if (safePage + 1 < pages) inventory.setItem(53, text.named(Material.ARROW, text.text(player, "gui.common.next-page")))
        if (all.isEmpty()) {
            inventory.setItem(22, text.item(player, GuiIcons.EMPTY, "gui.lobby.empty"))
        } else if (entries.isEmpty()) {
            inventory.setItem(22, text.item(player, GuiIcons.EMPTY, "gui.lobby.filter.empty"))
        }
        // 筛选：玩法循环切换 + 只看可报名（PLAN.md §5.1）。
        val modes = ActivityLobbyLogic.availableModes(all)
        inventory.setItem(
            46,
            text.item(
                player,
                if (filter.modeType == null) Material.HOPPER else gui.items.templateMaterial(filter.modeType),
                "gui.lobby.filter.mode",
                mapOf(
                    "mode" to (filter.modeType?.let { text.label(player, "labels.mode.$it", it) }
                        ?: text.text(player, "gui.lobby.filter.all")),
                    "count" to entries.size.toString(),
                ),
            ),
        )
        inventory.setItem(
            47,
            text.item(
                player,
                if (filter.joinableOnly) Material.LIME_DYE else Material.GRAY_DYE,
                "gui.lobby.filter.joinable",
                mapOf("value" to text.boolDisplay(player, filter.joinableOnly.toString())),
            ),
        )
        if (filter.isActive) inventory.setItem(48, text.item(player, GuiIcons.CLEAR, "gui.lobby.filter.clear"))
        if (gui.canEnterManagementSilent(player)) {
            inventory.setItem(49, text.item(player, Material.WRITABLE_BOOK, "gui.lobby.my-regions"))
        }
        player.openInventory(inventory)
    }

    fun click(player: Player, holder: RegionsHolder, slot: Int) {
        val filter = holder.lobbyFilter
        val all = publishedRegions(player).map { entryFor(player, it) }
        val entries = ActivityLobbyLogic.applyFilter(all, filter)
        when (slot) {
            45 -> open(player, holder.lobbyPage - 1, filter)
            53 -> open(player, holder.lobbyPage + 1, filter)
            // 切换筛选后回到第 1 页，否则可能停在一个已经不存在的页码上。
            46 -> open(player, 0, filter.cycleMode(ActivityLobbyLogic.availableModes(all)))
            47 -> open(player, 0, filter.copy(joinableOnly = !filter.joinableOnly))
            48 -> open(player, 0, LobbyFilter())
            49 -> if (gui.canEnterManagementSilent(player)) gui.openMain(player)
            in 0 until ActivityLobbyLogic.PAGE_SIZE -> {
                val entry = entries.getOrNull(holder.lobbyPage * ActivityLobbyLogic.PAGE_SIZE + slot) ?: return
                if (entry.available) {
                    gui.gameLobby.open(player, entry.region.id, holder.lobbyPage, filter)
                } else {
                    val key = entry.reasonKey ?: return
                    text.send(player, key, entry.reasonArgs)
                }
            }
        }
    }

    private fun publishedRegions(player: Player): List<RegionDefinition> =
        plugin.authority().visibleRegions(player, plugin.regions().all())
            .filter { it.enabled && it.lifecycle == RegionLifecycle.PUBLISHED }
            .sortedWith(RegionOverlapResolver.REGION_ORDER)

    private fun entryFor(player: Player, region: RegionDefinition): LobbyEntry {
        val source = plugin.sources().find(region.source.type)
        val unionsAvailable = plugin.unions().active()?.type != "fallback"
        return ActivityLobbyLogic.entry(
            region,
            statusOf(region),
            ending = plugin.combatModes().isCombatMode(region) && plugin.combatModes().isEnding(region.id),
            sourceAvailable = source?.isAvailable() == true,
            sourceLabel = text.label(player, "labels.source." + region.source.type, region.source.type),
            unionsAvailable = unionsAvailable,
        )
    }

    private fun statusOf(region: RegionDefinition): GameStatus = when (region.mode?.type?.lowercase()) {
        "run_race", "boat_race", "horse_race" -> plugin.raceModes().status(region.id)
        "hide_and_seek" -> plugin.roundModes().status(region.id)
        "dual_pvp", "union_war", "free_for_all" -> plugin.combatModes().status(region.id)
        else -> GameStatus(region.id, region.mode?.type ?: "", GamePhase.IDLE)
    }

    private fun card(player: Player, entry: LobbyEntry): ItemStack {
        val region = entry.region
        val modeType = region.mode?.type ?: ""
        val placeholders = mapOf(
            "name" to region.name,
            "mode" to text.label(player, "labels.mode.$modeType", modeType),
            "status" to plugin.gameStatusLine(player, statusOf(region)),
        )
        val reasonLore = if (entry.available) {
            text.lore(player, "gui.lobby.reason.joinable")
        } else {
            entry.reasonKey?.let { text.lore(player, it, entry.reasonArgs) } ?: emptyList()
        }
        return text.named(
            gui.items.templateMaterial(modeType),
            text.text(player, "gui.lobby.entry.name", placeholders),
            text.lore(player, "gui.lobby.entry.lore", placeholders) + reasonLore,
        )
    }
}
