package org.cubexmc.regions.gui

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.cubexmc.regions.match.JoinResult
import org.cubexmc.regions.match.MatchOutcome
import org.cubexmc.regions.match.MatchPhase
import org.cubexmc.regions.match.ParticipantState
import org.cubexmc.regions.mode.GamePhase
import org.cubexmc.regions.mode.GameStatus
import org.cubexmc.regions.mode.gameStatusLine
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionLifecycle

/**
 * 单页规则摘要的纯逻辑（PLAN.md §5.3：报名页明确同意装备暂存、单命/回合规则与退出后果）。
 * 每条规则是 (语言键, 参数)；输入只依赖 RegionDefinition，可测。
 */
internal object GameLobbyRules {

    fun ruleLines(region: RegionDefinition): List<Pair<String, Map<String, String>>> {
        val values = region.mode?.values ?: emptyMap()
        val lines = ArrayList<Pair<String, Map<String, String>>>()
        val replacesGear = values["replace-gear"]?.toBooleanStrictOrNull() == true ||
            !values["kit"].isNullOrBlank() ||
            !values["armor"].isNullOrBlank()
        lines += if (replacesGear) {
            "gui.game.rules.gear-escrow" to emptyMap()
        } else {
            "gui.game.rules.gear-own" to emptyMap()
        }
        when (region.mode?.type?.lowercase()) {
            "dual_pvp" -> {
                lines += "gui.game.rules.duel-life" to emptyMap()
                lines += "gui.game.rules.duel-rounds" to mapOf(
                    "rounds" to (if ((values["best-of"]?.toIntOrNull() ?: 1) >= 3) "3" else "1"),
                    "seconds" to (values["round-seconds"] ?: "180"),
                )
            }

            "union_war" -> {
                lines += "gui.game.rules.nation-life" to emptyMap()
                lines += "gui.game.rules.nation-roster" to mapOf(
                    "size" to (values["team-size"] ?: ((values["min-players"]?.toIntOrNull() ?: 10) / 2).toString()),
                )
                values["timeout-seconds"]?.let { lines += "gui.game.rules.timeout" to mapOf("seconds" to it) }
            }

            "free_for_all" -> {
                lines += "gui.game.rules.ffa-life" to emptyMap()
                values["timeout-seconds"]?.let { lines += "gui.game.rules.timeout" to mapOf("seconds" to it) }
            }

            "hide_and_seek" -> lines += "gui.game.rules.round" to mapOf("seconds" to (values["round-seconds"] ?: "300"))
            "run_race", "boat_race", "horse_race" ->
                values["timeout-seconds"]?.takeIf { it.toIntOrNull() != null }?.let {
                    lines += "gui.game.rules.timeout" to mapOf("seconds" to it)
                }
        }
        lines += "gui.game.rules.leave-warning" to emptyMap()
        values["min-players"]?.let { lines += "gui.game.rules.min-players" to mapOf("count" to it) }
        values["replace-gear"]?.let {
            lines += if (it.toBooleanStrictOrNull() == true) {
                "gui.game.rules.gear-restore" to emptyMap()
            } else {
                "gui.game.rules.gear-keep" to emptyMap()
            }
        }
        return lines
    }

    /** 该模式是否有"准备"动作；free_event 没有。 */
    fun hasReady(type: String?): Boolean = when (type?.lowercase()) {
        "dual_pvp", "union_war", "free_for_all", "run_race", "boat_race", "horse_race", "hide_and_seek" -> true
        else -> false
    }

    /** 报名按钮在当前状态下应该做什么。 */
    fun action(joined: Boolean, ready: Boolean, phase: MatchPhase?, eliminated: Boolean): LobbyAction = when {
        !joined && phase != null && phase != MatchPhase.WAITING && phase != MatchPhase.FINISHING && phase != MatchPhase.CLOSED ->
            LobbyAction.SPECTATE

        !joined -> LobbyAction.JOIN
        eliminated -> LobbyAction.LEAVE
        phase == MatchPhase.WAITING && ready -> LobbyAction.UNREADY
        phase == MatchPhase.WAITING -> LobbyAction.READY
        else -> LobbyAction.LEAVE
    }

    enum class LobbyAction { JOIN, READY, UNREADY, LEAVE, SPECTATE }
}

