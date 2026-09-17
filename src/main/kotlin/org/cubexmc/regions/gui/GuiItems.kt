package org.cubexmc.regions.gui

import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.cubexmc.regions.gui.GuiText.Ui
import org.cubexmc.regions.model.EffectConfig
import org.cubexmc.regions.model.RegionDefinition
import java.util.Locale

/**
 * Builds the recurring buttons shared by several menus.
 *
 * Each builder takes the player the menu is being rendered for and passes it on as the locale
 * viewer, so a button is always built in its own viewer's language (see [GuiText]).
 */
internal class GuiItems(private val text: GuiText, private val keys: GuiKeys) {
    fun region(viewer: Player, region: RegionDefinition): ItemStack {
        val material = if (region.enabled) Material.EMERALD_BLOCK else Material.COAL_BLOCK
        val item = text.named(
            material,
            text.text(viewer, "gui.item.region.name", mapOf("name" to region.name)),
            text.lore(
                viewer,
                "gui.item.region.lore",
                mapOf(
                    "id" to region.id,
                    "source" to region.source.describe(),
                    "lifecycle" to region.lifecycle.name.lowercase(Locale.ROOT),
                    "revision" to region.revision.toString(),
                    "mode" to (region.mode?.type ?: text.text(viewer, "gui.common.none")),
                    "flags" to region.flags.size.toString(),
                    "effects" to region.effects.size.toString(),
                ),
            ),
        )
        item.itemMeta?.let { meta ->
            meta.persistentDataContainer.set(keys.region, PersistentDataType.STRING, region.id)
            item.itemMeta = meta
        }
        return item
    }

    fun mode(viewer: Player, type: String, current: String?): ItemStack {
        val active = type.equals(current, ignoreCase = true)
        val lore = listOf(text.text(viewer, if (active) "gui.mode.current" else "gui.mode.switch")) +
            text.lore(viewer, "gui.mode.type.$type.lore")
        // 玩家界面显示译名；稳定 ID 只在"当前玩法"的标题里以括号形式出现（PLAN.md §4.1）。
        val label = text.label(viewer, "labels.mode.$type", type)
        return text.named(
            modeMaterial(type),
            "${if (active) Ui.GREEN else Ui.YELLOW}$label" + if (active) " <dark_gray>($type)" else "",
            lore,
        )
    }

    fun flag(viewer: Player, flag: String, value: String): ItemStack {
        val normalized = value.lowercase(Locale.ROOT)
        val material = when (normalized) {
            "deny" -> Material.RED_CONCRETE
            "allow" -> Material.LIME_CONCRETE
            else -> Material.GRAY_CONCRETE
        }
        return text.named(
            material,
            text.text(
                viewer,
                "gui.flag.entry.name",
                mapOf(
                    // 高级页保留稳定 ID，但主名必须是译名（PLAN.md §4.1）。
                    "flag" to "${text.label(viewer, "labels.flag.$flag", flag)} <dark_gray>($flag)",
                    "color" to statusColor(normalized),
                    "value" to text.label(viewer, "gui.flag.value.$normalized", normalized),
                ),
            ),
            text.lore(viewer, "gui.flag.entry.lore"),
        )
    }

    fun effect(viewer: Player, index: Int, effect: EffectConfig): ItemStack = text.named(
        Material.BLAZE_POWDER,
        text.text(
            viewer,
            "gui.effect.entry.name",
            mapOf("index" to (index + 1).toString(), "type" to effect.type),
        ),
        text.lore(
            viewer,
            "gui.effect.entry.lore",
            mapOf(
                "scope" to effect.scope.name.lowercase(Locale.ROOT),
                "combination" to effect.combination.name.lowercase(Locale.ROOT),
                "values" to effect.values.entries
                    .joinToString(", ") { "${it.key}=${it.value}" }
                    .ifBlank { text.text(viewer, "gui.effect.entry.no-values") },
            ),
        ),
    )

    fun templateMaterial(type: String?): Material = when (type?.lowercase(Locale.ROOT)) {
        "dual_pvp", "union_war", "free_for_all" -> Material.DIAMOND_SWORD
        "run_race" -> Material.LEATHER_BOOTS
        "boat_race" -> Material.OAK_BOAT
        "horse_race" -> Material.SADDLE
        "hide_and_seek" -> Material.ENDER_EYE
        else -> Material.CAMPFIRE
    }

    fun back(viewer: Player): ItemStack = text.named(GuiIcons.BACK, text.text(viewer, "gui.common.back"))

    fun describeVehicle(viewer: Player, value: String): String = when (value.lowercase(Locale.ROOT)) {
        "none", "on_foot", "on-foot", "no_vehicle", "no-vehicle", "foot" -> text.text(viewer, "gui.vehicle.on-foot")
        "any", "vehicle", "any_vehicle", "any-vehicle" -> text.text(viewer, "gui.vehicle.any")
        "boat" -> text.text(viewer, "gui.vehicle.boat")
        "horse" -> text.text(viewer, "gui.vehicle.horse")
        "minecart" -> text.text(viewer, "gui.vehicle.minecart")
        "pass", "ignore", "any_state", "any-state" -> text.text(viewer, "gui.vehicle.ignore")
        else -> value
    }

    private fun modeMaterial(type: String): Material = when (type) {
        "dual_pvp" -> Material.IRON_SWORD
        "union_war" -> Material.SHIELD
        "free_for_all" -> Material.NETHERITE_SWORD
        "run_race" -> Material.LEATHER_BOOTS
        "boat_race" -> Material.OAK_BOAT
        "horse_race" -> Material.SADDLE
        "hide_and_seek" -> Material.ENDER_EYE
        else -> Material.CAMPFIRE
    }

    private fun statusColor(value: String): String = when (value) {
        "deny" -> Ui.RED
        "allow" -> Ui.GREEN
        else -> Ui.GRAY
    }
}
