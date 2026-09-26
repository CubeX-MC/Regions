package org.cubexmc.regions.mode

import org.bukkit.Location
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.cubexmc.regions.RegionsPlugin
import org.cubexmc.regions.match.JoinResult
import org.cubexmc.regions.match.MatchOutcome
import org.cubexmc.regions.match.MatchPhase
import org.cubexmc.regions.match.MatchResult
import org.cubexmc.regions.match.ParticipantState
import org.cubexmc.regions.match.RewardState
import org.cubexmc.regions.match.SpectateResult
import org.cubexmc.regions.model.EffectConfig
import org.cubexmc.regions.model.EffectScope
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionLifecycle
import org.cubexmc.regions.model.RegionTrigger
import org.cubexmc.regions.service.ServiceResult
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil

/**
 * 捉迷藏（`hide_and_seek`）。
 *
 * 本轮补齐的三件事：
 *
 * 1. **恢复顺序**。原实现的 `restoreStored()` 先 `gearStore.take()` 删记录、再写背包，
 *    写背包这一步一失败装备就永久消失。战斗层在 M0 修过这个顺序，这里没跟上。
 *    现在统一走 [ModeGearEscrow]：写回 → 落盘确认 → 删记录。
 * 2. **显式报名**。走进场地只提示一次，只有 `join` 才进名单；同时补上
 *    `leave` / `unready` / `spectate`。
 * 3. **结果与隔离**。产生结构化 [MatchResult] 写进共享 store；除"搜寻者抓躲藏者"
 *    这一下之外，任何玩家来源的伤害都被 [ModeDamagePolicy] 拒绝——此前躲藏者
 *    可以反过来把搜寻者打死。
 */
class RoundModeService(private val plugin: RegionsPlugin) {

    private val states: ConcurrentHashMap<String, RoundState> = ConcurrentHashMap()
    private val endingRegions: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val entryPrompts: ConcurrentHashMap<String, Long> = ConcurrentHashMap()

    /** 死亡后等待重生再恢复的玩家；快照一直在 escrow 里，这里只记"该还了"。 */
    private val pendingRespawn: ConcurrentHashMap<UUID, String> = ConcurrentHashMap()

    private val escrow = ModeGearEscrow(plugin, ESCROW_FILE, "round")

    init {
        escrow.load()
    }

    fun isRoundMode(region: RegionDefinition): Boolean = isRoundMode(region.mode?.type)

    fun isRoundMode(type: String?): Boolean = type.equals("hide_and_seek", ignoreCase = true)

    // ------------------------------------------------------------ 进入与提示

    fun onEnter(player: Player, region: RegionDefinition): Boolean {
        if (!isRoundMode(region)) return false
        if (endingRegions.contains(region.id)) {
            plugin.sendGame(player, "game.round.restoring", mapOf("name" to region.name))
            return true
        }
        val state = states[region.id]
        if (state != null && state.roster.isParticipant(player.uniqueId)) return true
        if (state != null && state.roster.phase == MatchPhase.RUNNING) {
            plugin.sendGame(player, "game.round.in-progress", mapOf("name" to region.name))
            return true
        }
        promptJoin(player, region)
        return true
    }

    private fun promptJoin(player: Player, region: RegionDefinition) {
        val cooldownMillis = plugin.config.getLong("modes.entry-prompt-cooldown-seconds", DEFAULT_PROMPT_COOLDOWN) * 1000L
        val key = "${player.uniqueId}:${region.id}"
        val now = System.currentTimeMillis()
        val previous = entryPrompts[key]
        if (previous != null && now - previous < cooldownMillis) return
        entryPrompts[key] = now
        plugin.sendGame(player, "game.match.enter-prompt", mapOf("name" to region.name, "id" to region.id))
    }

    @Synchronized
    fun onLeave(player: Player, regionId: String, reason: String) {
        val state = states[regionId] ?: return
        val roster = state.roster
        if (roster.removeSpectator(player.uniqueId)) {
            plugin.releaseMatchEntry(player.uniqueId, roster.matchId)
            return
        }
        if (!roster.isParticipant(player.uniqueId)) return
        // 已经出局／已退赛的人不再重复处理：死亡、退出与断线回调可能先后到达。
        if (roster.stateOf(player.uniqueId)?.isContestant != true) return
        val running = roster.phase == MatchPhase.RUNNING
        if (running) {
            // 进行中退出**不删记录**：他参加过这一局，结果里要能查到。
            roster.eliminate(player.uniqueId, ParticipantState.LEFT)
        } else {
            roster.remove(player.uniqueId)
            plugin.releaseMatchEntry(player.uniqueId, roster.matchId)
        }
        state.roles.remove(player.uniqueId)
        state.found.remove(player.uniqueId)
        plugin.effects().cleanupModeEffects(player, regionId, "round-leave:$reason")
        restoreParticipant(player, regionId, "round-leave:$reason")
        if (running) {
            maybeFinishRound(state, reason)
        } else if (roster.isEmpty()) {
            states.remove(regionId, state)
        }
    }

