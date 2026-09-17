package org.cubexmc.regions.match

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.inventory.ItemStack
import org.cubexmc.regions.RegionsPlugin
import org.cubexmc.regions.mode.CombatGearStore
import org.cubexmc.regions.mode.GamePhase
import org.cubexmc.regions.mode.GameStatus
import org.cubexmc.regions.mode.GameArg
import org.cubexmc.regions.mode.sendGame
import org.cubexmc.regions.mode.sendGameLocalized
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionLifecycle
import org.cubexmc.regions.model.RegionTrigger
import org.cubexmc.regions.model.UnionRef
import org.cubexmc.regions.reward.FundingSettlement
import org.cubexmc.scheduler.CubexTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** 报名结果；每一步拒绝都带稳定的语言键，命令与 GUI 共用同一份判定。 */
sealed interface JoinResult {
    data object Joined : JoinResult

    /** 工会战：玩家同时属于多个 Nation，必须先明确选边（PLAN.md §7.2）。 */
    data class TeamSelection(val candidates: List<UnionRef>) : JoinResult

    data class Rejected(val key: String, val args: Map<String, String> = emptyMap()) : JoinResult
}

/** 观战结果。 */
sealed interface SpectateResult {
    data object Joined : SpectateResult
    data class Rejected(val key: String, val args: Map<String, String> = emptyMap()) : SpectateResult
}

/**
 * 三类战斗共用的比赛协调器（PLAN.md §6）。
 *
 * 职责：状态机（WAITING→PREPARING→COUNTDOWN→RUNNING→INTERMISSION→FINISHING→CLOSED）、
 * roster 与阵容锁定、装备托管屏障、计时、伤害隔离所需的成员查询，以及崩溃可恢复的清理协议。
 *
 * 线程模型：所有公开入口都在同一条锁下改变状态；实体访问（发装备、传送、写背包、发消息）
 * 一律经 `CubexScheduler` 回到玩家所属线程，任务执行前重新校验 matchId 与成员关系。
 */