/** 场地报名页：单页规则、报名/准备/退出、阵容与结果（PLAN.md §5.3）。 */
internal class GameLobbyMenu(private val gui: RegionsGui) {
    private val plugin get() = gui.plugin
    private val text get() = gui.text

    fun open(player: Player, regionId: String, fromPage: Int = 0, filter: LobbyFilter = LobbyFilter()) {
        val region = plugin.regions().find(regionId) ?: return gui.lobby.open(player, fromPage, filter)
        if (!region.enabled || region.lifecycle != RegionLifecycle.PUBLISHED) {
            return gui.lobby.open(player, fromPage, filter)
        }
        openWith(player, region, fromPage, emptyList(), filter)
    }

    private fun openWith(
        player: Player,
        region: RegionDefinition,
        fromPage: Int,
        teamChoices: List<Pair<String, String>>,
        filter: LobbyFilter,
    ) {
        val holder = RegionsHolder(
            View.GAME_LOBBY,
            region.id,
            lobbyPage = fromPage,
            teamChoices = teamChoices,
            lobbyFilter = filter,
        )
        val inventory = Bukkit.createInventory(holder, 54, text.component(player, "gui.game.title", mapOf("name" to region.name)))
        val modeType = region.mode?.type ?: ""
        val participants = if (plugin.combatModes().isCombatMode(region)) {
            plugin.combatModes().participants(region.id)
        } else {
            emptyList()
        }
        inventory.setItem(
            4,
            text.named(
                gui.items.templateMaterial(modeType),
                text.text(player, "gui.lobby.entry.name", mapOf("name" to region.name)),
                text.lore(
                    player,
                    "gui.lobby.entry.lore",
                    mapOf(
                        "mode" to text.label(player, "labels.mode.$modeType", modeType),
                        "status" to plugin.gameStatusLine(player, statusOf(region)),
                    ),
                ),
            ),
        )
        GameLobbyRules.ruleLines(region).take(13).forEachIndexed { index, (key, args) ->
            inventory.setItem(19 + index, text.named(Material.PAPER, text.text(player, key, args)))
        }
        participants.take(9).forEachIndexed { index, participant ->
            val stateLabel = text.label(
                player,
                "labels.participant.${participant.state.name.lowercase()}",
                participant.state.name.lowercase(),
            )
            inventory.setItem(
                9 + index,
                text.named(
                    Material.PLAYER_HEAD,
                    text.text(player, "gui.game.participant.name", mapOf("player" to participant.name)),
                    text.lore(player, "gui.game.participant.lore", mapOf("state" to stateLabel)),
                ),
            )
        }
        if (GameLobbyRules.hasReady(modeType)) {
            val joined = participants.any { it.playerId == player.uniqueId }
            val action = GameLobbyRules.action(
                joined = joined,
                ready = participants.any { it.playerId == player.uniqueId && it.state == ParticipantState.READY },
                phase = phaseOf(region),
                eliminated = participants.any {
                    it.playerId == player.uniqueId &&
                        (it.state == ParticipantState.ELIMINATED || it.state == ParticipantState.LEFT)
                },
            )
            inventory.setItem(40, actionButton(player, action))
            // 退赛不该只存在于主按钮的某一个状态里：准备好之后主按钮是"取消准备"，
            // 没有这个按钮的话，玩家就只剩下敲命令一条路。
            if (joined && plugin.combatModes().isCombatMode(region) && action != GameLobbyRules.LobbyAction.LEAVE) {
                inventory.setItem(38, text.item(player, Material.BARRIER, "gui.game.leave"))
            }
        }
        if (teamChoices.isNotEmpty()) {
            teamChoices.take(7).forEachIndexed { index, (id, name) ->
                inventory.setItem(
                    28 + index,
                    text.named(
                        Material.WHITE_BANNER,
                        text.text(player, "gui.game.team.name", mapOf("team" to name)),
                        text.lore(player, "gui.game.team.lore"),
                    ),
                )
            }
        }
        resultOf(region)?.let { result ->
            inventory.setItem(
                44,
                text.named(
                    Material.GOLDEN_APPLE,
                    text.text(
                        player,
                        "gui.game.result.name",
                        mapOf("outcome" to text.label(player, "labels.outcome.${result.outcome.name.lowercase()}", result.outcome.name.lowercase())),
                    ),
                    text.lore(
                        player,
                        "gui.game.result.lore",
                        mapOf(
                            "reason" to text.label(player, result.reasonKey, result.reasonKey),
                            "reward" to text.label(player, "labels.reward.${result.rewardState.name.lowercase()}", result.rewardState.name.lowercase()),
                        ),
                    ),
                ),
            )
        }
        inventory.setItem(42, text.item(player, Material.ENDER_EYE, "gui.game.spectate"))
        inventory.setItem(49, text.named(GuiIcons.BACK, text.text(player, "gui.game.back-to-lobby")))
        player.openInventory(inventory)
    }