    // ------------------------------------------------------------ 报名册

    @Synchronized
    fun join(player: Player, regionId: String): JoinResult {
        val region = plugin.regions().find(regionId)
            ?: return JoinResult.Rejected("game.match.join.unknown-region", mapOf("id" to regionId))
        if (!isRoundMode(region)) return JoinResult.Rejected("game.match.join.not-a-match")
        if (!region.enabled || region.lifecycle != RegionLifecycle.PUBLISHED) {
            return JoinResult.Rejected("game.match.join.unavailable")
        }
        if (endingRegions.contains(region.id)) return JoinResult.Rejected("game.match.join.restoring")
        if (escrow.isEscrowed(player.uniqueId)) return JoinResult.Rejected("game.match.join.restoring")
        val state = stateFor(region)
        val roster = state.roster
        if (roster.phase != MatchPhase.WAITING) return JoinResult.Rejected("game.match.join.in-progress")
        if (roster.isParticipant(player.uniqueId)) return JoinResult.Rejected("game.match.join.already-joined")
        val values = modeValues(region)
        val maxPlayers = RaceCourse.maxPlayers(values)
        if (maxPlayers > 0 && roster.size() >= maxPlayers) return JoinResult.Rejected("game.match.join.full")
        plugin.reserveMatchEntry(player.uniqueId, roster.matchId)?.let { return JoinResult.Rejected(it) }
        roster.join(player.uniqueId)
        if (values["require-ready"]?.toBooleanStrictOrNull() == false) roster.ready(player.uniqueId)
        plugin.sendGame(player, "game.match.join.ok", mapOf("name" to region.name, "id" to region.id))
        broadcast(
            state,
            "game.match.join.broadcast",
            mapOf(
                "player" to player.name,
                "current" to roster.size().toString(),
                "required" to minPlayers(values).toString(),
            ),
        )
        return JoinResult.Joined
    }

    @Synchronized
    fun leave(player: Player, regionId: String): Boolean {
        val state = states[regionId] ?: return false
        if (state.roster.removeSpectator(player.uniqueId)) {
            plugin.releaseMatchEntry(player.uniqueId, state.roster.matchId)
            return true
        }
        if (!state.roster.isParticipant(player.uniqueId)) return false
        onLeave(player, regionId, "leave-command")
        return true
    }

    @Synchronized
    fun ready(player: Player, regionId: String): Boolean {
        val region = plugin.regions().find(regionId) ?: return false
        if (!isRoundMode(region)) return false
        val state = states[regionId]
        if (state == null || !state.roster.isParticipant(player.uniqueId)) {
            plugin.sendGame(player, "game.match.ready.not-joined", emptyMap())
            return true
        }
        if (state.roster.phase != MatchPhase.WAITING) {
            plugin.sendGame(player, "game.round.already-started", emptyMap())
            return true
        }
        if (state.roster.ready(player.uniqueId)) {
            broadcast(
                state,
                "game.round.ready",
                mapOf(
                    "player" to player.name,
                    "current" to state.roster.readyCount().toString(),
                    "total" to state.roster.size().toString(),
                ),
            )
        }
        val values = modeValues(region)
        if (RaceCourse.startMode(values) == "vote" &&
            state.roster.readyCount() >= RaceCourse.requiredVotes(values, state.roster.size())
        ) {
            start(state.region, state, "vote")
        }
        return true
    }

    @Synchronized
    fun unready(player: Player, regionId: String): Boolean {
        val state = states[regionId] ?: return false
        if (state.roster.phase != MatchPhase.WAITING) return false
        if (!state.roster.unready(player.uniqueId)) return false
        broadcast(
            state,
            "game.match.unready",
            mapOf("player" to player.name, "current" to state.roster.readyCount().toString()),
        )
        return true
    }

