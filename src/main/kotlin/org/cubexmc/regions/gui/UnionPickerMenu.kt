package org.cubexmc.regions.gui

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.cubexmc.regions.integration.UnionLookup
import org.cubexmc.regions.model.UnionRef
import kotlin.math.ceil
import kotlin.math.max

/**
 * 工会战的对阵选择页：把 `/regions game <id> teams <A> <B>` 变成点两下。
 *
 * 命令仍然保留（脚本、控制台要用），但场地主不该为了开一场工会战去背 26 位 ULID——
 * 这一页只显示国家名字，点一下选甲方、再点一下选乙方，选错点同一个就取消。
 */
internal class UnionPickerMenu(private val gui: RegionsGui) {
    private val plugin get() = gui.plugin
    private val text get() = gui.text
    private val items get() = gui.items

    fun open(player: Player, regionId: String, page: Int = 0) {
        val region = gui.editable(regionId) ?: return gui.openMain(player)
        val provider = plugin.unions().active()
        val candidates = UnionLookup.ordered(provider?.allUnions().orEmpty())
        val pageCount = max(1, ceil(candidates.size / PAGE_SIZE.toDouble()).toInt())
        val current = page.coerceIn(0, pageCount - 1)
        val shown = candidates.drop(current * PAGE_SIZE).take(PAGE_SIZE)

        val holder = RegionsHolder(
            View.UNION_PICKER,
            region.id,
            lobbyPage = current,
            teamChoices = shown.map { it.id to UnionLookup.plainName(it.name) },
        )
        val inventory = Bukkit.createInventory(
            holder,
            54,
            text.component(player, "gui.teams.title", mapOf("name" to region.name)),
        )

        val (teamA, teamB) = plugin.combatModes().selectedTeams(region.id)
        inventory.setItem(4, summary(player, candidates, teamA, teamB))

        val mine = provider?.getEditUnion(player.uniqueId)
        shown.forEachIndexed { index, union ->
            val name = UnionLookup.plainName(union.name)
            val key = when {
                union.id == teamA -> "gui.teams.entry.a"
                union.id == teamB -> "gui.teams.entry.b"
                union.id == mine?.id -> "gui.teams.entry.mine"
                else -> "gui.teams.entry.free"
            }
            inventory.setItem(
                9 + index,
                text.item(player, banner(union.id, teamA, teamB), key, mapOf("name" to name)),
            )
        }
        if (candidates.isEmpty()) {
            inventory.setItem(22, text.item(player, GuiIcons.EMPTY, "gui.teams.empty"))
        }
        if (current > 0) inventory.setItem(45, text.named(Material.ARROW, text.text(player, "gui.common.previous-page")))
        if (current < pageCount - 1) inventory.setItem(53, text.named(Material.ARROW, text.text(player, "gui.common.next-page")))
        inventory.setItem(48, text.item(player, GuiIcons.CLEAR, "gui.teams.clear"))
        inventory.setItem(49, items.back(player))
        player.openInventory(inventory)
    }

    fun click(player: Player, holder: RegionsHolder, slot: Int) {
        val regionId = holder.regionId ?: return gui.openMain(player)
        when (slot) {
            49 -> return gui.openModePage(player, regionId)
            45 -> return open(player, regionId, holder.lobbyPage - 1)
            53 -> return open(player, regionId, holder.lobbyPage + 1)
            48 -> {
                plugin.combatModes().clearTeams(regionId)
                return open(player, regionId, holder.lobbyPage)
            }
        }
        val index = slot - 9
        val choice = holder.teamChoices.getOrNull(index) ?: return
        val (teamA, teamB) = plugin.combatModes().selectedTeams(regionId)
        val (nextA, nextB) = when (choice.first) {
            // 再点一次已选中的：取消它，不必先清空再重选。
            teamA -> null to teamB
            teamB -> teamA to null
            else -> if (teamA == null) choice.first to teamB else teamA to choice.first
        }
        applySelection(player, regionId, nextA, nextB)
        open(player, regionId, holder.lobbyPage)
    }

    private fun applySelection(player: Player, regionId: String, teamA: String?, teamB: String?) {
        if (teamA == null || teamB == null) {
            plugin.combatModes().setTeams(regionId, teamA, teamB)
            return
        }
        val candidates = plugin.unions().active()?.allUnions().orEmpty()
        val nameOf = { id: String ->
            candidates.firstOrNull { it.id == id }?.let { UnionLookup.plainName(it.name) }
        }
        if (!plugin.combatModes().setTeams(regionId, teamA, teamB, nameOf(teamA), nameOf(teamB))) {
            // 服务层如实拒绝（已开局、有人报名、不是工会战）——GUI 不伪造成功。
            text.send(player, "gui.teams.rejected")
        }
    }

    private fun summary(player: Player, candidates: List<UnionRef>, teamA: String?, teamB: String?) =
        text.item(
            player,
            Material.PAPER,
            "gui.teams.summary",
            mapOf(
                "a" to label(player, candidates, teamA),
                "b" to label(player, candidates, teamB),
            ),
        )

    private fun label(player: Player, candidates: List<UnionRef>, id: String?): String {
        if (id == null) return text.text(player, "gui.common.unset")
        return candidates.firstOrNull { it.id == id }?.let { UnionLookup.plainName(it.name) } ?: id
    }

    private fun banner(id: String, teamA: String?, teamB: String?): Material = when (id) {
        teamA -> Material.RED_BANNER
        teamB -> Material.BLUE_BANNER
        else -> Material.WHITE_BANNER
    }

    private companion object {
        const val PAGE_SIZE = 36
    }
}
