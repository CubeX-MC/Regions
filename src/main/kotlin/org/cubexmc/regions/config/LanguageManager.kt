package org.cubexmc.regions.config

import net.kyori.adventure.text.Component
import org.bukkit.NamespacedKey
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import org.cubexmc.core.Reloadable
import org.cubexmc.i18n.ColorMode
import org.cubexmc.i18n.I18nOptions
import org.cubexmc.i18n.I18nService
import org.cubexmc.i18n.I18nServices
import org.cubexmc.i18n.MissingKeyMode
import org.cubexmc.i18n.PlaceholderStyle
import org.cubexmc.regions.RegionsPlugin
import org.cubexmc.regions.model.ValidationSeverity

/**
 * Regions' view of the shared [I18nService].
 *
 * One service with an empty `keyPrefix` serves every section — `gui.*`, `game.*`, the top-level
 * command messages — so each is addressed by its full key. Compared with the hand-rolled
 * [org.bukkit.configuration.file.YamlConfiguration] this replaced, that buys three things: a key
 * missing from the active locale falls back down the chain to `zh_CN` instead of rendering as its
 * own key path; one colour pipeline (MiniMessage) and one placeholder style (`<name>`) across the
 * whole file; and [MissingKeyMode] applies everywhere rather than only to chat.
 */
class LanguageManager(private val plugin: RegionsPlugin) : Reloadable {

    private val i18n: I18nService = I18nServices.create(
        plugin,
        I18nOptions.create()
            .languageDirectory("lang")
            .currentLocale { sanitizeLanguageName(plugin.config.getString("language", DEFAULT_LOCALE)) }
            .defaultLocale(DEFAULT_LOCALE)
            .fallbackLocales(listOf(DEFAULT_LOCALE))
            .bundledLocales(listOf(DEFAULT_LOCALE, "en_US"))
            .prefixKey("prefix")
            .prefixToken("<prefix>")
            // Empty on purpose: sections are addressed by full key, so one service covers them all.
            .keyPrefix("")
            .missingKeyMode(MissingKeyMode.RETURN_KEY)
            .placeholderStyles(listOf(PlaceholderStyle.MINIMESSAGE_TAG))
            .colorMode(ColorMode.MINIMESSAGE),
    )

    override fun reload() {
        i18n.reload()
        // 顶层 `locale-mode`（server|player）：旧配置没有该键时默认 server，保持既有行为。
        localeMode = plugin.config.getString("locale-mode", "server") ?: "server"
    }

    /**
     * PLAN.md §4.2：`language.locale-mode` 为 `player` 时按"手动选择（PDC）→ 客户端语言 →
     * 服务器语言"解析；`server` 模式与控制台一律用服务器语言。解析结果只作用于本次渲染，
     * 不改全局 `setCurrentLocale`。
     */
    fun localeFor(sender: CommandSender?): String {
        if (sender !is Player || !localeMode.equals("player", ignoreCase = true)) {
            return i18n.currentLocale()
        }
        playerSelectedLocale(sender)?.let { return it }
        normalizeClientLocale(sender.locale)?.let { return it }
        return i18n.currentLocale()
    }

    fun messageFor(sender: CommandSender?, key: String, placeholders: Map<String, String> = emptyMap()): String =
        i18n.message(key, localeFor(sender), placeholders)

    /** [component] rendered in [sender]'s locale — the GUI needs a Component per viewing player. */
    fun componentFor(sender: CommandSender?, key: String, placeholders: Map<String, String> = emptyMap()): Component =
        i18n.component(key, localeFor(sender), placeholders)

    /** [messageList] rendered in [sender]'s locale, with the same single-scalar fallback. */
    fun messageListFor(sender: CommandSender?, key: String, placeholders: Map<String, String> = emptyMap()): List<String> {
        val locale = localeFor(sender)
        val lines = i18n.messageList(key, locale, placeholders)
        if (lines.isNotEmpty()) return lines
        if (i18n.rawOrNull(key, locale) == null) return emptyList()
        return i18n.message(key, locale, placeholders).split(NEWLINE)
    }