    @Synchronized
    fun spectate(player: Player, regionId: String): SpectateResult {
        val region = plugin.regions().find(regionId)
            ?: return SpectateResult.Rejected("game.match.join.unknown-region", mapOf("id" to regionId))
        if (!isRoundMode(region)) return SpectateResult.Rejected("game.match.spectate.not-a-match")
        val state = states[regionId] ?: return SpectateResult.Rejected("game.match.spectate.no-match")
        if (state.roster.isParticipant(player.uniqueId)) {
            return SpectateResult.Rejected("game.match.spectate.is-participant")
        }
        plugin.reserveMatchEntry(player.uniqueId, state.roster.matchId)?.let { return SpectateResult.Rejected(it) }
        state.roster.spectate(player.uniqueId)
        outsideLocation(region)?.let { plugin.regionScheduler().teleportAsync(player, it) }
        return SpectateResult.Joined
    }

    // ------------------------------------------------------------ 运行

    /** 隐藏时间内锁住搜寻者的移动。 */
    @Synchronized
    fun onMove(event: PlayerMoveEvent): Boolean {
        val player = event.player
        val state = states.values.firstOrNull {
            it.roster.phase == MatchPhase.RUNNING &&
                it.roster.isParticipant(player.uniqueId) &&
                it.roles[player.uniqueId] == RoundRole.SEEKER
        } ?: return false
        if (state.seekersReleased) return false
        val from = event.from
        val to = event.to ?: return false
        if (from.world == to.world && from.blockX == to.blockX && from.blockY == to.blockY && from.blockZ == to.blockZ) {
            return false
        }
        event.isCancelled = true
        plugin.sendGame(player, "game.round.wait-hiding", emptyMap())
        return true
    }

    /**
     * 搜寻者打到躲藏者 = 抓到。这是捉迷藏里**唯一**被允许的玩家间互动，
     * 伤害本身会被取消；其余组合由 [ModeDamagePolicy] 统一拒绝。
     */
    @Synchronized
    fun onDamage(event: EntityDamageByEntityEvent): Boolean {
        val victim = event.entity as? Player ?: return false
        val attacker = attackingPlayer(event) ?: return false
        val state = states.values.firstOrNull {
            it.roster.phase == MatchPhase.RUNNING &&
                it.seekersReleased &&
                it.roster.isParticipant(attacker.uniqueId) &&
                it.roster.isParticipant(victim.uniqueId)
        } ?: return false
        if (state.roles[attacker.uniqueId] != RoundRole.SEEKER || state.roles[victim.uniqueId] != RoundRole.HIDER) {
            return false
        }
        event.isCancelled = true
        found(victim, attacker, state)
        return true
    }

    @Synchronized
    fun startCommand(sender: CommandSender, regionId: String): Boolean {
        val region = plugin.regions().find(regionId) ?: return false
        if (!isRoundMode(region)) return false
        if (!plugin.authority().canJudge(sender, region).allowed) {
            plugin.lang().send(sender, "no-permission")
            return true
        }
        val state = states[regionId] ?: stateFor(region)
        start(state.region, state, "judge")
        return true
    }

    @Synchronized
    private fun start(region: RegionDefinition, state: RoundState, reason: String) {
        if (state.roster.phase != MatchPhase.WAITING) return
        val values = modeValues(region)
        val minPlayers = minPlayers(values)
        if (state.roster.size() < minPlayers) {
            broadcast(
                state,
                "game.round.waiting-players",
                mapOf("current" to state.roster.size().toString(), "required" to minPlayers.toString()),
            )
            return
        }
        state.roster.movePhase(MatchPhase.PREPARING)
        state.roles.clear()
        state.found.clear()
        state.seekersReleased = false
        state.generation++
        val generation = state.generation
        val playerIds = state.roster.participantIds()
        val prepared = HashSet<UUID>()
        plugin.regionScheduler().runGlobalLater(Runnable {
            synchronized(this) {
                if (states[region.id] === state && state.generation == generation &&
                    state.roster.phase == MatchPhase.PREPARING
                ) finish(region.id, "prepare-timeout", MatchOutcome.ABORTED, "game.match.reason.prepare-failed")
            }
        }, 200L)
        assignRoles(region, state)
        val replaceGear = ModeKit.shouldReplaceGear(values, "seeker-kit", "hider-kit")
        val outside = outsideLocation(region)
        if (!ModeMatchPersistence.begin(plugin.matchStore(), region, state.roster) {
                plugin.server.getPlayer(it)?.name ?: it.toString()
            }
        ) {
            finish(region.id, "prepare-storage", MatchOutcome.ABORTED, "game.match.reason.prepare-failed")
            return
        }
        for (playerId in playerIds) {
            val player = plugin.server.getPlayer(playerId)
            if (player == null) {
                finish(region.id, "prepare-disconnect", MatchOutcome.ABORTED, "game.match.reason.prepare-failed")
                return
            }
            plugin.regionScheduler().runAtEntity(player, Runnable {
                synchronized(this) {
                    if (states[region.id] !== state || state.generation != generation) return@Runnable
                    if (state.roster.phase != MatchPhase.PREPARING || !state.roster.isParticipant(playerId)) return@Runnable
                    runCatching {
                        check(!player.isDead) { "Player died during preparation" }
                        applyRoundStart(player, region, state, replaceGear, outside)
                        if (prepared.add(playerId) && prepared.containsAll(playerIds)) beginRound(region, state, reason)
                    }.onFailure { error ->
                        plugin.log().severe("Failed to start round ${region.id} for ${player.name}: ${error.message}")
                        finish(region.id, "start-failed", MatchOutcome.ABORTED, "game.match.reason.prepare-failed")
                    }
                }
            })
        }
    }

