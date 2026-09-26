package org.cubexmc.regions.gui

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.cubexmc.regions.match.MatchSpawns
import org.cubexmc.regions.match.SpawnPoint
import org.cubexmc.regions.model.RegionDefinition
import kotlin.math.ceil
import kotlin.math.max

/**
 * 出生点清单：把"已设 N 个"变成看得见、删得掉的一页（PLAN.md §5.2）。
 *
 * 原先玩法页只有一个计数按钮，设错了只能整组清空重来，而且根本看不出点设在哪——
 * 实机上这是最容易把人卡住的一步。
 *
 * 工会战有甲/乙两组，用同一页的两个标签切换；其余战斗玩法只有一组。
 */
internal class SpawnListMenu(private val gui: RegionsGui) {
    private val plugin get() = gui.plugin
    private val text get() = gui.text
    private val items get() = gui.items

    fun open(player: Player, regionId: String, teamB: Boolean = false, page: Int = 0) {
        val region = gui.editable(regionId) ?: return gui.openMain(player)
        val union = region.mode?.type.orEmpty().equals("union_war", ignoreCase = true)
        val sideB = teamB && union
        val key = if (sideB) SPAWN_B else SPAWN_A
        val points = MatchSpawns.parseList(region.mode?.values?.get(key))
        val pageCount = max(1, ceil(points.size / PAGE_SIZE.toDouble()).toInt())
        val current = page.coerceIn(0, pageCount - 1)

        val holder = RegionsHolder(View.SPAWN_LIST, region.id, lobbyPage = current, spawnTeamB = sideB)
        val inventory = Bukkit.createInventory(
            holder,
            54,
            text.component(player, "gui.spawns.title", mapOf("name" to region.name)),
        )

        points.drop(current * PAGE_SIZE).take(PAGE_SIZE).forEachIndexed { offset, point ->
            val index = current * PAGE_SIZE + offset
            inventory.setItem(
                offset,
                text.item(
                    player,
                    Material.LODESTONE,
                    if (sideB) "gui.spawns.entry.b" else "gui.spawns.entry.a",
                    mapOf("index" to (index + 1).toString(), "location" to describe(point)),
                ),
            )
        }
        if (points.isEmpty()) inventory.setItem(22, text.item(player, GuiIcons.EMPTY, "gui.spawns.empty"))

        if (union) {
            inventory.setItem(45, text.item(player, sideMaterial(false, sideB), "gui.spawns.side.a"))
            inventory.setItem(46, text.item(player, sideMaterial(true, sideB), "gui.spawns.side.b"))
        }
        inventory.setItem(
            48,
            text.item(player, Material.ENDER_PEARL, "gui.spawns.add", mapOf("location" to GuiValues.formatLocation(player.location))),
        )
        inventory.setItem(50, text.item(player, GuiIcons.CLEAR, "gui.spawns.clear", mapOf("count" to points.size.toString())))
        inventory.setItem(49, items.back(player))
        if (current > 0) inventory.setItem(44, text.named(Material.ARROW, text.text(player, "gui.common.previous-page")))
        if (current < pageCount - 1) inventory.setItem(53, text.named(Material.ARROW, text.text(player, "gui.common.next-page")))
        player.openInventory(inventory)
    }

    fun click(player: Player, holder: RegionsHolder, slot: Int) {
        val regionId = holder.regionId ?: return gui.openMain(player)
        val region = gui.editable(regionId) ?: return gui.openMain(player)
        val sideB = holder.spawnTeamB
        val key = if (sideB) SPAWN_B else SPAWN_A
        when (slot) {
            49 -> return gui.openModePage(player, regionId, ModeTab.SPAWNS)
            45 -> return open(player, regionId, teamB = false)
            46 -> return open(player, regionId, teamB = true)
            44 -> return open(player, regionId, sideB, holder.lobbyPage - 1)
            53 -> return open(player, regionId, sideB, holder.lobbyPage + 1)
            48 -> return write(player, region, key, MatchSpawns.parseList(region.mode?.values?.get(key)) + here(player), sideB, holder.lobbyPage)
            50 -> return write(player, region, key, emptyList(), sideB, 0)
        }
        if (slot >= PAGE_SIZE) return
        val points = MatchSpawns.parseList(region.mode?.values?.get(key))
        val index = holder.lobbyPage * PAGE_SIZE + slot
        if (index !in points.indices) return
        // 删错了站过去再点一次"在这里添加"就回来了，所以不做二次确认。
        write(player, region, key, points.filterIndexed { position, _ -> position != index }, sideB, holder.lobbyPage)
    }

    private fun write(
        player: Player,
        region: RegionDefinition,
        key: String,
        points: List<SpawnPoint>,
        sideB: Boolean,
        page: Int,
    ) {
        val mode = region.mode ?: return
        val values = LinkedHashMap(mode.values)
        if (points.isEmpty()) values.remove(key) else values[key] = points.joinToString(";") { format(it) }
        gui.saveAndReopen(player, region.copy(mode = mode.copy(values = values))) {
            open(player, region.id, sideB, page)
        }
    }

    private fun here(player: Player): SpawnPoint {
        val location = player.location
        return SpawnPoint(
            location.world?.name.orEmpty(),
            location.x,
            location.y,
            location.z,
            location.yaw,
            location.pitch,
        )
    }

    private fun format(point: SpawnPoint): String =
        listOf(point.world, point.x, point.y, point.z, point.yaw, point.pitch).joinToString(",")

    private fun describe(point: SpawnPoint): String =
        "%s %.0f %.0f %.0f".format(point.world, point.x, point.y, point.z)

    private fun sideMaterial(isB: Boolean, sideB: Boolean): Material = when {
        isB == sideB -> Material.LIME_STAINED_GLASS_PANE
        isB -> Material.BLUE_BANNER
        else -> Material.RED_BANNER
    }

    private companion object {
        const val PAGE_SIZE = 36
        const val SPAWN_A = "spawn-points"
        const val SPAWN_B = "spawn-points-b"
    }
}