    private fun actionButton(player: Player, action: GameLobbyRules.LobbyAction): ItemStack = when (action) {
        GameLobbyRules.LobbyAction.JOIN -> text.item(player, Material.LIME_CONCRETE, "gui.game.join")
        GameLobbyRules.LobbyAction.READY -> text.item(player, Material.LIME_DYE, "gui.game.ready")
        GameLobbyRules.LobbyAction.UNREADY -> text.item(player, Material.GRAY_DYE, "gui.game.unready")
        GameLobbyRules.LobbyAction.LEAVE -> text.item(player, Material.RED_DYE, "gui.game.leave")
        GameLobbyRules.LobbyAction.SPECTATE -> text.item(player, Material.ENDER_EYE, "gui.game.spectate")
    }

    fun click(player: Player, holder: RegionsHolder, slot: Int) {
        val regionId = holder.regionId ?: return gui.lobby.open(player)
        val region = plugin.regions().find(regionId) ?: return gui.lobby.open(player)
        // 队伍选择按钮：点哪一支就用哪一支的稳定 ID 重新报名。
        if (slot in 28..34 && holder.teamChoices.isNotEmpty()) {
            val choice = holder.teamChoices.getOrNull(slot - 28) ?: return
            handleJoin(player, region, choice.first)
            return
        }
        when (slot) {
            38 -> leave(player, region, holder.lobbyFilter)
            40 -> primaryAction(player, region, holder.lobbyFilter)
            42 -> spectate(player, region, holder.lobbyFilter)
            49 -> gui.lobby.open(player, holder.lobbyPage, holder.lobbyFilter)
        }
    }

    /**
     * 报名页主按钮。七种玩法走**同一条**流程：报名 → 准备 → 取消准备 → 退出 / 观战。
     *
     * 此前只有战斗三兄弟有这套按钮，竞速与捉迷藏点主按钮只会调 `ready()`——
     * 而那两类玩法当时压根没有报名这一步（走进区域就算进场）。现在名单是显式的，
     * 按钮逻辑也就能共用一份。
     */
    private fun primaryAction(player: Player, region: RegionDefinition, filter: LobbyFilter) {
        if (!GameLobbyRules.hasReady(region.mode?.type)) return
        val state = participantStateOf(region, player)
        when (GameLobbyRules.action(
            joined = state != null,
            ready = state == ParticipantState.READY,
            phase = phaseOf(region),
            eliminated = state == ParticipantState.ELIMINATED || state == ParticipantState.LEFT,
        )) {
            GameLobbyRules.LobbyAction.JOIN -> handleJoin(player, region, null, filter)
            GameLobbyRules.LobbyAction.READY -> {
                readyFor(player, region)
                open(player, region.id, 0, filter)
            }

            GameLobbyRules.LobbyAction.UNREADY -> {
                if (!unreadyFor(player, region)) {
                    text.send(player, "game.match.unready.not-possible")
                }
                open(player, region.id, 0, filter)
            }

            GameLobbyRules.LobbyAction.LEAVE -> {
                leaveFor(player, region)
                open(player, region.id, 0, filter)
            }

            GameLobbyRules.LobbyAction.SPECTATE -> spectate(player, region, filter)
        }
    }

    // ---------------------------------------------------- 按玩法分派的报名册动作

    private fun participantStateOf(region: RegionDefinition, player: Player): ParticipantState? =
        when (region.mode?.type?.lowercase()) {
            "run_race", "boat_race", "horse_race" -> plugin.raceModes().participantState(region.id, player.uniqueId)
            "hide_and_seek" -> plugin.roundModes().participantState(region.id, player.uniqueId)
            "dual_pvp", "union_war", "free_for_all" ->
                plugin.combatModes().participants(region.id).firstOrNull { it.playerId == player.uniqueId }?.state
            else -> null
        }