    private fun beginRound(region: RegionDefinition, state: RoundState, reason: String) {
        state.roster.begin(System.currentTimeMillis())
        val values = modeValues(region)
        val generation = state.generation
        for (playerId in state.roster.participantIds()) {
            val player = plugin.server.getPlayer(playerId) ?: continue
            plugin.regionScheduler().runAtEntity(player, Runnable {
                if (states[region.id] !== state || state.roster.phase != MatchPhase.RUNNING ||
                    state.roster.stateOf(playerId) != ParticipantState.ALIVE
                ) return@Runnable
                plugin.triggers().fire(RegionTrigger.ON_ROLE_ASSIGNED, player, region)
                plugin.triggers().fire(RegionTrigger.ON_MODE_START, player, region)
            })
        }
        val hideSeconds = hideSeconds(values)
        broadcast(state, "game.round.started", mapOf("name" to region.name, "seconds" to hideSeconds.toString()))
        plugin.regionScheduler().runGlobalLater(Runnable {
            if (states[region.id] === state && state.generation == generation &&
                state.roster.phase == MatchPhase.RUNNING
            ) {
                releaseSeekers(state)
            }
        }, hideSeconds * 20L)
        val roundSeconds = roundSeconds(values)
        if (roundSeconds > 0) {
            plugin.regionScheduler().runGlobalLater(Runnable {
                if (states[region.id] === state && state.generation == generation &&
                    state.roster.phase == MatchPhase.RUNNING
                ) {
                    broadcast(state, "game.round.hiders-win-timeout", emptyMap())
                    finish(
                        region.id,
                        "time-limit",
                        MatchOutcome.NATURAL,
                        "game.match.reason.hiders-survived",
                        winners = hiderIds(state),
                    )
                }
            }, roundSeconds * 20L)
        }
        plugin.log().debug("Started round ${region.id}: $reason")
    }

    private fun assignRoles(region: RegionDefinition, state: RoundState) {
        val players = state.roster.participantIds().toMutableList()
        players.shuffle()
        val seekerCount = seekerCount(modeValues(region), players.size)
        for ((index, playerId) in players.withIndex()) {
            state.roles[playerId] = if (index < seekerCount) RoundRole.SEEKER else RoundRole.HIDER
        }
    }

    private fun applyRoundStart(
        player: Player,
        region: RegionDefinition,
        state: RoundState,
        replaceGear: Boolean,
        outside: Location?,
    ) {
        val role = state.roles[player.uniqueId] ?: return
        val values = modeValues(region)
        plugin.sessions().setMetadata(player, region.id, "round_role", role.key)
        // 先持久化 escrow 再换装：顺序反过来一旦失败就是装备永久丢失。
        if (replaceGear) checkNotNull(escrow.capture(player, region.id, outside)) { "Player already has pending gear" }
        when (role) {
            RoundRole.SEEKER -> {
                applySeekerVisual(player, region)
                if (replaceGear) ModeKit.apply(player, values, "seeker-kit", "kit")
                plugin.sendGame(player, "game.round.role-seeker", emptyMap())
            }
            RoundRole.HIDER -> {
                applyHiderVisual(player, region)
                if (replaceGear) ModeKit.apply(player, values, "hider-kit", "kit")
                plugin.sendGame(player, "game.round.role-hider", emptyMap())
            }
        }
    }

