package org.cubexmc.regions.listener

import org.bukkit.entity.ArmorStand
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.EventPriority
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.inventory.CraftingInventory
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.Inventory
import org.cubexmc.regions.RegionsPlugin
import org.cubexmc.regions.gui.RegionsHolder

/** Protect temporary kits even when a container was already open before preparation. */
class GearTransferListener(private val plugin: RegionsPlugin) : Listener {
    private fun protected(player: Player): Boolean =
        plugin.combatModes().isGearEscrowed(player.uniqueId) ||
            plugin.raceModes().isGearEscrowed(player.uniqueId) ||
            plugin.roundModes().isGearEscrowed(player.uniqueId)

    private fun external(inventory: Inventory): Boolean =
        // The personal 2x2 grid has four inputs and one result; a workbench has ten slots.
        !(inventory is CraftingInventory && inventory.size == 5) && inventory.holder !is RegionsHolder

    @EventHandler(ignoreCancelled = true)
    fun onOpen(event: InventoryOpenEvent) {
        val player = event.player as? Player ?: return
        if (external(event.inventory) && protected(player)) {
            event.isCancelled = true
            plugin.lang().sendPlain(player, "game.match.gear-transfer-denied")
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (external(event.view.topInventory) && protected(player)) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        if (external(event.view.topInventory) && protected(player)) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        if (protected(event.player)) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onEquipEntity(event: PlayerInteractEntityEvent) {
        if ((event.rightClicked is ArmorStand || event.rightClicked is ItemFrame) && protected(event.player)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDeath(event: PlayerDeathEvent) {
        if (protected(event.entity)) {
            event.drops.clear()
            event.droppedExp = 0
        }
    }
}