    private fun readyFor(player: Player, region: RegionDefinition) {
        when (region.mode?.type?.lowercase()) {
            "run_race", "boat_race", "horse_race" -> plugin.raceModes().ready(player, region.id)
            "hide_and_seek" -> plugin.roundModes().ready(player, region.id)
            else -> plugin.combatModes().ready(player, region.id)
        }
    }

    private fun unreadyFor(player: Player, region: RegionDefinition): Boolean =
        when (region.mode?.type?.lowercase()) {
            "run_race", "boat_race", "horse_race" -> plugin.raceModes().unready(player, region.id)
            "hide_and_seek" -> plugin.roundModes().unready(player, region.id)
            else -> plugin.combatModes().unready(player, region.id)
        }

    private fun leaveFor(player: Player, region: RegionDefinition): Boolean =
        when (region.mode?.type?.lowercase()) {
            "run_race", "boat_race", "horse_race" -> plugin.raceModes().leave(player, region.id)
            "hide_and_seek" -> plugin.roundModes().leave(player, region.id)
            "dual_pvp", "union_war", "free_for_all" -> plugin.combatModes().leave(player, region.id)
            else -> false
        }

    private fun handleJoin(
        player: Player,
        region: RegionDefinition,
        teamId: String?,
        filter: LobbyFilter = LobbyFilter(),
    ) {
        val outcome = when (region.mode?.type?.lowercase()) {
            "run_race", "boat_race", "horse_race" -> plugin.raceModes().join(player, region.id)
            "hide_and_seek" -> plugin.roundModes().join(player, region.id)
            else -> plugin.combatModes().join(player, region.id, teamId)
        }
        when (val result = outcome) {
            is JoinResult.Joined -> open(player, region.id, 0, filter)
            is JoinResult.Rejected -> {
                text.send(player, result.key, result.args)
                open(player, region.id, 0, filter)
            }

            is JoinResult.TeamSelection -> openWith(
                player,
                region,
                0,
                result.candidates.map { it.id to it.name },
                filter,
            )
        }
    }

    /** 退赛。七种玩法都有报名名单了，所以这个按钮对每一种都有意义。 */
    private fun leave(player: Player, region: RegionDefinition, filter: LobbyFilter = LobbyFilter()) {
        leaveFor(player, region)
        open(player, region.id, 0, filter)
    }

    private fun spectate(player: Player, region: RegionDefinition, filter: LobbyFilter = LobbyFilter()) {
        val result = when (region.mode?.type?.lowercase()) {
            "run_race", "boat_race", "horse_race" -> plugin.raceModes().spectate(player, region.id)
            "hide_and_seek" -> plugin.roundModes().spectate(player, region.id)
            else -> plugin.combatModes().spectate(player, region.id)
        }
        if (result is org.cubexmc.regions.match.SpectateResult.Rejected) {
            text.send(player, result.key, result.args)
        }
        open(player, region.id, 0, filter)
    }

    private fun phaseOf(region: RegionDefinition): MatchPhase? {
        when (region.mode?.type?.lowercase()) {
            "run_race", "boat_race", "horse_race" -> return plugin.raceModes().phaseOf(region.id)
            "hide_and_seek" -> return plugin.roundModes().phaseOf(region.id)
            "dual_pvp", "union_war", "free_for_all" -> Unit
            else -> return null
        }
        val status = plugin.combatModes().status(region.id)
        return when (status.phase) {
            GamePhase.IDLE -> if (plugin.combatModes().result(region.id)?.outcome == MatchOutcome.ABORTED) {
                MatchPhase.CLOSED
            } else {
                null
            }

            GamePhase.WAITING -> MatchPhase.WAITING
            GamePhase.RUNNING -> MatchPhase.RUNNING
        }
    }

    /** 结果卡片对八种玩法读同一个 store（`free_event` 没有比赛，自然一直是 null）。 */
    private fun resultOf(region: RegionDefinition) =
        if (GameLobbyRules.hasReady(region.mode?.type)) plugin.matchStore().lastResult(region.id) else null

    private fun statusOf(region: RegionDefinition): GameStatus = when (region.mode?.type?.lowercase()) {
        "run_race", "boat_race", "horse_race" -> plugin.raceModes().status(region.id)
        "hide_and_seek" -> plugin.roundModes().status(region.id)
        "dual_pvp", "union_war", "free_for_all" -> plugin.combatModes().status(region.id)
        else -> GameStatus(region.id, region.mode?.type ?: "", GamePhase.IDLE)
    }
}