    @Synchronized
    private fun releaseSeekers(state: RoundState) {
        if (state.roster.phase != MatchPhase.RUNNING) return
        state.seekersReleased = true
        broadcast(state, "game.round.seek-start", emptyMap())
    }

    @Synchronized
    private fun found(hider: Player, seeker: Player, state: RoundState) {
        if (!state.found.add(hider.uniqueId)) return
        val region = state.region
        plugin.sessions().setMetadata(hider, region.id, "round_found", "true")
        plugin.sessions().setMetadata(hider, region.id, "round_found_by", seeker.name)
        plugin.effects().cleanupModeEffects(hider, region.id, "round-role-change")
        if (modeValues(region)["found-becomes-seeker"]?.toBooleanStrictOrNull() != false) {
            state.roles[hider.uniqueId] = RoundRole.SEEKER
            applySeekerVisual(hider, region)
            plugin.sendGame(hider, "game.round.found-become-seeker", mapOf("seeker" to seeker.name))
        } else {
            state.roles.remove(hider.uniqueId)
            state.roster.eliminate(hider.uniqueId)
            outsideLocation(region)?.let { plugin.regionScheduler().teleportAsync(hider, it) }
            plugin.sendGame(hider, "game.round.found-eliminated", mapOf("seeker" to seeker.name))
        }
        plugin.triggers().fire(RegionTrigger.ON_FOUND, hider, region)
        broadcast(state, "game.round.found", mapOf("player" to hider.name, "seeker" to seeker.name))
        maybeFinishRound(state, "found")
    }

    /**
     * 躲藏者全部被找到 → 搜寻者赢；搜寻者全部离开 → 躲藏者赢。
     *
     * 只看**还在场上的人**（[ModeRoster.aliveIds]）：退赛与死亡的记录留在名单里供结果使用。
     */
    private fun maybeFinishRound(state: RoundState, reason: String) {
        if (state.roster.phase != MatchPhase.RUNNING) return
        val participants = state.roster.aliveIds()
        if (participants.isEmpty()) {
            finish(state.regionId, reason, MatchOutcome.ABORTED, "game.match.reason.roster-changed")
            return
        }
        val remainingHiders = participants.filter { state.roles[it] == RoundRole.HIDER && !state.found.contains(it) }
        val seekers = participants.filter { state.roles[it] == RoundRole.SEEKER }
        if (remainingHiders.isEmpty()) {
            broadcast(state, "game.round.seekers-win", emptyMap())
            finish(
                state.regionId,
                reason,
                MatchOutcome.NATURAL,
                "game.match.reason.all-found",
                winners = seekers.toSet(),
            )
        } else if (seekers.isEmpty()) {
            broadcast(state, "game.round.hiders-win-abandoned", emptyMap())
            finish(
                state.regionId,
                reason,
                MatchOutcome.NATURAL,
                "game.match.reason.seekers-gone",
                winners = remainingHiders.toSet(),
            )
        }
    }

    // ------------------------------------------------------------ 死亡与断线

    @Synchronized
    fun onDeath(event: PlayerDeathEvent): Boolean {
        val player = event.entity
        val state = states.values.firstOrNull {
            it.roster.phase == MatchPhase.RUNNING && it.roster.isParticipant(player.uniqueId)
        } ?: return false
        if (escrow.isEscrowed(player.uniqueId)) {
            event.drops.clear()
            event.droppedExp = 0
            pendingRespawn[player.uniqueId] = state.regionId
        }
        state.roster.eliminate(player.uniqueId)
        state.roles.remove(player.uniqueId)
        state.found.remove(player.uniqueId)
        plugin.sendGame(player, "game.round.removed", emptyMap())
        maybeFinishRound(state, "death")
        return true
    }

    @Synchronized
    fun onRespawn(player: Player) {
        if (!escrow.isEscrowed(player.uniqueId)) return
        pendingRespawn.remove(player.uniqueId)
        runCatching { escrow.restore(player, "round-respawn") }
            .onFailure { plugin.log().severe("Failed to restore round gear for ${player.name}: ${it.message}") }
    }

    @Synchronized
    fun onDisconnect(player: Player, reason: String) {
        for (state in states.values.toList()) {
            if (!state.roster.isParticipant(player.uniqueId) && !state.roster.isSpectator(player.uniqueId)) continue
            onLeave(player, state.regionId, reason)
        }
    }

    // ------------------------------------------------------------ 收尾