class CombatMatchCoordinator(
    private val plugin: RegionsPlugin,
    private val gearStore: CombatGearStore,
    private val matchStore: MatchStore,
    /**
     * 单调耗时时钟（毫秒）。回合超时、离场宽限与结算观察窗口都基于它——
     * 墙钟被 NTP 校正时这些判定不能跟着跳（PLAN.md §6.2「用单调时钟」）。
     * 落盘用的时间戳另走 [System.currentTimeMillis]。
     */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val runtimes: ConcurrentHashMap<String, MatchRuntime> = ConcurrentHashMap()
    private val endingRegions: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** 上一场的结果留在内存里给结果页用；重启后由 MatchStore 的历史记录负责。 */
    private val recentResults: ConcurrentHashMap<String, MatchResult> = ConcurrentHashMap()
    private val pendingRespawnRestores: ConcurrentHashMap<UUID, CombatGearStore.StoredGear> = ConcurrentHashMap()
    private val entryPrompts: ConcurrentHashMap<String, Long> = ConcurrentHashMap()
    private val lastDisplay: ConcurrentHashMap<UUID, String> = ConcurrentHashMap()
    private var tickTask: CubexTask? = null

    /** 停服/重载清理期间为 true：实体任务就地执行，避免调度器已经不再收任务。 */
    @Volatile
    private var forceSynchronousRestores = false

    // ---------------------------------------------------------------- 入场与报名

    /**
     * 玩家进入场地。**进入区域不等于同意参赛**（PLAN.md §5.3）：这里只发一次带冷却的参与提示，
     * 报名必须走 [join]。
     */
    @Synchronized
    fun onEnter(player: Player, region: RegionDefinition): Boolean {
        if (MatchRulesCatalog.rulesFor(region.mode?.type) == null) return false
        val runtime = runtimes[region.id]
        if (runtime == null) {
            promptJoin(player, region)
            return true
        }
        if (runtime.phase == MatchPhase.FINISHING || runtime.phase == MatchPhase.CLOSED) {
            plugin.sendGame(player, "game.combat.restoring", mapOf("name" to region.name))
            return true
        }
        if (runtime.participants.containsKey(player.uniqueId)) {
            // 正式战斗中的短暂离场允许回到场上（宽限见 [tick]）。
            runtime.pendingLeave.remove(player.uniqueId)
            plugin.sendGame(
                player,
                "game.match.rejoin",
                mapOf("name" to region.name, "state" to plugin.lang().label("labels.state.${phaseLabel(runtime.phase)}", phaseLabel(runtime.phase))),
            )
            return true
        }
        if (runtime.spectators.contains(player.uniqueId)) return true
        if (runtime.phase != MatchPhase.WAITING) {
            plugin.sendGame(player, "game.combat.in-progress", mapOf("name" to region.name))
            return true
        }
        if (runtime.settings.maxParticipants in 1 until runtime.participants.size) {
            plugin.sendGame(player, "game.combat.full", mapOf("name" to region.name))
            return true
        }
        promptJoin(player, region)
        return true
    }

    private fun promptJoin(player: Player, region: RegionDefinition) {
        val cooldownMillis = configLong("modes.entry-prompt-cooldown-seconds", DEFAULT_PROMPT_COOLDOWN) * 1000L
        val key = "${player.uniqueId}:${region.id}"
        val now = clock()
        val previous = entryPrompts[key]
        if (previous != null && now - previous < cooldownMillis) return
        entryPrompts[key] = now
        plugin.sendGame(player, "game.match.enter-prompt", mapOf("name" to region.name, "id" to region.id))
    }

    /** 明确报名：只有这一步才是"同意参赛"（含装备暂存与退出后果）。 */
    @Synchronized
    fun join(player: Player, regionId: String, teamId: String? = null): JoinResult {
        val region = plugin.regions().find(regionId)
            ?: return JoinResult.Rejected("game.match.join.unknown-region", mapOf("id" to regionId))
        if (!region.enabled || region.lifecycle != RegionLifecycle.PUBLISHED) {
            return JoinResult.Rejected("game.match.join.unavailable")
        }
        val rules = MatchRulesCatalog.rulesFor(region.mode?.type)
            ?: return JoinResult.Rejected("game.match.join.not-a-match")
        val runtime = runtimeFor(region, rules)
        if (runtime.phase != MatchPhase.WAITING) {
            return JoinResult.Rejected("game.match.join.in-progress")
        }
        if (runtime.participants.containsKey(player.uniqueId)) {
            return JoinResult.Rejected("game.match.join.already-joined")
        }
        // 装备还在别人（可能是上一局）的托管里：先恢复完再报名，否则新局会把旧快照写回去。
        if (pendingRestoreAnywhere(player.uniqueId)) {
            return JoinResult.Rejected("game.match.join.restoring")
        }
        val settings = runtime.settings
        if (settings.maxParticipants > 0 && runtime.participants.size >= settings.maxParticipants) {
            return JoinResult.Rejected("game.match.join.full")
        }
        val team = when (settings.teamUnit) {
            TeamUnit.PLAYER -> player.uniqueId.toString()
            TeamUnit.NATION -> when (val resolved = resolveNationTeam(player, runtime, teamId)) {
                is NationSelection.Selected -> resolved.id
                is NationSelection.Choose -> return JoinResult.TeamSelection(resolved.candidates)
                is NationSelection.Refuse -> return JoinResult.Rejected(resolved.key, resolved.args)
            }
        }
        if (settings.requireVerifiedEnemyRelation && settings.teamUnit == TeamUnit.NATION) {
            diplomacyProblem(runtime)?.let { return JoinResult.Rejected(it) }
        }
        if (settings.teamUnit == TeamUnit.NATION) {
            val members = runtime.participants.values.count { it.teamId == team }
            if (settings.teamSize > 0 && members >= settings.teamSize) {
                return JoinResult.Rejected("game.match.join.team-full")
            }
            runtime.teams.putIfAbsent(team, TeamSnapshot(team, runtime.teamNames[team] ?: team, team))
        } else {
            runtime.teams.putIfAbsent(team, TeamSnapshot(team, player.name))
        }
        runtime.participants[player.uniqueId] = Participant(
            playerId = player.uniqueId,
            name = player.name,
            teamId = team,
            // `require-ready: false` 保留历史语义：报名即视为准备。
            state = if (settings.requireReady) ParticipantState.WAITING else ParticipantState.READY,
        )
        persist(runtime)
        plugin.sendGame(player, "game.match.join.ok", mapOf("name" to region.name))
        broadcast(
            runtime,
            "game.match.join.broadcast",
            mapOf(
                "player" to player.name,
                "current" to runtime.participants.size.toString(),
                "required" to settings.minParticipants.toString(),
            ),
        )
        if (startCondition(runtime) == null) beginPreparing(runtime) else broadcastPreparation(runtime)
        return JoinResult.Joined
    }

    private sealed interface NationSelection {
        data class Selected(val id: String) : NationSelection
        data class Choose(val candidates: List<UnionRef>) : NationSelection
        data class Refuse(val key: String, val args: Map<String, String> = emptyMap()) : NationSelection
    }

    /**
     * Nation 身份解析（PLAN.md §7.2）：枚举玩家拥有或加入的全部 Land 的非空 Nation，
     * 按稳定 ID 去重；一个候选自动选中，多个候选必须明确选边，没有候选拒绝报名。
     */
    private fun resolveNationTeam(player: Player, runtime: MatchRuntime, requested: String?): NationSelection {
        val provider = plugin.unions().active()
            ?: return NationSelection.Refuse("game.match.join.union-unavailable")
        provider.unavailableReason()?.let { reason ->
            return NationSelection.Refuse("game.match.join.union-unavailable", mapOf("reason" to reason))
        }
        val candidates = provider.getUnions(player.uniqueId)
        if (candidates.isEmpty()) {
            return NationSelection.Refuse("game.match.join.need-nation")
        }
        val locked = listOfNotNull(runtime.options[OPTION_TEAM_A], runtime.options[OPTION_TEAM_B])
        candidates.forEach { runtime.teamNames.putIfAbsent(it.id, it.name) }
        if (requested != null) {
            val match = candidates.firstOrNull { it.id == requested }
                ?: return NationSelection.Refuse("game.match.join.team-unknown", mapOf("team" to requested))
            if (locked.isNotEmpty() && match.id !in locked) {
                return NationSelection.Refuse("game.match.join.team-not-in-match", mapOf("team" to match.name))
            }
            return NationSelection.Selected(match.id)
        }
        val eligible = if (locked.isEmpty()) candidates else candidates.filter { it.id in locked }
        if (eligible.isEmpty()) {
            return NationSelection.Refuse(
                "game.match.join.team-not-in-match",
                mapOf("team" to candidates.joinToString { it.name }),
            )
        }
        if (eligible.size == 1) return NationSelection.Selected(eligible.single().id)
        return NationSelection.Choose(eligible)
    }

    /**
     * 场地主在开报名时选定本场双方 Nation（比赛选项，不改已发布的场地规则，PLAN.md §7.2）。
     * 列出过任何一支队伍后再改会被拒绝，避免把已经选边的玩家混进新对阵。
     */
    @Synchronized
    fun selectTeams(regionId: String, nationA: String, nationB: String, nameA: String?, nameB: String?): Boolean {
        val region = plugin.regions().find(regionId) ?: return false
        if (MatchRulesCatalog.rulesFor(region.mode?.type)?.settings(region)?.teamUnit != TeamUnit.NATION) return false
        val runtime = runtimeFor(region, NationBattleRules)
        if (runtime.phase != MatchPhase.WAITING || runtime.participants.isNotEmpty()) return false
        if (nationA == nationB) return false
        runtime.options[OPTION_TEAM_A] = nationA
        runtime.options[OPTION_TEAM_B] = nationB
        runtime.teamNames[nationA] = nameA ?: nationA
        runtime.teamNames[nationB] = nameB ?: nationB
        persist(runtime)
        return true
    }

    /** 当前锁定的双方 Nation；未选择时为空。 */
    @Synchronized
    fun selectedTeams(regionId: String): Pair<String?, String?> {
        val runtime = runtimes[regionId] ?: return null to null
        return runtime.options[OPTION_TEAM_A] to runtime.options[OPTION_TEAM_B]
    }

    @Synchronized
    fun ready(player: Player, regionId: String): Boolean {
        val region = plugin.regions().find(regionId) ?: return false
        if (MatchRulesCatalog.rulesFor(region.mode?.type) == null) {
            plugin.sendGame(player, "game.match.ready.not-a-match", emptyMap())
            return true
        }
        val runtime = runtimes[regionId]
        if (runtime == null || !runtime.participants.containsKey(player.uniqueId)) {
            plugin.sendGame(player, "game.match.ready.not-joined", emptyMap())
            return true
        }
        if (runtime.phase != MatchPhase.WAITING) {
            plugin.sendGame(player, "game.combat.already-started", emptyMap())
            return true
        }
        val participant = runtime.participants.getValue(player.uniqueId)
        if (participant.state != ParticipantState.READY) {
            participant.state = ParticipantState.READY
            broadcast(
                runtime,
                "game.combat.ready",
                mapOf(
                    "player" to player.name,
                    "current" to runtime.participants.values.count { it.state == ParticipantState.READY }.toString(),
                    "total" to runtime.participants.size.toString(),
                ),
            )
        }
        persist(runtime)
        if (startCondition(runtime) == null) beginPreparing(runtime) else broadcastPreparation(runtime, player)
        return true
    }

    /** 取消准备：只影响自己的名单记录，已经开赛的比赛不受影响（PLAN.md §5.4）。 */
    @Synchronized
    fun unready(player: Player, regionId: String): Boolean {
        val runtime = runtimes[regionId] ?: return false
        if (runtime.phase != MatchPhase.WAITING) return false
        val participant = runtime.participants[player.uniqueId] ?: return false
        if (participant.state != ParticipantState.READY) return false
        participant.state = ParticipantState.WAITING
        persist(runtime)
        broadcast(
            runtime,
            "game.match.unready",
            mapOf("player" to participant.name, "current" to runtime.participants.values.count { it.state == ParticipantState.READY }.toString()),
        )
        return true
    }

    /**
     * 明确退出（`/regions game <id> leave`）：报名阶段直接退报名；正式战斗中是弃权，
     * 立即淘汰。失去参与权限的玩家也能走这条通道退出并恢复装备。
     */
    @Synchronized
    fun leave(player: Player, regionId: String): Boolean {
        val runtime = runtimes[regionId] ?: return false
        if (!runtime.participants.containsKey(player.uniqueId)) {
            return runtime.spectators.remove(player.uniqueId)
        }
        onLeave(player, regionId, "leave-command")
        return true
    }

    /** 只需等待、暂不参赛的玩家进入观战名单：不能伤害、不能被伤害，可随时退出。 */
    @Synchronized
    fun spectate(player: Player, regionId: String): SpectateResult {
        val runtime = runtimes[regionId] ?: return SpectateResult.Rejected("game.match.spectate.no-match")
        if (runtime.participants.containsKey(player.uniqueId)) {
            return SpectateResult.Rejected("game.match.spectate.is-participant")
        }
        runtime.spectators.add(player.uniqueId)
        plugin.sendGame(player, "game.match.spectate.joined", mapOf("id" to regionId))
        // PLAN.md §5.4：观战是"到配置的场外观战点"，本轮不依赖跨世界自由旁观传送。
        regionOf(runtime)?.let { region ->
            outsideLocation(region)?.let { spot -> plugin.regionScheduler().teleportAsync(player, spot) }
        }
        return SpectateResult.Joined
    }

    /** 报名/退出的统一离开入口；正式战斗中的离场按弃权处理（宽限后淘汰）。 */
    @Synchronized
    fun onLeave(player: Player, regionId: String, reason: String) {
        val runtime = runtimes[regionId] ?: return
        runtime.spectators.remove(player.uniqueId)
        val participant = runtime.participants[player.uniqueId] ?: return
        when (runtime.phase) {
            MatchPhase.WAITING -> {
                runtime.participants.remove(player.uniqueId)
                persist(runtime)
                broadcast(runtime, "game.match.join.leave", mapOf("player" to player.name))
                if (runtime.participants.isEmpty()) close(runtime, "empty")
            }

            MatchPhase.PREPARING, MatchPhase.COUNTDOWN -> abortStart(runtime, reason)

            MatchPhase.RUNNING -> {
                if (reason == REASON_QUIT || reason == REASON_KICK) {
                    eliminate(runtime, participant.playerId, "game.match.reason.disconnect", null)
                } else {
                    // 主动离场：给宽限时间回到场上，超时才按弃权淘汰。
                    runtime.pendingLeave[participant.playerId] = clock()
                }
            }

            MatchPhase.INTERMISSION -> eliminate(runtime, participant.playerId, "game.match.reason.left", null)

            MatchPhase.FINISHING, MatchPhase.CLOSED -> Unit
        }
    }

    /**
     * 断线/踢出：正式战斗中立即按弃权淘汰（PLAN.md §7.1/§7.3），报名与候场阶段退报名。
     *
     * 不能只依赖会话清理：`safety.cleanup-on-quit` 可以被服主关掉，那时会话仍在，
     * 比赛不能留着一个已经离线的人当"存活选手"。
     */
    @Synchronized
    fun onDisconnect(player: Player, reason: String) {
        for (runtime in runtimes.values.toList()) {
            if (!runtime.participants.containsKey(player.uniqueId) && !runtime.spectators.contains(player.uniqueId)) {
                continue
            }
            runtime.pendingLeave.remove(player.uniqueId)
            onLeave(player, runtime.regionId, reason)
        }
    }

    @Synchronized
    fun forceEnd(regionId: String, reason: String, actor: String? = null): Boolean {
        val runtime = runtimes[regionId] ?: return false
        if (runtime.phase == MatchPhase.FINISHING || runtime.phase == MatchPhase.CLOSED) return false
        if (runtime.phase == MatchPhase.WAITING) {
            broadcast(runtime, "game.combat.ended", emptyMap())
            close(runtime, reason)
            return true
        }
        finalize(
            runtime,
            MatchResolution(
                outcome = MatchOutcome.ABORTED,
                reasonKey = "game.match.reason.forced",
                reasonArgs = reasonArgs(reason),
            ),
            actor,
            reason,
        )
        return true
    }

    // ---------------------------------------------------------------- 死亡、重生与淘汰

    @Synchronized
    fun onDeath(event: PlayerDeathEvent): Boolean {
        val player = event.entity
        val runtime = runtimeOf(player.uniqueId) ?: return false
        val participant = runtime.participants[player.uniqueId] ?: return false
        if (runtime.phase != MatchPhase.RUNNING || participant.state != ParticipantState.ALIVE) {
            // 候场/观战/恢复阶段的死亡不属于本局胜负，交给原有会话清理路径。
            return false
        }
        val snapshot = gearStore.peek(player.uniqueId)
        if (snapshot != null) {
            pendingRespawnRestores[player.uniqueId] = snapshot
            event.drops.clear()
            event.droppedExp = 0
        }
        eliminate(runtime, player.uniqueId, "game.match.reason.death", player.killer?.name)
        return true
    }

    @Synchronized
    fun onRespawn(event: PlayerRespawnEvent) {
        val player = event.player
        val runtime = runtimeOf(player.uniqueId)
        if (runtime == null) {
            pendingRespawnRestores.remove(player.uniqueId)?.let { restoreSnapshot(player, it) }
            return
        }
        val participant = runtime.participants[player.uniqueId]
        if (participant != null && runtime.phase != MatchPhase.FINISHING && runtime.phase != MatchPhase.CLOSED) {
            // 还有下一回合：不恢复原装备，重生到自己的出生点后重新发本场装备。
            pendingRespawnRestores.remove(player.uniqueId)
            spawnLocation(runtime, participant)?.let { event.respawnLocation = it }
            scheduleEntity(player) {
                if (runtimes[runtime.regionId] !== runtime) return@scheduleEntity
                regionOf(runtime)?.let { applyKit(player, it) }
            }
            return
        }
        pendingRespawnRestores.remove(player.uniqueId)?.let { snapshot ->
            restoreSnapshot(player, snapshot)
            snapshot.respawn?.let { event.respawnLocation = it }
        }
    }

    @Synchronized
    private fun eliminate(runtime: MatchRuntime, playerId: UUID, reasonKey: String, killerName: String?) {
        val participant = runtime.participants[playerId] ?: return
        if (participant.state == ParticipantState.ELIMINATED || participant.state == ParticipantState.LEFT) return
        runtime.eliminationCounter += 1
        participant.state = ParticipantState.ELIMINATED
        participant.eliminatedOrder = runtime.eliminationCounter
        participant.eliminationReason = reasonKey
        persist(runtime)
        val player = plugin.server.getPlayer(playerId)
        if (player != null) {
            plugin.sendGameLocalized(
                player,
                "game.match.eliminated",
                mapOf(
                    "name" to GameArg.literal(participant.name),
                    "reason" to GameArg.key(reasonKey),
                    "killer" to GameArg.literal(killerName ?: ""),
                ),
            )
            val region = regionOf(runtime)
            val out = region?.let { outsideLocation(it) }
            if (out != null) plugin.regionScheduler().teleportAsync(player, out)
        }
        broadcast(
            runtime,
            "game.match.eliminated.broadcast",
            mapOf(
                "player" to participant.name,
                "alive" to runtime.participants.values.count { it.state == ParticipantState.ALIVE }.toString(),
            ),
        )
        scheduleResolution(runtime)
    }

    /**
     * 结算观察窗口（PLAN.md §6.2）：成员变化后不立刻判胜负，等 [SETTLEMENT_WINDOW_MILLIS]
     * 把同一批死亡／退出事件收齐再判定。没有它，范围伤害同归于尽会被读成"先死的一方输"。
     * 窗口内重复触发只排一次。
     */
    @Synchronized
    private fun scheduleResolution(runtime: MatchRuntime) {
        if (runtime.phase != MatchPhase.RUNNING || runtime.resolutionScheduled) return
        runtime.resolutionScheduled = true
        val matchId = runtime.matchId
        val regionId = runtime.regionId
        plugin.regionScheduler().runGlobalLater(
            Runnable { runScheduledResolution(regionId, matchId) },
            SETTLEMENT_WINDOW_TICKS,
        )
    }

    @Synchronized
    private fun runScheduledResolution(regionId: String, matchId: UUID) {
        val runtime = runtimes[regionId] ?: return
        if (runtime.matchId != matchId) return
        runtime.resolutionScheduled = false
        resolveRoundIfDecided(runtime)
    }

    // ---------------------------------------------------------------- 状态机

    /** 返回阻塞开赛的原因键；null 表示阵容与准备都满足。 */
    private fun startCondition(runtime: MatchRuntime): String? {
        val settings = runtime.settings
        if (runtime.participants.size < settings.minParticipants) return "game.match.waiting-players"
        if (settings.maxParticipants > 0 && runtime.participants.size > settings.maxParticipants) {
            return "game.match.waiting-roster"
        }
        if (runtime.participants.values.any { it.state == ParticipantState.WAITING }) return "game.match.waiting-ready"
        if (settings.teamUnit == TeamUnit.NATION) {
            val locked = listOfNotNull(runtime.options[OPTION_TEAM_A], runtime.options[OPTION_TEAM_B])
            if (locked.size != 2) return "game.match.waiting-teams"
            if (settings.requireVerifiedEnemyRelation) {
                val problem = diplomacyProblem(runtime)
                if (problem != null) return problem
            }
            val counts = runtime.participants.values.groupingBy { it.teamId }.eachCount()
            if (counts.size != 2) return "game.match.waiting-teams"
            if (settings.teamSize > 0 && counts.values.any { it != settings.teamSize }) return "game.match.waiting-roster"
        }
        return null
    }

    /**
     * 高级 `enemy-only` 的外交前置条件（PLAN.md §7.2）：只接受**已验证**的敌对关系。
     * 提供方无法验证时返回拒绝键，绝不把"未知"当成"非敌对"放行，也不修改任何外交关系。
     */
    private fun diplomacyProblem(runtime: MatchRuntime): String? {
        if (!runtime.settings.requireVerifiedEnemyRelation) return null
        val teamA = runtime.options[OPTION_TEAM_A] ?: return "game.match.waiting-teams"
        val teamB = runtime.options[OPTION_TEAM_B] ?: return "game.match.waiting-teams"
        val provider = plugin.unions().active() ?: return "game.match.diplomacy.unverifiable"
        return when (provider.areNationsEnemy(teamA, teamB)) {
            true -> null
            false -> "game.match.diplomacy.not-enemies"
            null -> "game.match.diplomacy.unverifiable"
        }
    }

    private fun broadcastPreparation(runtime: MatchRuntime, only: Player? = null) {
        val key = startCondition(runtime) ?: return
        val args = mapOf(
            "current" to runtime.participants.size.toString(),
            "required" to runtime.settings.minParticipants.toString(),
            "waiting" to runtime.participants.values
                .filter { it.state == ParticipantState.WAITING }
                .joinToString { it.name },
        )
        if (only != null) plugin.sendGame(only, key, args) else broadcast(runtime, key, args)
    }

    /**
     * PREPARING 屏障：资金与奖励身份映射 → 逐人持久化 escrow → 发装备与传送 → 收齐回执才倒计时。
     * 任何一名玩家失败都撤销本次开赛（PLAN.md §6.2）。
     */
    @Synchronized
    private fun beginPreparing(runtime: MatchRuntime) {
        val region = regionOf(runtime)
        if (region == null) {
            abortStart(runtime, "region-missing")
            return
        }
        runtime.phase = MatchPhase.PREPARING
        persist(runtime)
        broadcast(runtime, "game.match.preparing", emptyMap())

        if (runtime.settings.allowFunding && hasFunding(region)) {
            if (!lockNationParties(runtime, region)) {
                abortStart(runtime, "funding-mapping")
                return
            }
            val funding = plugin.rewards().reserve(region)
            if (!funding.successful) {
                plugin.log().warn(
                    "Could not reserve Contract funding for ${region.id} (${funding.code}): ${funding.detail}",
                )
                abortStart(runtime, "funding:${funding.code}")
                return
            }
        }

        // 屏障自身也要有上限：实体任务没回来（Folia 区域未加载、任务被丢弃）时不能永远挂着。
        val matchId = runtime.matchId
        val regionId = runtime.regionId
        plugin.regionScheduler().runGlobalLater(
            Runnable { preparingTimedOut(regionId, matchId) },
            runtime.settings.preparingSeconds * 20L,
        )

        val pending = AtomicInteger(runtime.participants.size)
        val failures = AtomicInteger(0)
        for (playerId in runtime.participants.keys.toList()) {
            val player = plugin.server.getPlayer(playerId)
            if (player == null) {
                failures.incrementAndGet()
                if (pending.decrementAndGet() == 0) finishPreparing(runtime, failures.get())
                continue
            }
            scheduleEntity(player) {
                val ok = synchronized(this) {
                    if (runtimes[runtime.regionId] !== runtime || runtime.phase != MatchPhase.PREPARING) {
                        return@synchronized null
                    }
                    if (!runtime.participants.containsKey(playerId)) return@synchronized null
                    val prepared = runCatching { prepareParticipant(player, runtime, region) }
                        .onFailure { error ->
                            plugin.log().severe("Failed to prepare ${player.name} for ${region.id}: ${error.message}")
                        }
                        .getOrDefault(false)
                    runtime.prepared.add(playerId)
                    prepared
                } ?: return@scheduleEntity
                if (!ok) failures.incrementAndGet()
                if (pending.decrementAndGet() == 0) finishPreparing(runtime, failures.get())
            }
        }
        if (runtime.participants.isEmpty()) finishPreparing(runtime, 0)
    }

    /**
     * 开赛前把合同的两个签署方唯一映射到本场两个 Nation（PLAN.md §7.2「奖励兼容」）。
     * 无法唯一映射就拒绝开赛，绝不按当前成员关系猜测收款人。
     */
    private fun lockNationParties(runtime: MatchRuntime, region: RegionDefinition): Boolean {
        if (runtime.settings.teamUnit != TeamUnit.NATION) return true
        val teamA = runtime.options[OPTION_TEAM_A] ?: return false
        val teamB = runtime.options[OPTION_TEAM_B] ?: return false
        val check = plugin.rewards().check(region)
        if (!check.successful) return false
        val parties = listOfNotNull(check.partyA, check.partyB).distinct()
        if (parties.size != 2) {
            plugin.log().warn("Nation battle ${region.id} requires two distinct WAGER parties; found ${parties.size}.")
            return false
        }
        val provider = plugin.unions().active() ?: return false
        val mapping = LinkedHashMap<String, UUID>()
        for (party in parties) {
            val matched = provider.getUnions(party).map { it.id }.filter { it == teamA || it == teamB }.distinct()
            if (matched.size != 1) {
                plugin.log().warn("WAGER party $party does not map to exactly one of the two nations in ${region.id}.")
                return false
            }
            mapping[matched.single()] = party
        }
        if (mapping.keys != setOf(teamA, teamB)) return false
        runtime.nationParties.clear()
        runtime.nationParties.putAll(mapping)
        persist(runtime)
        return true
    }

    /** 单名玩家的开赛准备；返回 false 表示这名玩家失败，本次开赛必须整体撤销。 */
    private fun prepareParticipant(player: Player, runtime: MatchRuntime, region: RegionDefinition): Boolean {
        if (shouldReplaceGear(region)) {
            val snapshot = GearSnapshot.capture(player, outsideLocation(region))
            gearStore.put(player.uniqueId, region.id, snapshot)
            runtime.escrowed.add(player.uniqueId)
            applyKit(player, region)
        }
        val participant = runtime.participants[player.uniqueId] ?: return false
        spawnLocation(runtime, participant)?.let { spawn -> plugin.regionScheduler().teleportAsync(player, spawn) }
        plugin.sendGame(player, "game.match.gear-ready", emptyMap())
        return true
    }

    /** 屏障超时：点名还没回执的玩家，然后走统一清理（不会留下一半人在场上的比赛）。 */
    @Synchronized
    private fun preparingTimedOut(regionId: String, matchId: UUID) {
        val runtime = runtimes[regionId] ?: return
        if (runtime.matchId != matchId || runtime.phase != MatchPhase.PREPARING) return
        val missing = runtime.participants.values
            .filterNot { runtime.prepared.contains(it.playerId) }
            .joinToString { it.name }
        plugin.log().warn(
            "Preparing ${runtime.regionId} exceeded ${runtime.settings.preparingSeconds}s; " +
                "aborting the start. Players without a receipt: ${missing.ifBlank { "none" }}",
        )
        abortStart(runtime, "prepare-timeout")
    }

    @Synchronized
    private fun finishPreparing(runtime: MatchRuntime, failures: Int) {
        if (runtime.phase != MatchPhase.PREPARING) return
        if (failures > 0) {
            abortStart(runtime, "prepare-failed")
            return
        }
        runtime.phase = MatchPhase.COUNTDOWN
        for (participant in runtime.participants.values) {
            // 已过开赛屏障：倒计时期间算"场上的人"，但伤害仍然被阶段保护挡住。
            if (participant.state != ParticipantState.LEFT) participant.state = ParticipantState.ALIVE
        }
        persist(runtime)
        broadcast(runtime, "game.match.countdown", mapOf("seconds" to runtime.settings.countdownSeconds.toString()))
        val matchId = runtime.matchId
        val regionId = runtime.regionId
        plugin.regionScheduler().runGlobalLater(
            Runnable { countdownFinished(regionId, matchId) },
            runtime.settings.countdownSeconds * 20L,
        )
    }

    @Synchronized
    private fun countdownFinished(regionId: String, matchId: UUID) {
        val runtime = runtimes[regionId] ?: return
        if (runtime.matchId != matchId || runtime.phase != MatchPhase.COUNTDOWN) return
        // 倒计时结束前复查资格：缺人就撤销开赛，不带着残阵开打。
        if (startCondition(runtime) != null) {
            abortStart(runtime, "roster-changed")
            return
        }
        startRound(runtime)
    }

    @Synchronized
    private fun startRound(runtime: MatchRuntime) {
        val region = regionOf(runtime)
        runtime.phase = MatchPhase.RUNNING
        runtime.roundStartedAtMillis = clock()
        if (runtime.matchStartedAtMillis == 0L) runtime.matchStartedAtMillis = runtime.roundStartedAtMillis
        runtime.pendingLeave.clear()
        for (participant in runtime.participants.values) {
            if (participant.state == ParticipantState.LEFT) continue
            participant.state = ParticipantState.ALIVE
            // 淘汰顺序不重置：它记录的是整场的出局次序（大乱斗排名、决斗跨回合成绩都用它）。
        }
        persist(runtime)
        broadcast(
            runtime,
            "game.match.started",
            mapOf("round" to runtime.round.toString(), "total" to runtime.settings.maxRounds.toString()),
        )
        for (participant in runtime.participants.values.toList()) {
            val player = plugin.server.getPlayer(participant.playerId) ?: continue
            if (region != null) plugin.triggers().fire(RegionTrigger.ON_MODE_START, player, region)
            scheduleEntity(player) {
                if (runtimes[runtime.regionId] !== runtime || runtime.phase != MatchPhase.RUNNING) return@scheduleEntity
                spawnLocation(runtime, participant)?.let { plugin.regionScheduler().teleportAsync(player, it) }
            }
        }
        ensureTicking()
    }

    private fun resolveRoundIfDecided(runtime: MatchRuntime) {
        if (runtime.phase != MatchPhase.RUNNING) return
        when (val verdict = runtime.rules.evaluate(runtime.view(clock()))) {
            is MatchVerdict.RoundOver -> applyRoundOutcome(runtime, verdict.outcome)
            is MatchVerdict.Finished -> finalize(runtime, verdict.resolution, null, "decided")
            MatchVerdict.Continue -> Unit
        }
    }

    @Synchronized
    private fun applyRoundOutcome(runtime: MatchRuntime, outcome: RoundOutcome) {
        if (runtime.phase != MatchPhase.RUNNING) return
        if (outcome.draw) {
            runtime.drawnRounds += 1
        } else {
            outcome.winnerIds.forEach { winner -> runtime.roundWins.merge(winner, 1, Int::plus) }
            outcome.winnerUnitId?.let { unit -> runtime.unitRoundWins.merge(unit, 1, Int::plus) }
        }
        broadcastLocalized(
            runtime,
            if (outcome.draw) "game.match.round.draw" else "game.match.round.won",
            mapOf(
                "round" to GameArg.literal(runtime.round.toString()),
                "winner" to GameArg.literal(outcome.winnerIds.mapNotNull { runtime.participants[it]?.name }.joinToString(", ")),
                "reason" to GameArg.key(outcome.reasonKey),
            ),
        )
        when (val verdict = runtime.rules.afterRound(runtime.view(clock()), outcome)) {
            is MatchVerdict.Finished -> finalize(runtime, verdict.resolution, null, outcome.reasonKey)
            MatchVerdict.Continue -> beginIntermission(runtime)
            is MatchVerdict.RoundOver -> finalize(
                runtime,
                MatchResolution(
                    outcome = if (outcome.draw) MatchOutcome.DRAW else MatchOutcome.NATURAL,
                    winnerIds = outcome.winnerIds,
                    winnerTeamId = outcome.winnerUnitId,
                    reasonKey = outcome.reasonKey,
                    reasonArgs = outcome.reasonArgs,
                ),
                null,
                outcome.reasonKey,
            )
        }
    }

    @Synchronized
    private fun beginIntermission(runtime: MatchRuntime) {
        runtime.phase = MatchPhase.INTERMISSION
        persist(runtime)
        broadcast(runtime, "game.match.intermission", mapOf("seconds" to runtime.settings.intermissionSeconds.toString()))
        val matchId = runtime.matchId
        val regionId = runtime.regionId
        val seconds = runtime.settings.intermissionSeconds.toLong().coerceAtLeast(1L)
        plugin.regionScheduler().runGlobalLater(
            Runnable { nextRound(regionId, matchId) },
            seconds * 20L,
        )
    }

    @Synchronized
    private fun nextRound(regionId: String, matchId: UUID) {
        val runtime = runtimes[regionId] ?: return
        if (runtime.matchId != matchId || runtime.phase != MatchPhase.INTERMISSION) return
        runtime.round += 1
        startRound(runtime)
    }

    @Synchronized
    private fun abortStart(runtime: MatchRuntime, reason: String) {
        if (runtime.phase == MatchPhase.FINISHING || runtime.phase == MatchPhase.CLOSED) return
        finalize(
            runtime,
            MatchResolution(
                outcome = MatchOutcome.ABORTED,
                reasonKey = "game.match.reason.start-aborted",
                reasonArgs = reasonArgs(reason),
            ),
            null,
            reason,
        )
    }

    /** 统一终结入口：终态只记录一次，资金只处理一次（PLAN.md §6.2）。 */
    @Synchronized
    private fun finalize(
        runtime: MatchRuntime,
        resolution: MatchResolution,
        forcedBy: String?,
        reason: String,
    ) {
        if (runtime.phase == MatchPhase.FINISHING || runtime.phase == MatchPhase.CLOSED) return
        runtime.phase = MatchPhase.FINISHING
        endingRegions.add(runtime.regionId)
        val result = MatchResult(
            resultId = UUID.randomUUID(),
            matchId = runtime.matchId,
            regionId = runtime.regionId,
            modeType = runtime.rules.modeType,
            outcome = resolution.outcome,
            winnerIds = resolution.winnerIds,
            winnerTeamId = resolution.winnerTeamId,
            reasonKey = resolution.reasonKey,
            reasonArgs = resolution.reasonArgs,
            forcedBy = forcedBy,
            finishedAtMillis = clock(),
            eliminationOrder = runtime.participants.values
                .filter { it.eliminatedOrder != null }
                .sortedBy { it.eliminatedOrder }
                .map { it.playerId },
        )
        val region = regionOf(runtime)
        var rewardState = RewardState.NONE
        if (region != null && runtime.settings.allowFunding && hasFunding(region)) {
            val funding = if (result.outcome == MatchOutcome.NATURAL && result.winnerIds.isNotEmpty()) {
                plugin.rewards().settle(
                    region,
                    FundingSettlement(
                        winnerKeys = result.winnerIds,
                        winnerUnit = result.winnerTeamId,
                        unitParties = runtime.nationParties.toMap(),
                    ),
                )
            } else {
                plugin.rewards().refund(region, reason)
            }
            rewardState = if (funding.successful) {
                if (result.outcome == MatchOutcome.NATURAL && result.winnerIds.isNotEmpty()) RewardState.SETTLED else RewardState.REFUNDED
            } else {
                plugin.log().severe("Contract funding for ${region.id} was not finalized (${funding.code}): ${funding.detail}")
                RewardState.REVIEW_REQUIRED
            }
        }
        runtime.result = result.copy(rewardState = rewardState)
        recentResults[runtime.regionId] = runtime.result!!
        runtime.pendingRestore.clear()
        runtime.pendingRestore.addAll(runtime.participants.keys.filter { gearStore.peek(it) != null })
        runtime.escrowed.clear()
        persist(runtime)

        plugin.audit().record(
            null,
            runtime.regionId,
            "mode.match.end",
            reason,
            mapOf(
                "match" to runtime.matchId.toString(),
                "mode" to runtime.rules.modeType,
                "outcome" to result.outcome.name,
                "participants" to runtime.participants.size.toString(),
                "revision" to runtime.publishedRevision.toString(),
                "actor" to (forcedBy ?: "system"),
            ),
        )
        announceResult(runtime)
        announceRestoreProgress(runtime)
        restorePending(runtime)
    }

    /** FINISHING 期间把"还有几人待恢复 + 资金状态"如实告诉参与者，不让界面看起来已经结束。 */
    private fun announceRestoreProgress(runtime: MatchRuntime) {
        if (runtime.pendingRestore.isEmpty()) return
        val reward = plugin.lang().label(
            "labels.reward.${runtime.result?.rewardState?.name?.lowercase() ?: "pending"}",
            runtime.result?.rewardState?.name?.lowercase() ?: "pending",
        )
        val args = mapOf("pending" to runtime.pendingRestore.size.toString(), "reward" to reward)
        for (participant in runtime.participants.values) {
            if (!runtime.pendingRestore.contains(participant.playerId)) continue
            val player = plugin.server.getPlayer(participant.playerId) ?: continue
            plugin.sendGame(player, "game.match.finishing", args)
        }
    }

    private fun announceResult(runtime: MatchRuntime) {
        val result = runtime.result ?: return
        val winners = result.winnerIds.mapNotNull { runtime.participants[it]?.name }.joinToString(", ")
        for (participant in runtime.participants.values) {
            val player = plugin.server.getPlayer(participant.playerId) ?: continue
            val key = when {
                result.outcome == MatchOutcome.ABORTED -> "game.match.result.aborted"
                result.outcome != MatchOutcome.NATURAL -> "game.match.result.draw"
                result.winnerIds.contains(participant.playerId) -> "game.match.result.win"
                result.winnerTeamId != null && result.winnerTeamId == participant.teamId -> "game.match.result.team-win"
                else -> "game.match.result.lose"
            }
            plugin.sendGameLocalized(
                player,
                key,
                mapOf(
                    "winner" to GameArg.literal(winners),
                    "reason" to GameArg.key(result.reasonKey),
                    "reward" to GameArg.key("labels.reward.${result.rewardState.name.lowercase()}"),
                ),
            )
        }
    }

    // ---------------------------------------------------------------- 恢复协议

    private fun restorePending(runtime: MatchRuntime) {
        if (runtime.pendingRestore.isEmpty()) {
            close(runtime, "restored")
            return
        }
        for (playerId in runtime.pendingRestore.toList()) {
            val player = plugin.server.getPlayer(playerId) ?: continue
            scheduleEntity(player) { restoreForMatch(player, runtime) }
        }
    }

    /**
     * 恢复顺序（PLAN.md §6.3.7）：读 escrow → 在玩家所属线程写回完整快照 → **确认落盘后**再删除
     * escrow 记录。重启时若发现该 operation 已确认、记录还在，只补清理而不覆盖恢复后的新背包。
     */
    @Synchronized
    private fun restoreForMatch(player: Player, runtime: MatchRuntime) {
        if (runtimes[runtime.regionId] !== runtime) return
        val playerId = player.uniqueId
        val stored = gearStore.peek(playerId)
        if (stored == null) {
            runtime.pendingRestore.remove(playerId)
            maybeClose(runtime)
            return
        }
        if (!runtime.confirmedRestore.contains(playerId)) {
            writeSnapshot(player, stored)
            runtime.confirmedRestore.add(playerId)
            // 确认标记必须先落盘：写不进去就保留 escrow 等下次恢复（重写同一快照是幂等的），
            // 不直接解锁（PLAN.md §6.3.7）。
            if (!persist(runtime)) return
        }
        gearStore.take(playerId)
        runtime.pendingRestore.remove(playerId)
        stored.respawn?.let { plugin.regionScheduler().teleportAsync(player, it) }
        plugin.effects().cleanupModeEffects(player, runtime.regionId, "match-end")
        runtime.result?.let {
            plugin.sendGameLocalized(
                player,
                "game.match.restored",
                mapOf("reward" to GameArg.key("labels.reward.${it.rewardState.name.lowercase()}")),
            )
        }
        persist(runtime)
        maybeClose(runtime)
    }

    @Synchronized
    private fun maybeClose(runtime: MatchRuntime) {
        persist(runtime)
        if (runtime.pendingRestore.isEmpty()) {
            close(runtime, "restored")
            return
        }
        announceRestoreProgress(runtime)
    }

    @Synchronized
    private fun close(runtime: MatchRuntime, reason: String) {
        runtimes.remove(runtime.regionId, runtime)
        endingRegions.remove(runtime.regionId)
        matchStore.remove(runtime.matchId)
        matchStore.save()
        (runtime.participants.keys + runtime.spectators).forEach { lastDisplay.remove(it) }
        runtime.phase = MatchPhase.CLOSED
        plugin.log().debug("Match ${runtime.matchId} in ${runtime.regionId} closed: $reason")
    }

    /**
     * 重启恢复：持久化的比赛不自动续打半局，逐人恢复后删除记录；离线选手保留 escrow 与待恢复
     * 标记，登录时由 [restoreIfPending] 继续（PLAN.md §6.2）。
     */
    @Synchronized
    fun recoverPersisted(reason: String) {
        for (snapshot in matchStore.all()) {
            if (snapshot.phase == MatchPhase.CLOSED) {
                matchStore.remove(snapshot.matchId)
                continue
            }
            val region = plugin.regions().find(snapshot.regionId)
            val rules = MatchRulesCatalog.rulesFor(snapshot.modeType)
            if (region == null || rules == null) {
                // 场地被删或玩法不再受支持：只补清理 escrow，不臆造胜负。
                for (playerId in snapshot.pendingRestore) {
                    val player = plugin.server.getPlayer(playerId) ?: continue
                    scheduleEntity(player) { runCatching { restoreIfPending(player, "orphan-match") } }
                }
                continue
            }
            val runtime = MatchRuntime(
                regionId = snapshot.regionId,
                matchId = snapshot.matchId,
                rules = rules,
                settings = rules.settings(region),
                publishedRevision = snapshot.publishedRevision,
                createdAtMillis = snapshot.createdAtMillis,
            )
            runtime.options.putAll(snapshot.options)
            runtime.teams.putAll(snapshot.teams)
            snapshot.participants.forEach {
                runtime.participants[it.playerId] = Participant(
                    it.playerId,
                    it.name,
                    it.teamId,
                    it.state,
                    it.eliminatedOrder,
                    it.eliminationReason,
                )
            }
            runtime.phase = MatchPhase.FINISHING
            runtime.result = snapshot.result
            runtime.confirmedRestore.addAll(snapshot.confirmedRestore)
            runtime.pendingRestore.addAll(snapshot.pendingRestore)
            runtimes[snapshot.regionId] = runtime
            endingRegions.add(snapshot.regionId)
            plugin.log().warn(
                "Aborting unfinished match ${snapshot.matchId} in ${snapshot.regionId} after restart ($reason); " +
                    "${runtime.pendingRestore.size} participant(s) still need gear recovery.",
            )
            restorePending(runtime)
        }
    }

    /**
     * 恢复某名玩家的持久化 escrow；返回是否确实写回了内容。
     * 与比赛无关的历史记录同样走这条路径（旧装备记录不能因缺 matchId 被丢弃）。
     */
    @Synchronized
    fun restoreIfPending(player: Player, reason: String): Boolean {
        val playerId = player.uniqueId
        val stored = gearStore.peek(playerId) ?: return false
        val runtime = matchStore.all()
            .firstOrNull { it.pendingRestore.contains(playerId) }
            ?.let { snapshot -> runtimes[snapshot.regionId]?.takeIf { it.matchId == snapshot.matchId } }
        if (runtime != null && runtime.confirmedRestore.contains(playerId)) {
            // 重启补清理：背包已经写过，只删记录，避免覆盖玩家恢复后合法获得的新物品。
            gearStore.take(playerId)
            runtime.pendingRestore.remove(playerId)
            maybeClose(runtime)
            plugin.log().warn("Cleared the completed recovery record for ${player.name}: $reason")
            return false
        }
        writeSnapshot(player, stored)
        if (runtime != null) {
            runtime.confirmedRestore.add(playerId)
            runtime.pendingRestore.remove(playerId)
            if (!persist(runtime)) {
                // 标记落盘失败：保留 escrow，下次登录再恢复（同一快照重写是幂等的）。
                runtime.confirmedRestore.remove(playerId)
                runtime.pendingRestore.add(playerId)
                return false
            }
        }
        gearStore.take(playerId)
        stored.respawn?.let { plugin.regionScheduler().teleportAsync(player, it) }
        plugin.log().warn("Restored persisted combat escrow for ${player.name}: $reason")
        if (runtime != null) maybeClose(runtime)
        return true
    }

    @Synchronized
    fun cleanupAll(reason: String, shuttingDown: Boolean = false) {
        val immediate = !plugin.regionScheduler().isFolia
        forceSynchronousRestores = immediate
        try {
            cleanupAllInternal(reason, shuttingDown, immediate)
        } finally {
            forceSynchronousRestores = false
        }
    }

    private fun cleanupAllInternal(reason: String, shuttingDown: Boolean, immediate: Boolean) {
        for (runtime in runtimes.values.toList()) {
            if (runtime.phase == MatchPhase.WAITING || runtime.phase == MatchPhase.CLOSED) {
                close(runtime, reason)
                continue
            }
            finalize(
                runtime,
                MatchResolution(
                    outcome = MatchOutcome.ABORTED,
                    reasonKey = "game.match.reason.server-stop",
                    reasonArgs = reasonArgs(reason),
                ),
                null,
                reason,
            )
        }
        for ((playerId, snapshot) in pendingRespawnRestores.toMap()) {
            val player = plugin.server.getPlayer(playerId) ?: continue
            val restore = Runnable {
                restoreSnapshot(player, snapshot)
                pendingRespawnRestores.remove(playerId)
            }
            if (immediate) restore.run() else if (!shuttingDown) scheduleEntity(player, restore)
        }
        if (shuttingDown) pendingRespawnRestores.clear()
        // 无主 escrow（历史记录或缺 matchId 的旧装备）也必须恢复，否则会永久扣着别人的背包。
        for (playerId in gearStore.allPlayerIds()) {
            val player = plugin.server.getPlayer(playerId) ?: continue
            val restore = Runnable { runCatching { restoreIfPending(player, "cleanup-all:$reason") } }
            if (immediate) restore.run() else if (!shuttingDown) scheduleEntity(player, restore)
        }
        stopTicking()
    }

    fun stopTicking() {
        tickTask?.cancel()
        tickTask = null
    }

    // ---------------------------------------------------------------- 计时与展示

    /**
     * 每秒驱动一次：回合超时判定、离场宽限与 ActionBar 状态。由协调器自己的计时任务调用，
     * `RegionsPlugin` 的看门狗也会兜底调用（Folia 上不同区域的实体任务不保证同 tick 完成，
     * 所以判定只依赖单调时钟与有序事件）。
     */
    @Synchronized
    fun tick() {
        val now = clock()
        for (runtime in runtimes.values.toList()) {
            when (runtime.phase) {
                MatchPhase.RUNNING -> {
                    for ((playerId, leftAt) in runtime.pendingLeave.toMap()) {
                        if (now - leftAt >= LEAVE_GRACE_MILLIS) {
                            runtime.pendingLeave.remove(playerId)
                            eliminate(runtime, playerId, "game.match.reason.left", null)
                        }
                    }
                    resolveRoundIfDecided(runtime)
                }

                MatchPhase.PREPARING, MatchPhase.COUNTDOWN -> Unit

                else -> continue
            }
            updateDisplay(runtime)
        }
    }

    private fun ensureTicking() {
        if (tickTask != null) return
        tickTask = plugin.regionScheduler().runGlobalTimer(Runnable { tick() }, 20L, 20L)
    }

    private fun updateDisplay(runtime: MatchRuntime) {
        val key = when (runtime.phase) {
            MatchPhase.PREPARING -> "game.match.actionbar.preparing"
            MatchPhase.COUNTDOWN -> "game.match.actionbar.starting"
            MatchPhase.RUNNING -> "game.match.actionbar.running"
            else -> return
        }
        val args = mapOf(
            "alive" to runtime.participants.values.count { it.state == ParticipantState.ALIVE }.toString(),
            "total" to runtime.participants.size.toString(),
            "round" to runtime.round.toString(),
            "time" to remainingSeconds(runtime).toString(),
        )
        val signature = "$key:${args.toSortedMap()}"
        for (participant in runtime.participants.values) {
            val player = plugin.server.getPlayer(participant.playerId) ?: continue
            if (lastDisplay[participant.playerId] == signature) continue
            lastDisplay[participant.playerId] = signature
            scheduleEntity(player) {
                if (runtimes[runtime.regionId] !== runtime) return@scheduleEntity
                player.sendActionBar(plugin.lang().render(plugin.lang().messageFor(player, key, args)))
            }
        }
    }

    private fun remainingSeconds(runtime: MatchRuntime): Int {
        if (runtime.settings.roundSeconds <= 0) return 0
        val elapsed = clock() - runtime.roundStartedAtMillis
        return ((runtime.settings.roundSeconds * 1000L - elapsed) / 1000L).coerceAtLeast(0L).toInt()
    }

    // ---------------------------------------------------------------- 查询

    fun isEnding(regionId: String): Boolean = endingRegions.contains(regionId)

    /**
     * 这名玩家的装备是否仍在托管里（比赛中或等待恢复）。
     *
     * PLAN.md §6.3.6：托管期间的装备是"临时装备"，不许丢弃、拾取或通过容器转移，
     * 否则恢复快照会与场上实际物品分叉，玩家也可能把比赛装备带出场。
     */
    fun isGearEscrowed(playerId: UUID): Boolean = gearStore.peek(playerId) != null

    fun isCombatMode(region: RegionDefinition): Boolean = MatchRulesCatalog.rulesFor(region.mode?.type) != null

    @Synchronized
    fun status(regionId: String): GameStatus {
        val region = plugin.regions().find(regionId)
        val modeType = region?.mode?.type ?: ""
        val runtime = runtimes[regionId] ?: return GameStatus(regionId, modeType, GamePhase.IDLE)
        return when (runtime.phase) {
            MatchPhase.WAITING -> GameStatus(
                regionId,
                modeType,
                GamePhase.WAITING,
                players = runtime.participants.size,
                ready = runtime.participants.values.count { it.state == ParticipantState.READY },
                extra = mapOf("spectators" to runtime.spectators.size),
            )

            MatchPhase.FINISHING, MatchPhase.CLOSED -> GameStatus(regionId, modeType, GamePhase.IDLE)

            else -> GameStatus(
                regionId,
                modeType,
                GamePhase.RUNNING,
                players = runtime.participants.values.count { it.state == ParticipantState.ALIVE },
                extra = mapOf(
                    "alive" to runtime.participants.values.count { it.state == ParticipantState.ALIVE },
                    "round" to runtime.round,
                    "spectators" to runtime.spectators.size,
                ),
            )
        }
    }

    @Synchronized
    fun participants(regionId: String): List<MatchParticipant> =
        runtimes[regionId]?.participants?.values?.map { it.snapshot() }.orEmpty()

    @Synchronized
    fun result(regionId: String): MatchResult? = runtimes[regionId]?.result ?: recentResults[regionId]

    @Synchronized
    fun spectatorIds(regionId: String): Set<UUID> = runtimes[regionId]?.spectators?.toSet().orEmpty()

    /** 伤害隔离用的成员查询：一名玩家在一场比赛里最多只有一条记录。 */
    @Synchronized
    fun membership(playerId: UUID): MatchMembership? {
        for (runtime in runtimes.values) {
            runtime.participants[playerId]?.let { participant ->
                return MatchMembership(
                    regionId = runtime.regionId,
                    matchId = runtime.matchId,
                    participant = true,
                    spectator = false,
                    phase = runtime.phase,
                    teamId = participant.teamId,
                    state = participant.state,
                    friendlyFire = runtime.settings.friendlyFire,
                )
            }
            if (runtime.spectators.contains(playerId)) {
                return MatchMembership(
                    regionId = runtime.regionId,
                    matchId = runtime.matchId,
                    participant = false,
                    spectator = true,
                    phase = runtime.phase,
                    teamId = null,
                    state = ParticipantState.ELIMINATED,
                    friendlyFire = runtime.settings.friendlyFire,
                )
            }
        }
        return null
    }

    /**
     * 伤害事件的判定入口。
     *
     * [playerSourced] 表示这次伤害可归因于玩家（近战、投射物、药水/滞留云、有主人的宠物、
     * 玩家点燃的 TNT）。生物与环境伤害返回 [DamageDecision.UNRELATED]，仍按原规则处理：
     * 环境死亡同样计入淘汰，只是不参与 PVP 成员隔离。
     */
    @Synchronized
    fun damageDecision(attackerId: UUID?, victimId: UUID, playerSourced: Boolean): DamageDecision {
        if (!playerSourced) return DamageDecision.UNRELATED
        val victim = membership(victimId)
        if (attackerId == null) {
            // 来源可归因于玩家但定位不到人：参赛者一律不受影响。
            return if (victim == null) DamageDecision.UNRELATED else DamageDecision.DENY
        }
        return CombatDamagePolicy.decide(membership(attackerId), victim)
    }

    // ---------------------------------------------------------------- 内部工具

    /** 该玩家是否还有任何一场比赛待恢复（含已从内存移除、只留在 MatchStore 记录里的）。 */
    @Synchronized
    private fun pendingRestoreAnywhere(playerId: UUID): Boolean {
        if (runtimes.values.any { it.pendingRestore.contains(playerId) }) return true
        return matchStore.all().any { it.pendingRestore.contains(playerId) }
    }

    private fun runtimeOf(playerId: UUID): MatchRuntime? =
        runtimes.values.firstOrNull { it.participants.containsKey(playerId) }

    @Synchronized
    private fun runtimeFor(region: RegionDefinition, rules: MatchRules): MatchRuntime =
        runtimes.computeIfAbsent(region.id) {
            MatchRuntime(
                regionId = region.id,
                matchId = UUID.randomUUID(),
                rules = rules,
                settings = rules.settings(region),
                publishedRevision = region.publishedRevision ?: region.revision,
                createdAtMillis = clock(),
            )
        }

    private fun persist(runtime: MatchRuntime): Boolean = matchStore.persist(runtime.snapshot())

    private fun broadcast(runtime: MatchRuntime, key: String, args: Map<String, String>) {
        broadcastLocalized(runtime, key, GameArg.literals(args))
    }

    /** 占位符里有语言键时的广播：每个接收者各自解析一次（PLAN.md §4.2）。 */
    private fun broadcastLocalized(runtime: MatchRuntime, key: String, args: Map<String, GameArg>) {
        for (playerId in runtime.participants.keys + runtime.spectators) {
            val player = plugin.server.getPlayer(playerId) ?: continue
            scheduleEntity(player) {
                if (runtimes[runtime.regionId] !== runtime) return@scheduleEntity
                plugin.sendGameLocalized(player, key, args)
            }
        }
    }

    private fun scheduleEntity(player: Player, task: Runnable) {
        // 停服/重载时调度器上的任务不保证还能跑，此时就地执行，否则会把装备留在 escrow 里。
        if (forceSynchronousRestores) {
            task.run()
            return
        }
        plugin.regionScheduler().runAtEntity(player, task)
    }

    private fun regionOf(runtime: MatchRuntime): RegionDefinition? = plugin.regions().find(runtime.regionId)

    private fun hasFunding(region: RegionDefinition): Boolean {
        val values = region.mode?.values ?: return false
        return values["reward-source"].equals("contract", ignoreCase = true) &&
            !values["reward-contract"].isNullOrBlank()
    }

    private fun spawnLocation(runtime: MatchRuntime, participant: Participant): Location? {
        val region = regionOf(runtime) ?: return null
        val values = region.mode?.values ?: return null
        val point = when (runtime.rules.modeType) {
            "free_for_all" -> runtime.spawnAssignments.getOrPut(participant.playerId) {
                assignSpawn(runtime) ?: return null
            }

            "union_war" -> {
                val raw = if (participant.teamId == runtime.options[OPTION_TEAM_A]) {
                    values["spawn-points"]
                } else {
                    values["spawn-points-b"]
                }
                val points = MatchSpawns.parseList(raw).ifEmpty { MatchSpawns.parseList(values["spawn-points"]) }
                if (points.isEmpty()) return null
                val index = runtime.participants.values
                    .filter { it.teamId == participant.teamId }
                    .indexOfFirst { it.playerId == participant.playerId }
                    .coerceAtLeast(0)
                points[index % points.size]
            }

            else -> {
                val points = MatchSpawns.parseList(values["spawn-points"])
                if (points.isEmpty()) return null
                val index = runtime.participants.keys.indexOfFirst { it == participant.playerId }.coerceAtLeast(0)
                points[index % points.size]
            }
        }
        return point.toLocation()
    }

    private fun assignSpawn(runtime: MatchRuntime): SpawnPoint? {
        val region = regionOf(runtime) ?: return null
        val points = MatchSpawns.parseList(region.mode?.values?.get("spawn-points"))
        if (points.isEmpty()) return null
        val used = runtime.spawnAssignments.values
            .mapTo(HashSet()) { "${it.world.lowercase()}:${it.x}:${it.y}:${it.z}" }
        val free = points.filterNot { "${it.world.lowercase()}:${it.x}:${it.y}:${it.z}" in used }
        return (free.ifEmpty { points })[runtime.spawnAssignments.size % points.size]
    }

    private fun SpawnPoint.toLocation(): Location? =
        plugin.server.getWorld(world)?.let { Location(it, x, y, z, yaw, pitch) }

    private fun applyKit(player: Player, region: RegionDefinition) {
        player.inventory.clear()
        player.inventory.armorContents = arrayOfNulls(4)
        player.inventory.setItemInOffHand(null)
        for (item in parseItems(region.mode?.values?.get("kit"))) {
            player.inventory.addItem(item)
        }
        val armor = parseItems(region.mode?.values?.get("armor")).take(4)
        val armorContents = arrayOfNulls<ItemStack>(4)
        for (index in armor.indices) {
            armorContents[index] = armor[index]
        }
        player.inventory.armorContents = armorContents
        parseItems(region.mode?.values?.get("offhand")).firstOrNull()?.let { player.inventory.setItemInOffHand(it) }
        player.updateInventory()
    }

    private fun parseItems(value: String?): List<ItemStack> {
        if (value.isNullOrBlank()) return emptyList()
        return value.split(',', ';')
            .mapNotNull { raw ->
                val parts = raw.trim().split(':')
                val material = Material.matchMaterial(parts[0].trim().uppercase()) ?: return@mapNotNull null
                val amount = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 64) ?: 1
                ItemStack(material, amount)
            }
    }

    private fun shouldReplaceGear(region: RegionDefinition): Boolean {
        val values = region.mode?.values ?: return false
        return values["replace-gear"]?.toBooleanStrictOrNull() == true ||
            !values["kit"].isNullOrBlank() ||
            !values["armor"].isNullOrBlank() ||
            !values["offhand"].isNullOrBlank()
    }

    private fun writeSnapshot(player: Player, snapshot: CombatGearStore.StoredGear) {
        player.inventory.contents = snapshot.contents
        player.inventory.armorContents = snapshot.armor
        player.inventory.setItemInOffHand(snapshot.offhand)
        player.level = snapshot.level
        player.exp = snapshot.exp
        player.gameMode = snapshot.gameMode
        player.updateInventory()
    }

    private fun restoreSnapshot(player: Player, snapshot: CombatGearStore.StoredGear) {
        writeSnapshot(player, snapshot)
        gearStore.take(player.uniqueId)
    }

    private fun outsideLocation(region: RegionDefinition): Location? {
        val values = region.mode?.values ?: return null
        val raw = values["respawn"] ?: values["outside"] ?: values["spectator"] ?: return null
        return MatchSpawns.parse(raw)?.toLocation()
    }

    private fun phaseLabel(phase: MatchPhase): String = when (phase) {
        MatchPhase.WAITING -> "waiting"
        MatchPhase.PREPARING, MatchPhase.COUNTDOWN -> "preparing"
        MatchPhase.RUNNING, MatchPhase.INTERMISSION -> "running"
        MatchPhase.FINISHING, MatchPhase.CLOSED -> "restoring"
    }

    private fun reasonArgs(reason: String): Map<String, String> = mapOf(
        "reason" to plugin.lang().label("labels.reason.$reason", reason),
    )

    /** `plugin.config` 在部分单测里不可用；配置读取失败一律退回默认值。 */
    private fun configLong(path: String, default: Long): Long =
        runCatching { plugin.config.getLong(path, default) }.getOrDefault(default)

    /** 一场比赛在内存中的可变状态；所有修改都在协调器的锁内完成。 */
    private class MatchRuntime(
        val regionId: String,
        val matchId: UUID,
        val rules: MatchRules,
        val settings: MatchSettings,
        val publishedRevision: Long,
        val createdAtMillis: Long,
    ) {
        val participants: LinkedHashMap<UUID, Participant> = LinkedHashMap()
        val teams: MutableMap<String, TeamSnapshot> = LinkedHashMap()
        val options: MutableMap<String, String> = LinkedHashMap()
        val teamNames: MutableMap<String, String> = LinkedHashMap()
        val spectators: MutableSet<UUID> = LinkedHashSet()
        val pendingLeave: MutableMap<UUID, Long> = LinkedHashMap()
        val roundWins: MutableMap<UUID, Int> = LinkedHashMap()
        val unitRoundWins: MutableMap<String, Int> = LinkedHashMap()
        val spawnAssignments: MutableMap<UUID, SpawnPoint> = LinkedHashMap()
        val escrowed: MutableSet<UUID> = LinkedHashSet()
        val confirmedRestore: MutableSet<UUID> = LinkedHashSet()
        val pendingRestore: MutableSet<UUID> = LinkedHashSet()
        /** 工会战：开赛前锁定的 Nation → 合同签署方，赛后换届不改收款人。 */
        val nationParties: MutableMap<String, UUID> = LinkedHashMap()
        var phase: MatchPhase = MatchPhase.WAITING
        var round: Int = 1
        var drawnRounds: Int = 0
        var eliminationCounter: Int = 0
        var roundStartedAtMillis: Long = 0L
        var matchStartedAtMillis: Long = 0L
        var result: MatchResult? = null
        /** 已经回过准备屏障回执的玩家（回执也可能是否定的）。 */
        val prepared: MutableSet<UUID> = LinkedHashSet()
        /** 结算观察窗口只排一次。 */
        var resolutionScheduled: Boolean = false

        fun view(now: Long): MatchView = MatchView(
            phase = phase,
            round = round,
            participants = participants.values.map { it.snapshot() },
            settings = settings,
            roundElapsedMillis = if (roundStartedAtMillis == 0L) 0L else now - roundStartedAtMillis,
            roundWins = roundWins.toMap(),
            unitRoundWins = unitRoundWins.toMap(),
            drawnRounds = drawnRounds,
        )

        fun snapshot(): MatchSnapshot = MatchSnapshot(
            matchId = matchId,
            regionId = regionId,
            publishedRevision = publishedRevision,
            modeType = rules.modeType,
            createdAtMillis = createdAtMillis,
            options = options.toMap(),
            participants = participants.values.map { it.snapshot() },
            teams = teams.toMap(),
            phase = phase,
            round = round,
            result = result,
            pendingRestore = pendingRestore.toSet(),
            confirmedRestore = confirmedRestore.toSet(),
        )
    }

    /** 运行期的可变选手记录；对外只暴露不可变的 [MatchParticipant]。 */
    private class Participant(
        val playerId: UUID,
        val name: String,
        var teamId: String?,
        var state: ParticipantState,
        var eliminatedOrder: Int? = null,
        var eliminationReason: String? = null,
    ) {
        fun snapshot(): MatchParticipant =
            MatchParticipant(playerId, name, teamId, state, eliminatedOrder, eliminationReason)
    }

    companion object {
        const val OPTION_TEAM_A = "team-a"
        const val OPTION_TEAM_B = "team-b"
        const val REASON_QUIT = "quit"
        const val REASON_KICK = "kick"
        const val LEAVE_GRACE_MILLIS = 5_000L
        const val DEFAULT_PROMPT_COOLDOWN = 60L

        /** 结算观察窗口：200 毫秒（PLAN.md §6.2），用 4 tick 表示，不依赖同 tick 完成。 */
        const val SETTLEMENT_WINDOW_TICKS = 4L
    }
}
