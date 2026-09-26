package org.cubexmc.regions.listener

import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.CraftingInventory
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryView
import org.cubexmc.regions.RegionsPlugin
import org.cubexmc.regions.gui.RegionsHolder
import org.cubexmc.regions.gui.View
import org.cubexmc.regions.mode.CombatModeService
import org.cubexmc.regions.mode.RaceModeService
import org.cubexmc.regions.mode.RoundModeService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.util.UUID

class GearTransferListenerTest {
    private val plugin = mock(RegionsPlugin::class.java)
    private val player = mock(Player::class.java)
    private val inventory = mock(Inventory::class.java)
    private val view = mock(InventoryView::class.java)
    private val race = mock(RaceModeService::class.java)
    private val listener = GearTransferListener(plugin)

    init {
        `when`(player.uniqueId).thenReturn(UUID.randomUUID())
        `when`(plugin.combatModes()).thenReturn(mock(CombatModeService::class.java))
        `when`(plugin.raceModes()).thenReturn(race)
        `when`(plugin.roundModes()).thenReturn(mock(RoundModeService::class.java))
        `when`(race.isGearEscrowed(player.uniqueId)).thenReturn(true)
        `when`(view.topInventory).thenReturn(inventory)
    }

    private fun click(): InventoryClickEvent {
        val event = mock(InventoryClickEvent::class.java)
        `when`(event.whoClicked).thenReturn(player)
        `when`(event.view).thenReturn(view)
        listener.onClick(event)
        return event
    }

    @Test fun `an already open chest cannot receive a kit through clicks or drag`() {
        verify(click()).isCancelled = true
        val drag = mock(InventoryDragEvent::class.java)
        `when`(drag.whoClicked).thenReturn(player)
        `when`(drag.view).thenReturn(view)
        listener.onDrag(drag)
        verify(drag).isCancelled = true
    }

    @Test fun `own inventory and match menus stay usable`() {
        val crafting = mock(CraftingInventory::class.java)
        `when`(crafting.size).thenReturn(5)
        `when`(view.topInventory).thenReturn(crafting)
        verify(click(), never()).isCancelled = true
        `when`(view.topInventory).thenReturn(inventory)
        `when`(inventory.holder).thenReturn(RegionsHolder(View.GAME_LOBBY))
        verify(click(), never()).isCancelled = true
    }

    @Test fun `normal players can use containers`() {
        `when`(race.isGearEscrowed(player.uniqueId)).thenReturn(false)
        verify(click(), never()).isCancelled = true
    }
}