    @Synchronized
    fun forceEnd(sender: CommandSender, regionId: String, reason: String): Boolean {
        val region = plugin.regions().find(regionId) ?: return false
        if (!isRoundMode(region)) return false
        if (!plugin.authority().canJudge(sender, region).allowed) {
            plugin.lang().send(sender, "no-permission")
            return true
        }
        return forceEnd(regionId, reason, sender.name)
    }

    @Synchronized
    fun forceEnd(regionId: String, reason: String, forcedBy: String? = null): Boolean {
        if (!states.containsKey(regionId)) return false
        finish(regionId, reason, MatchOutcome.ABORTED, "game.match.reason.forced", forcedBy = forcedBy)
        return true
    }

    @Synchronized
    fun cleanupAll(reason: String, shuttingDown: Boolean = false) {
        val immediate = !plugin.regionScheduler().isFolia
        for (regionId in states.keys.toList()) {
            finish(
                regionId,
                reason,
                MatchOutcome.ABORTED,
                if (shuttingDown) "game.match.reason.server-stop" else "game.match.reason.forced",
                immediate = immediate,
                restorePlayers = !shuttingDown || immediate,
            )
        }
        escrow.restoreAllOnline("round-cleanup:$reason", immediate, shuttingDown)
        if (shuttingDown) pendingRespawn.clear()
    }

    @Synchronized
    private fun finish(
        regionId: String,
        reason: String,
        outcome: MatchOutcome,
        reasonKey: String,
        winners: Set<UUID> = emptySet(),
        forcedBy: String? = null,
        immediate: Boolean = false,
        restorePlayers: Boolean = true,
    ) {
        val state = states[regionId] ?: return
        val region = state.region
        state.roster.movePhase(MatchPhase.FINISHING)
        endingRegions.add(regionId)
        states.remove(regionId, state)
        plugin.releaseMatchEntries(state.roster.matchId)

        val participants = state.roster.participantIds()
        val result = MatchResult(
            resultId = UUID.randomUUID(),
            matchId = state.roster.matchId,
            regionId = regionId,
            modeType = region?.mode?.type ?: plugin.modeTypeOf(regionId),
            outcome = outcome,
            winnerIds = winners,
            winnerTeamId = null,
            reasonKey = reasonKey,
            forcedBy = forcedBy,
            rewardState = RewardState.NONE,
            finishedAtMillis = System.currentTimeMillis(),
            eliminationOrder = state.roster.eliminationOrderSnapshot(),
        )
        if (!ModeMatchPersistence.finish(plugin.matchStore(), result)) {
            plugin.log().severe("Failed to persist round result for $regionId; result remains in memory for retry")
        }

        plugin.audit().record(
            null,
            regionId,
            "mode.round.end",
            reason,
            mapOf(
                "revision" to (region?.publishedRevision?.toString() ?: "unknown"),
                "participants" to participants.size.toString(),
                "seekers" to state.roles.values.count { it == RoundRole.SEEKER }.toString(),
                "hiders" to state.roles.values.count { it == RoundRole.HIDER }.toString(),
                "found" to state.found.size.toString(),
                "outcome" to outcome.name,
            ),
        )

        val affected = (participants + state.roster.spectatorIds() + escrowedFor(regionId))
            .mapNotNull { plugin.server.getPlayer(it) }
            .distinctBy { it.uniqueId }
        val remaining = AtomicInteger(affected.size)
        if (affected.isEmpty() || !restorePlayers) endingRegions.remove(regionId)
        if (restorePlayers) {
            for (player in affected) {
                val task = Runnable {
                    try {
                        try {
                            plugin.triggers().fire(RegionTrigger.ON_MODE_END, player, region)
                            plugin.effects().cleanupModeEffects(player, regionId, "mode-end:$reason")
                        } finally {
                            restoreParticipant(player, regionId, "round-end:$reason")
                        }
                        plugin.sendGame(player, "game.round.ended", emptyMap())
                    } finally {
                        if (remaining.decrementAndGet() == 0) endingRegions.remove(regionId)
                    }
                }
                runCatching { if (immediate) task.run() else plugin.regionScheduler().runAtEntity(player, task) }
                    .onFailure {
                        plugin.log().severe("Failed to schedule round cleanup for ${player.name} in $regionId: ${it.message}")
                        if (!immediate && remaining.decrementAndGet() == 0) endingRegions.remove(regionId)
                    }
            }
        }
        state.roster.movePhase(MatchPhase.CLOSED)
        plugin.log().debug("Ended round $regionId: $reason ($outcome)")
    }