    /** [label] rendered in [sender]'s locale. */
    fun labelFor(sender: CommandSender?, key: String, fallback: String): String =
        if (has(key)) messageFor(sender, key) else fallback

    /** [severityLabel] rendered in [sender]'s locale. */
    fun severityLabelFor(sender: CommandSender?, severity: ValidationSeverity): String =
        labelFor(sender, "labels.severity.${severity.name.lowercase()}", severity.name)

    /** [issueLine] rendered in [sender]'s locale. */
    fun issueLineFor(
        sender: CommandSender?,
        code: String,
        args: Map<String, String> = emptyMap(),
        fieldPath: String? = null,
        diagnostic: String = "",
    ): String {
        if (!has("errors.$code")) {
            return diagnostic.ifBlank { code }
        }
        val resolved = LinkedHashMap(args)
        val field = fieldPath ?: args["field"]
        if (field != null) {
            val leaf = field.substringAfterLast('.')
            // 字段译名有两种历史写法：`labels.field.*`（M1.2 起的规范位置）与 `labels.*`
            //（respawn/start/finish 这类早期词条）。先查前者，再退到后者，最后才是原样字段名——
            // 否则"缺少 respawn 的坐标"这种句子会把内部字段名露给玩家。
            resolved["field"] = when {
                has("labels.field.$leaf") -> messageFor(sender, "labels.field.$leaf")
                has("labels.$leaf") -> messageFor(sender, "labels.$leaf")
                else -> leaf
            }
        }
        return messageFor(sender, "errors.$code", resolved)
    }

    /** [resultReason] rendered in [sender]'s locale. */
    fun resultReasonFor(
        sender: CommandSender?,
        code: String?,
        args: Map<String, String>,
        reason: String,
    ): String {
        if (code != null) {
            return issueLineFor(sender, code, args, null, reason)
        }
        return if (reason.isNotBlank() && has(reason)) messageFor(sender, reason) else reason
    }

    /** 服务器是否允许玩家自选语言（`locale-mode: player`）。GUI 据此决定要不要显示语言按钮。 */
    fun playerLocaleEnabled(): Boolean = localeMode.equals("player", ignoreCase = true)

    /** 读取玩家手动语言选择；未选择返回 null（跟随客户端/服务器）。 */
    fun playerSelectedLocale(player: Player): String? =
        player.persistentDataContainer.get(localeKey, PersistentDataType.STRING)?.let { sanitizeLanguageName(it) }

    /** 保存玩家手动语言选择；传 null 即清除（auto）。 */
    fun setPlayerLocale(player: Player, locale: String?) {
        if (locale == null) {
            player.persistentDataContainer.remove(localeKey)
        } else {
            player.persistentDataContainer.set(localeKey, PersistentDataType.STRING, sanitizeLanguageName(locale))
        }
    }

    /** Alias kept for the plugin lifecycle's existing call sites. */
    fun load() = reload()

    fun message(key: String, placeholders: Map<String, String> = emptyMap()): String =
        i18n.message(key, placeholders)

    fun component(key: String, placeholders: Map<String, String> = emptyMap()): Component =
        i18n.component(key, placeholders)

    /**
     * Reads a key that holds a list of lines, used for item lore. A plain string is accepted too and
     * split on newlines, so a translator can collapse a short lore block into one scalar.
     */
    fun messageList(key: String, placeholders: Map<String, String> = emptyMap()): List<String> {
        val lines = i18n.messageList(key, placeholders)
        if (lines.isNotEmpty()) return lines
        if (i18n.rawOrNull(key) == null) return emptyList()
        return message(key, placeholders).split(NEWLINE)
    }

    fun has(key: String): Boolean = i18n.rawOrNull(key) != null

