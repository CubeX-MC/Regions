package org.cubexmc.regions.mode

import org.bukkit.command.CommandSender
import org.cubexmc.regions.RegionsPlugin

/**
 * Resolves a gameplay message from the active language file.
 *
 * Mode services broadcast to whoever is in the match rather than to one command sender, so they need
 * the resolved string rather than a send helper bound to a single recipient.
 */
internal fun RegionsPlugin.gameText(key: String, placeholders: Map<String, String> = emptyMap()): String =
    lang().message(key, placeholders)

/** Sends a line already resolved by [gameText] to one participant. */
internal fun RegionsPlugin.sendGame(recipient: CommandSender, message: String) {
    lang().sendRaw(recipient, message)
}

/**
 * 占位符值：一种是字面量（玩家名、数量、ID），另一种是**语言键**，必须在每个接收者自己的
 * locale 下解析（PLAN.md §4.2：广播不能先生成一条服务器语言的字符串再发给所有人）。
 *
 * 只有需要按接收者翻译的值才用 [Localized]，其余继续走字面量，避免把所有调用点都改一遍。
 */
internal sealed interface GameArg {
    data class Literal(val value: String) : GameArg
    data class Localized(val key: String) : GameArg

    companion object {
        fun literal(value: String): GameArg = Literal(value)
        fun key(key: String): GameArg = Localized(key)

        /** 全部按字面量处理，供只有字面量参数的调用方复用同一套广播函数。 */
        fun literals(values: Map<String, String>): Map<String, GameArg> =
            values.mapValues { Literal(it.value) }
    }
}

/**
 * Resolves [key] in the recipient's locale (PLAN.md §4.2) and sends it — the per-receiver form
 * broadcasts must use so Chinese and English players in one match each get their own language.
 */
internal fun RegionsPlugin.sendGame(recipient: CommandSender, key: String, placeholders: Map<String, String>) {
    lang().sendRaw(recipient, lang().messageFor(recipient, key, placeholders))
}

/**
 * 带按接收者解析的占位符版本：`reason`、`reward`、载具名这类值是语言键，
 * 必须每个接收者各解析一次，否则中英玩家在同一局里会看到对方的语言。
 */
internal fun RegionsPlugin.sendGameLocalized(recipient: CommandSender, key: String, placeholders: Map<String, GameArg>) {
    val resolved = LinkedHashMap<String, String>(placeholders.size)
    for ((name, arg) in placeholders) {
        resolved[name] = when (arg) {
            is GameArg.Literal -> arg.value
            is GameArg.Localized -> lang().messageFor(recipient, arg.key)
        }
    }
    lang().sendRaw(recipient, lang().messageFor(recipient, key, resolved))
}

/** A region's configured mode type, or the empty string when the region no longer exists. */
internal fun RegionsPlugin.modeTypeOf(regionId: String): String =
    regions().find(regionId)?.mode?.type ?: ""

/**
 * Renders a [GameStatus] for the active locale. The phase and every counter name come from
 * `labels.state.*` / `labels.counter.*`, so no service-internal English leaks into chat.
 */
internal fun RegionsPlugin.gameStatusLine(status: GameStatus): String = gameStatusLine(null, status)

/**
 * [gameStatusLine] rendered in [viewer]'s locale（PLAN.md §4.2）：大厅卡片是给一个玩家看的，
 * 状态与计数名必须按该玩家的语言解析；`viewer` 为 null（控制台／非玩家接收者）时回退服务器语言。
 */
internal fun RegionsPlugin.gameStatusLine(viewer: CommandSender?, status: GameStatus): String {
    val lang = lang()
    val base = when (status.phase) {
        GamePhase.IDLE -> lang.messageFor(viewer, "labels.state.idle")
        GamePhase.WAITING -> lang.messageFor(
            viewer,
            "labels.state.waiting",
            mapOf("ready" to status.ready.toString(), "required" to status.players.toString()),
        )
        GamePhase.RUNNING -> lang.messageFor(viewer, "labels.state.running", mapOf("players" to status.players.toString()))
    }
    if (status.extra.isEmpty()) {
        return base
    }
    val detail = status.extra.entries.joinToString(" ") { (id, value) ->
        "${lang.labelFor(viewer, "labels.counter.$id", id)} $value"
    }
    return "$base ($detail)"
}