    private fun escrowedFor(regionId: String): Set<UUID> =
        escrow.pendingPlayerIds().filterTo(LinkedHashSet()) { escrow.escrowedRegion(it) == regionId }

    private fun restoreParticipant(player: Player, regionId: String, reason: String) {
        if (player.isDead) return
        // A delayed cleanup from an earlier match must never restore a newer match's escrow.
        if (escrow.escrowedRegion(player.uniqueId) != regionId) return
        pendingRespawn.remove(player.uniqueId, regionId)
        runCatching { escrow.restore(player, reason) }
            .onFailure { plugin.log().severe("Failed to restore round gear for ${player.name}: ${it.message}") }
    }

    fun restoreIfPending(player: Player, reason: String): Boolean =
        runCatching { escrow.restore(player, reason) }
            .onFailure { plugin.log().severe("Failed to restore round gear for ${player.name}: ${it.message}") }
            .getOrDefault(false)

    // ------------------------------------------------------------ 查询

    fun status(regionId: String): GameStatus {
        val modeType = plugin.modeTypeOf(regionId)
        val state = states[regionId] ?: return GameStatus(regionId, modeType, GamePhase.IDLE)
        if (state.roster.phase != MatchPhase.RUNNING) {
            return GameStatus(
                regionId,
                modeType,
                GamePhase.WAITING,
                players = state.roster.size(),
                ready = state.roster.readyCount(),
            )
        }
        return GameStatus(
            regionId,
            modeType,
            GamePhase.RUNNING,
            players = state.roster.size(),
            extra = mapOf(
                "seekers" to state.roles.values.count { it == RoundRole.SEEKER },
                "hiders" to state.roles.values.count { it == RoundRole.HIDER },
                "found" to state.found.size,
                "released" to if (state.seekersReleased) 1 else 0,
            ),
        )
    }

    fun result(regionId: String): MatchResult? = plugin.matchStore().lastResult(regionId)

    fun recoverPersisted(reason: String): Int =
        ModeMatchPersistence.recover(plugin.matchStore(), ::isRoundMode, reason)

    /** 本人在这块场地报名册里的状态；没报名返回 null。大厅按钮据此决定显示报名/准备/退出。 */
    fun participantState(regionId: String, playerId: java.util.UUID): org.cubexmc.regions.match.ParticipantState? =
        states[regionId]?.roster?.stateOf(playerId)

    /** 场地当前的比赛阶段；没有进行中的局返回 null。 */
    fun phaseOf(regionId: String): MatchPhase? = states[regionId]?.roster?.phase

    fun isSpectating(regionId: String, playerId: java.util.UUID): Boolean =
        states[regionId]?.roster?.isSpectator(playerId) == true

    fun isGearEscrowed(playerId: UUID): Boolean = escrow.isEscrowed(playerId)

    fun isPlaying(playerId: UUID): Boolean =
        states.values.any { it.roster.phase == MatchPhase.RUNNING && it.roster.isParticipant(playerId) }

    fun membership(playerId: UUID): ModeDamagePolicy.Membership? {
        for (state in states.values) {
            if (state.roster.phase == MatchPhase.CLOSED) continue
            val participant = state.roster.isParticipant(playerId)
            val spectator = state.roster.isSpectator(playerId)
            if (!participant && !spectator) continue
            return ModeDamagePolicy.Membership(state.regionId, state.roster.matchId, participant, spectator)
        }
        return null
    }

    // ------------------------------------------------------------ 辅助

    private fun stateFor(region: RegionDefinition): RoundState =
        states.computeIfAbsent(region.id) { RoundState(region) }

    private fun modeValues(region: RegionDefinition): Map<String, String> = region.mode?.values ?: emptyMap()

    private fun hiderIds(state: RoundState): Set<UUID> =
        state.roster.aliveIds()
            .filterTo(LinkedHashSet()) { state.roles[it] == RoundRole.HIDER && !state.found.contains(it) }

    private fun applyHiderVisual(player: Player, region: RegionDefinition) {
        requireEffectApplied(plugin.effects().apply(
            player,
            region,
            EffectConfig(
                "potion",
                EffectScope.UNTIL_MODE_END,
                mapOf(
                    "effect" to "invisibility",
                    "duration-ticks" to HIDER_INVISIBILITY_TICKS.toString(),
                    "particles" to "false",
                    "icon" to "false",
                ),
            ),
        ))
        requireEffectApplied(
            plugin.effects().apply(player, region, EffectConfig("glowing", EffectScope.UNTIL_MODE_END, mapOf("value" to "false"))),
        )
    }