    /**
     * Stable identifier → display label, falling back to the raw id when no translation exists, so
     * nothing renders blank. Terms live under `labels.*` (see PLAN.md §4.1); ids inside configs and
     * commands stay stable and are only translated at this presentation boundary.
     */
    fun label(key: String, fallback: String): String =
        if (has(key)) message(key) else fallback

    /**
     * Renders one validation finding for a player: `errors.<code>` with [args], the [fieldPath]
     * leaf translated through `labels.field.*`. Falls back to the English [diagnostic] when the
     * code has no key yet — a missing translation must degrade to text, not to a key path.
     */
    fun issueLine(
        code: String,
        args: Map<String, String> = emptyMap(),
        fieldPath: String? = null,
        diagnostic: String = "",
    ): String = issueLineFor(null, code, args, fieldPath, diagnostic)

    fun severityLabel(severity: ValidationSeverity): String =
        label("labels.severity.${severity.name.lowercase()}", severity.name)

    /**
     * Renders a [ServiceResult] failure for players. A coded result renders from `errors.<code>`;
     * an uncoded reason that is itself a language key (the AuthorityDenial convention) resolves
     * through the dictionary; anything else falls back to the English diagnostic verbatim.
     */
    fun resultReason(code: String?, args: Map<String, String>, reason: String): String {
        if (code != null) {
            return issueLine(code, args, null, reason)
        }
        return if (reason.isNotBlank() && has(reason)) message(reason) else reason
    }

    /**
     * Parses text that is already rendered — the output of [message], possibly concatenated with
     * more of it — or that an operator wrote in `&`-code form in `regions.yml` / `templates.yml`.
     *
     * Trigger actions and GUI labels both need this: they assemble a line out of several fragments,
     * so they cannot use [component], but they still need a component at the display boundary.
     * Colouring first means one function covers both the `§` the service emits and the `&` an
     * operator types.
     */
    fun render(rendered: String): Component = i18n.componentOf(plugin.text().color(rendered))

    fun prefixed(key: String, placeholders: Map<String, String> = emptyMap()): String =
        message("prefix") + message(key, placeholders)

    fun send(sender: CommandSender, key: String, placeholders: Map<String, String> = emptyMap()) {
        val locale = localeFor(sender)
        sendRaw(sender, i18n.message("prefix", locale, emptyMap<String, String>()) + i18n.message(key, locale, placeholders))
    }

    /** Sends a translated message without the plugin prefix, for multi-line command output. */
    fun sendPlain(sender: CommandSender, key: String, placeholders: Map<String, String> = emptyMap()) {
        sendRaw(sender, i18n.message(key, localeFor(sender), placeholders))
    }

    fun sendRaw(sender: CommandSender, raw: String) {
        for (line in raw.split(NEWLINE)) {
            sender.sendMessage(render(line))
        }
    }

    private fun sanitizeLanguageName(configured: String?): String {
        if (configured.isNullOrBlank()) return DEFAULT_LOCALE
        val sanitized = configured.replace("[^A-Za-z0-9_-]".toRegex(), "")
        return if (sanitized.isBlank()) DEFAULT_LOCALE else sanitized
    }

    private val localeKey: NamespacedKey by lazy { NamespacedKey(plugin, "locale") }
    @Volatile
    private var localeMode: String = "server"

    companion object {
        const val DEFAULT_LOCALE = "zh_CN"
        val NEWLINE = Regex("\\R")

        /**
         * 客户端语言归一：本轮只承诺 `zh_CN`/`en_US`（PLAN.md §4.2），中文变体归一到 `zh_CN`，
         * 英语变体归一到 `en_US`；其余语言返回 null，回退服务器语言。
         */
        fun normalizeClientLocale(raw: String?): String? {
            val value = raw?.lowercase()?.replace('-', '_') ?: return null
            return when {
                value.startsWith("zh") -> "zh_CN"
                value.startsWith("en") -> "en_US"
                else -> null
            }
        }
    }
}
