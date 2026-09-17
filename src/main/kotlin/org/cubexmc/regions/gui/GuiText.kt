package org.cubexmc.regions.gui

import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.cubexmc.regions.RegionsPlugin
import org.cubexmc.regions.model.ValidationSeverity

/**
 * Every string the GUI shows comes from the active language file.
 *
 * Each helper takes the **viewing player** explicitly and resolves through
 * [org.cubexmc.regions.config.LanguageManager.messageFor]: whatever menu text a player sees is
 * rendered in that player's locale, so a Chinese and an English player looking at the same menu do
 * not get the same strings. There are deliberately no viewer-less overloads — under
 * `locale-mode: player` a helper that resolves in the server locale would silently hand one player's
 * language to another, and the compiler is what keeps that from coming back. On the default
 * `locale-mode: server` every resolution returns the server language, so existing servers render
 * exactly what they did before.
 *
 * Menu entries follow a `<key>.name` / `<key>.lore` pair so [item] can build a whole button from one
 * key, which is what keeps the menu classes readable once the literals move out of the code.
 */
class GuiText(private val plugin: RegionsPlugin) {
    fun text(viewer: Player, key: String, placeholders: Map<String, String> = emptyMap()): String =
        plugin.lang().messageFor(viewer, key, placeholders)

    fun component(viewer: Player, key: String, placeholders: Map<String, String> = emptyMap()): Component =
        plugin.lang().componentFor(viewer, key, placeholders)

    fun lore(viewer: Player, key: String, placeholders: Map<String, String> = emptyMap()): List<String> =
        plugin.lang().messageListFor(viewer, key, placeholders)

    /** Builds a button from `<key>.name` and, when present, `<key>.lore`, rendered for [viewer]. */
    fun item(
        viewer: Player,
        material: Material,
        key: String,
        placeholders: Map<String, String> = emptyMap(),
        extraLore: List<String> = emptyList(),
    ): ItemStack = named(
        material,
        text(viewer, "$key.name", placeholders),
        lore(viewer, "$key.lore", placeholders) + extraLore,
    )

    /**
     * Wraps already-rendered strings into an [ItemStack]. The strings are rendered by the caller (in
     * its viewer's locale) and only parsed here, through
     * [org.cubexmc.regions.config.LanguageManager.render], so this stays viewer-free on purpose.
     */
    fun named(material: Material, name: String, lore: List<String> = emptyList()): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta
        if (meta != null) {
            meta.displayName(plugin.lang().render(name))
            meta.lore(lore.map { plugin.lang().render(it) })
            item.itemMeta = meta
        }
        return item
    }

    fun send(player: Player, key: String, placeholders: Map<String, String> = emptyMap()) {
        plugin.lang().sendRaw(player, text(player, key, placeholders))
    }

    /** Falls back to the raw id when a capability has no translation, so nothing renders blank. */
    fun label(viewer: Player, key: String, fallback: String): String =
        plugin.lang().labelFor(viewer, key, fallback)

    /**
     * Display name for a `true`/`false` config value (`labels.common.on` / `labels.common.off`).
     * Storage keeps the raw boolean; only this presentation boundary translates it.
     */
    fun boolDisplay(viewer: Player, raw: String): String =
        if (raw.equals("true", ignoreCase = true)) {
            text(viewer, "labels.common.on")
        } else {
            text(viewer, "labels.common.off")
        }

    /** Display name for an enum config value under `labels.<group>.<id>`, falling back to the id. */
    fun enumDisplay(viewer: Player, group: String, id: String): String =
        label(viewer, "labels.$group.$id", id)

    /** One validation finding, with its field and severity, rendered for [viewer]. */
    fun issueLine(
        viewer: Player,
        code: String,
        args: Map<String, String> = emptyMap(),
        fieldPath: String? = null,
        diagnostic: String = "",
    ): String = plugin.lang().issueLineFor(viewer, code, args, fieldPath, diagnostic)

    fun severityLabel(viewer: Player, severity: ValidationSeverity): String =
        plugin.lang().severityLabelFor(viewer, severity)

    /** A [org.cubexmc.regions.service.ServiceResult] failure reason, rendered for [viewer]. */
    fun resultReason(viewer: Player, code: String?, args: Map<String, String>, reason: String): String =
        plugin.lang().resultReasonFor(viewer, code, args, reason)

    /**
     * Colours for labels the code assembles rather than reads whole from the language file — a
     * status tint in front of a translated name, for example.
     *
     * Deliberately `&` codes and not MiniMessage tags: these are concatenated with strings the i18n
     * service has *already* rendered, so they are read back by
     * [org.cubexmc.regions.config.LanguageManager.render] and never re-parsed as MiniMessage.
     */
    object Ui {
        const val DARK_GREEN = "&2"
        const val AQUA = "&b"
        const val GRAY = "&7"
        const val GREEN = "&a"
        const val YELLOW = "&e"
        const val RED = "&c"
        const val DARK_GRAY = "&8"
    }
}