    private fun applySeekerVisual(player: Player, region: RegionDefinition) {
        requireEffectApplied(
            plugin.effects().apply(player, region, EffectConfig("invisibility_suppression", EffectScope.UNTIL_MODE_END)),
        )
        requireEffectApplied(
            plugin.effects().apply(player, region, EffectConfig("glowing", EffectScope.UNTIL_MODE_END, mapOf("value" to "false"))),
        )
    }

    private fun requireEffectApplied(result: ServiceResult) {
        check(result.success) { result.reason.ifBlank { "Round visual effect could not be applied." } }
    }

    private fun seekerCount(values: Map<String, String>, playerCount: Int): Int {
        val ceiling = (playerCount - 1).coerceAtLeast(1)
        values["seekers"]?.toIntOrNull()?.let { return it.coerceIn(1, ceiling) }
        val ratio = values["seeker-ratio"]?.toDoubleOrNull()?.coerceIn(0.05, 0.8) ?: DEFAULT_SEEKER_RATIO
        return ceil(playerCount * ratio).toInt().coerceIn(1, ceiling)
    }

    private fun minPlayers(values: Map<String, String>): Int =
        values["min-players"]?.toIntOrNull()?.coerceAtLeast(2) ?: 2

    private fun hideSeconds(values: Map<String, String>): Long =
        values["hide-seconds"]?.toLongOrNull()?.coerceAtLeast(0L) ?: DEFAULT_HIDE_SECONDS

    private fun roundSeconds(values: Map<String, String>): Long =
        values["round-seconds"]?.toLongOrNull()?.coerceAtLeast(0L) ?: DEFAULT_ROUND_SECONDS

    private fun broadcast(state: RoundState, key: String, placeholders: Map<String, String> = emptyMap()) {
        val recipients = state.roster.participantIds() + state.roster.spectatorIds()
        for (playerId in recipients) {
            val player = plugin.server.getPlayer(playerId) ?: continue
            plugin.regionScheduler().runAtEntity(player, Runnable {
                plugin.sendGame(player, key, placeholders)
            })
        }
    }

    private fun outsideLocation(region: RegionDefinition): Location? {
        val values = modeValues(region)
        val raw = values["respawn"] ?: values["outside"] ?: values["spectator"] ?: return null
        val parts = raw.split(',')
        if (parts.size < 4) return null
        val world = plugin.server.getWorld(parts[0].trim()) ?: return null
        val x = parts[1].trim().toDoubleOrNull() ?: return null
        val y = parts[2].trim().toDoubleOrNull() ?: return null
        val z = parts[3].trim().toDoubleOrNull() ?: return null
        val yaw = parts.getOrNull(4)?.trim()?.toFloatOrNull() ?: 0.0f
        val pitch = parts.getOrNull(5)?.trim()?.toFloatOrNull() ?: 0.0f
        return Location(world, x, y, z, yaw, pitch)
    }

    private fun attackingPlayer(event: EntityDamageByEntityEvent): Player? {
        val direct = event.damager
        if (direct is Player) return direct
        return (direct as? org.bukkit.entity.Projectile)?.shooter as? Player
    }

    private enum class RoundRole(val key: String) {
        SEEKER("seeker"),
        HIDER("hider"),
    }

    private class RoundState(val region: RegionDefinition) {
        val regionId = region.id
        val roster = ModeRoster(regionId)
        val found: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
        val roles: ConcurrentHashMap<UUID, RoundRole> = ConcurrentHashMap()

        @Volatile
        var seekersReleased: Boolean = false

        /** 每开一局 +1；延迟任务据此确认自己属于哪一局。 */
        @Volatile
        var generation: Int = 0
    }

    private companion object {
        const val ESCROW_FILE = "round-escrow.yml"
        const val DEFAULT_PROMPT_COOLDOWN = 60L
        const val DEFAULT_SEEKER_RATIO = 0.2
        const val DEFAULT_HIDE_SECONDS = 30L
        const val DEFAULT_ROUND_SECONDS = 300L

        /** 躲藏者的隐身按"一整局都不掉"给，实际由 UNTIL_MODE_END 的 lease 负责清除。 */
        const val HIDER_INVISIBILITY_TICKS = 20 * 60 * 60
    }
}
