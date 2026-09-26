package org.cubexmc.regions.mode

import org.bukkit.Location
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.entity.PlayerDeathEvent
import org.cubexmc.regions.RegionsPlugin
import org.cubexmc.regions.match.JoinResult
import org.cubexmc.regions.match.MatchOutcome
import org.cubexmc.regions.match.MatchPhase
import org.cubexmc.regions.match.MatchResult
import org.cubexmc.regions.match.ParticipantState
import org.cubexmc.regions.match.RewardState
import org.cubexmc.regions.match.SpectateResult
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionLifecycle
import org.cubexmc.regions.model.RegionTrigger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 三种竞速玩法（`run_race` / `boat_race` / `horse_race`）。
 *
 * 本轮把它从"走进来就算报名、装备键设了没反应、任何人都能插手"提到与战斗层同级：
 *
 * - **显式报名**：进入场地只提示一次（带冷却），只有 `join` 才进名单。
 * - **装备托管**：`kit`/`armor`/`offhand`/`replace-gear` 现在真的生效，且走
 *   [ModeGearEscrow] 的崩溃安全协议——先持久化再换装，写回确认后才删记录。
 * - **成员隔离**：比赛期间局外人与选手互不造成玩家来源的伤害（[ModeDamagePolicy]）。
 * - **结构化结果**：完赛顺序写进 [MatchResult.standings]，`result` 与大厅卡片共用一份。
 *
 * 线程模型沿用既有约定：状态变更在同一把锁下，实体访问（发装备、传送、写背包）
 * 一律经 `CubexScheduler` 回到玩家线程，任务执行前重新校验 state 实例与成员关系。
 */
class RaceModeService(private val plugin: RegionsPlugin) {

    private val states: ConcurrentHashMap<String, RaceState> = ConcurrentHashMap()
    private val endingRegions: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val entryPrompts: ConcurrentHashMap<String, Long> = ConcurrentHashMap()

    /** 死亡后等待重生再恢复的玩家 → 他所在的场地；快照本身一直在 escrow 里。 */
    private val pendingRespawn: ConcurrentHashMap<UUID, String> = ConcurrentHashMap()

    private val escrow = ModeGearEscrow(plugin, ESCROW_FILE, "race")

    init {
        escrow.load()
    }

    fun isRaceMode(region: RegionDefinition): Boolean = isRaceMode(region.mode?.type)

    fun isRaceMode(type: String?): Boolean =
        type != null && type.lowercase() in setOf("run_race", "boat_race", "horse_race")

    // ------------------------------------------------------------ 进入与提示

    /**
     * 走进赛道**不等于报名**（与战斗层同一条规则）。只提示一次，带冷却；
     * 名单要靠 [join] 才会变化。
     */
    fun onEnter(player: Player, region: RegionDefinition): Boolean {
        if (!isRaceMode(region)) return false
        if (endingRegions.contains(region.id)) {
            plugin.sendGame(player, "game.race.restoring", mapOf("name" to region.name))
            return true
        }
        val state = states[region.id]
        if (state != null && state.roster.isParticipant(player.uniqueId)) return true
        if (state != null && state.roster.phase == MatchPhase.RUNNING) {
            plugin.sendGame(player, "game.race.in-progress", mapOf("name" to region.name))
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

    /** 离开场地范围：报名阶段退出名单，比赛中按弃权处理并恢复装备。 */
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
            // 比赛中退出**不删记录**：他参加过这一局，必须出现在结果里。
            roster.eliminate(player.uniqueId, ParticipantState.LEFT)
        } else {
            roster.remove(player.uniqueId)
            plugin.releaseMatchEntry(player.uniqueId, roster.matchId)
        }
        state.progress.remove(player.uniqueId)
        restoreParticipant(player, regionId, "race-leave:$reason")
        if (running) {
            broadcast(state, "game.race.left", mapOf("player" to player.name))
            maybeFinishAfterRosterChange(state, reason)
        } else if (roster.isEmpty()) {
            states.remove(regionId, state)
        }
    }

    // ------------------------------------------------------------ 报名册

    /** 明确报名。只有这一步才是"同意参赛"（含装备暂存与退出后果）。 */
    @Synchronized
    fun join(player: Player, regionId: String): JoinResult {
        val region = plugin.regions().find(regionId)
            ?: return JoinResult.Rejected("game.match.join.unknown-region", mapOf("id" to regionId))
        if (!isRaceMode(region)) return JoinResult.Rejected("game.match.join.not-a-match")
        if (!region.enabled || region.lifecycle != RegionLifecycle.PUBLISHED) {
            return JoinResult.Rejected("game.match.join.unavailable")
        }
        if (endingRegions.contains(region.id)) return JoinResult.Rejected("game.match.join.restoring")
        // 装备还压在上一局的托管里：先恢复完再报名，否则新局会把旧快照写回去。
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
        // `require-ready: false` 沿用历史语义：报名即视为准备。
        if (values["require-ready"]?.toBooleanStrictOrNull() == false) roster.ready(player.uniqueId)
        plugin.sendGame(player, "game.match.join.ok", mapOf("name" to region.name, "id" to region.id))
        broadcast(
            state,
            "game.match.join.broadcast",
            mapOf(
                "player" to player.name,
                "current" to roster.size().toString(),
                "required" to RaceCourse.minPlayers(values).toString(),
            ),
        )
        return JoinResult.Joined
    }

    /** 明确退出：报名阶段退报名，比赛中弃权。不设额外权限——失去参赛资格也必须能拿回装备。 */
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
        if (!isRaceMode(region)) return false
        val state = states[regionId]
        if (state == null || !state.roster.isParticipant(player.uniqueId)) {
            plugin.sendGame(player, "game.match.ready.not-joined", emptyMap())
            return true
        }
        if (state.roster.phase != MatchPhase.WAITING) {
            plugin.sendGame(player, "game.race.already-started", emptyMap())
            return true
        }
        val values = modeValues(region)
        val constraint = RaceCourse.constraintFor(values, region.mode?.type, RaceCourse.Stage.START)
        if (!matchesVehicle(player, constraint)) {
            plugin.sendGameLocalized(player, RaceCourse.requireMessageKey(constraint), constraintArg(constraint))
            return true
        }
        if (!nearStart(player, region)) {
            plugin.sendGame(player, "game.race.start-required", emptyMap())
            return true
        }
        if (state.roster.ready(player.uniqueId)) {
            broadcast(
                state,
                "game.race.ready",
                mapOf(
                    "player" to player.name,
                    "current" to state.roster.readyCount().toString(),
                    "total" to state.roster.size().toString(),
                ),
            )
        }
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

    /** 观战：不参赛、不被隔离规则牵连，可随时退出。 */
    @Synchronized
    fun spectate(player: Player, regionId: String): SpectateResult {
        val region = plugin.regions().find(regionId)
            ?: return SpectateResult.Rejected("game.match.join.unknown-region", mapOf("id" to regionId))
        if (!isRaceMode(region)) return SpectateResult.Rejected("game.match.spectate.not-a-match")
        val state = states[regionId] ?: return SpectateResult.Rejected("game.match.spectate.no-match")
        if (state.roster.isParticipant(player.uniqueId)) {
            return SpectateResult.Rejected("game.match.spectate.is-participant")
        }
        plugin.reserveMatchEntry(player.uniqueId, state.roster.matchId)?.let { return SpectateResult.Rejected(it) }
        state.roster.spectate(player.uniqueId)
        outsideLocation(region)?.let { plugin.regionScheduler().teleportAsync(player, it) }
        return SpectateResult.Joined
    }

    // ------------------------------------------------------------ 比赛进行

    @Synchronized
    fun startCommand(sender: CommandSender, regionId: String): Boolean {
        val region = plugin.regions().find(regionId) ?: return false
        if (!isRaceMode(region)) return false
        if (!plugin.authority().canJudge(sender, region).allowed) {
            plugin.lang().send(sender, "no-permission")
            return true
        }
        val state = states[regionId] ?: stateFor(region)
        start(state.region, state, "judge")
        return true
    }

    @Synchronized
    private fun start(region: RegionDefinition, state: RaceState, reason: String) {
        if (state.roster.phase != MatchPhase.WAITING || state.starting) return
        val values = modeValues(region)
        val minPlayers = RaceCourse.minPlayers(values)
        if (state.roster.size() < minPlayers) {
            broadcast(
                state,
                "game.race.waiting-players",
                mapOf("current" to state.roster.size().toString(), "required" to minPlayers.toString()),
            )
            return
        }
        state.starting = true
        state.roster.movePhase(MatchPhase.PREPARING)
        state.generation++
        val preparation = state.generation
        plugin.regionScheduler().runGlobalLater(Runnable {
            synchronized(this) {
                if (states[region.id] === state && state.generation == preparation &&
                    state.roster.phase == MatchPhase.PREPARING
                ) finish(region.id, "prepare-timeout", MatchOutcome.ABORTED, "game.match.reason.prepare-failed")
            }
        }, 200L)
        val start = parseLocation(values["start"])
        val playerIds = state.roster.participantIds().toList()
        val checks = ConcurrentHashMap<UUID, RaceStartCheck>()
        val remaining = AtomicInteger(playerIds.size)
        fun completeCheck() {
            if (remaining.decrementAndGet() == 0) finalizeStart(region, state, reason, start, checks)
        }
        for (playerId in playerIds) {
            val player = plugin.server.getPlayer(playerId)
            if (player == null) {
                checks[playerId] = RaceStartCheck(atStart = false, validVehicle = false)
                completeCheck()
                continue
            }
            plugin.regionScheduler().runAtEntity(player, Runnable {
                if (states[region.id] !== state || state.generation != preparation ||
                    state.roster.phase != MatchPhase.PREPARING
                ) return@Runnable
                checks[playerId] = RaceStartCheck(
                    atStart = start == null ||
                        near(player.location, start, RaceCourse.radius(values, "start-radius")),
                    validVehicle = matchesVehicle(
                        player,
                        RaceCourse.constraintFor(values, region.mode?.type, RaceCourse.Stage.START),
                    ),
                )
                completeCheck()
            })
        }
    }

    @Synchronized
    private fun finalizeStart(
        region: RegionDefinition,
        state: RaceState,
        reason: String,
        start: Location?,
        checks: Map<UUID, RaceStartCheck>,
    ) {
        if (states[region.id] !== state || !state.starting || state.roster.phase != MatchPhase.PREPARING) return
        state.starting = false
        val values = modeValues(region)
        val currentPlayers = state.roster.participantIds()
        val minPlayers = RaceCourse.minPlayers(values)

        fun abort(key: String, args: Map<String, GameArg> = emptyMap()) {
            state.roster.movePhase(MatchPhase.WAITING)
            broadcastLocalized(state, key, args)
        }

        if (currentPlayers.size < minPlayers) {
            return abort(
                "game.race.waiting-players",
                GameArg.literals(
                    mapOf("current" to currentPlayers.size.toString(), "required" to minPlayers.toString()),
                ),
            )
        }
        if (!checks.keys.containsAll(currentPlayers)) {
            state.roster.movePhase(MatchPhase.WAITING)
            return
        }
        if (values["require-start"]?.toBooleanStrictOrNull() != false &&
            currentPlayers.any { checks[it]?.atStart != true }
        ) {
            return abort("game.race.waiting-at-start")
        }
        if (currentPlayers.any { checks[it]?.validVehicle != true }) {
            val constraint = RaceCourse.constraintFor(values, region.mode?.type, RaceCourse.Stage.START)
            return abort("game.race.waiting-vehicle", constraintArg(constraint))
        }

        state.finishOrder.clear()
        val generation = state.generation
        val prepared = HashSet<UUID>()
        val replaceGear = ModeKit.shouldReplaceGear(values)
        val outside = outsideLocation(region)
        if (!ModeMatchPersistence.begin(plugin.matchStore(), region, state.roster) {
                plugin.server.getPlayer(it)?.name ?: it.toString()
            }
        ) {
            finish(region.id, "prepare-storage", MatchOutcome.ABORTED, "game.match.reason.prepare-failed")
            return
        }
        for (playerId in currentPlayers) {
            state.progress[playerId] = 0
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
                        // 先持久化 escrow 再动背包：顺序反过来一旦失败就是装备永久丢失。
                        if (replaceGear) {
                            checkNotNull(escrow.capture(player, region.id, outside)) { "Player already has pending gear" }
                            ModeKit.apply(player, values, "kit")
                        }
                        if (values["teleport-start"]?.toBooleanStrictOrNull() == true && start != null) {
                            plugin.regionScheduler().teleportAsync(player, start).whenComplete { success, error ->
                                synchronized(this) {
                                    if (states[region.id] !== state || state.generation != generation ||
                                        state.roster.phase != MatchPhase.PREPARING
                                    ) return@whenComplete
                                    if (error != null || success != true) {
                                        finish(region.id, "prepare-teleport", MatchOutcome.ABORTED, "game.match.reason.prepare-failed")
                                    } else if (prepared.add(playerId) && prepared.containsAll(currentPlayers)) {
                                        beginRace(region, state, reason)
                                    }
                                }
                            }
                        } else if (prepared.add(playerId) && prepared.containsAll(currentPlayers)) {
                            beginRace(region, state, reason)
                        }
                    }.onFailure { error ->
                        plugin.log().severe("Failed to start race ${region.id} for ${player.name}: ${error.message}")
                        forceEnd(region.id, "prepare-failed")
                    }
                }
            })
        }
    }

    private fun beginRace(region: RegionDefinition, state: RaceState, reason: String) {
        state.roster.begin(System.currentTimeMillis())
        val generation = state.generation
        val values = modeValues(region)
        for (playerId in state.roster.participantIds()) {
            val player = plugin.server.getPlayer(playerId) ?: continue
            plugin.regionScheduler().runAtEntity(player, Runnable {
                if (states[region.id] === state && state.roster.phase == MatchPhase.RUNNING &&
                    state.roster.stateOf(playerId) == ParticipantState.ALIVE
                ) plugin.triggers().fire(RegionTrigger.ON_MODE_START, player, region)
            })
        }
        broadcast(state, "game.race.started", mapOf("name" to region.name))
        val timeoutSeconds = RaceCourse.timeoutSeconds(values)
        if (timeoutSeconds > 0) {
            plugin.regionScheduler().runGlobalLater(Runnable {
                // 旧局的计时器不能结束新局：同时校验 state 实例与 generation。
                if (states[region.id] === state && state.generation == generation &&
                    state.roster.phase == MatchPhase.RUNNING
                ) {
                    broadcast(state, "game.race.timeout", emptyMap())
                    finish(region.id, "timeout", MatchOutcome.TIMEOUT, "game.match.reason.timeout")
                }
            }, timeoutSeconds * 20L)
        }
        plugin.log().debug("Started race ${region.id}: $reason")
    }

    fun onMove(player: Player) {
        for (session in plugin.sessions().activeSessions(player.uniqueId)) {
            val state = states[session.regionId] ?: continue
            val region = state.region
            if (state.roster.phase != MatchPhase.RUNNING) continue
            if (state.roster.stateOf(player.uniqueId) != ParticipantState.ALIVE) continue
            if (state.finishOrder.contains(player.uniqueId)) continue
            tickRaceProgress(player, region, state)
        }
    }

    @Synchronized
    private fun tickRaceProgress(player: Player, region: RegionDefinition, state: RaceState) {
        if (states[region.id] !== state || state.roster.phase != MatchPhase.RUNNING ||
            state.roster.stateOf(player.uniqueId) != ParticipantState.ALIVE
        ) return
        val values = modeValues(region)
        val checkpoints = parseLocations(values["checkpoints"])
        val index = state.progress[player.uniqueId] ?: 0
        if (index < checkpoints.size) {
            val constraint = RaceCourse.constraintFor(values, region.mode?.type, RaceCourse.Stage.CHECKPOINT, index)
            if (!matchesVehicle(player, constraint)) return
            if (!near(player.location, checkpoints[index], RaceCourse.radius(values, "checkpoint-radius"))) return
            val next = index + 1
            state.progress[player.uniqueId] = next
            plugin.sessions().setMetadata(player, region.id, "race_checkpoint", next.toString())
            plugin.sendGame(
                player,
                "game.race.checkpoint",
                mapOf("index" to next.toString(), "total" to checkpoints.size.toString()),
            )
            plugin.triggers().fire(RegionTrigger.ON_CHECKPOINT, player, region)
            return
        }
        val finishPoint = parseLocation(values["finish"]) ?: return
        if (!matchesVehicle(player, RaceCourse.constraintFor(values, region.mode?.type, RaceCourse.Stage.FINISH, checkpoints.size))) return
        if (!near(player.location, finishPoint, RaceCourse.radius(values, "finish-radius"))) return
        recordFinish(player, region, state)
    }

    private fun recordFinish(player: Player, region: RegionDefinition, state: RaceState) {
        if (!state.finishOrder.add(player.uniqueId)) return
        val rank = state.finishOrder.size
        val elapsed = System.currentTimeMillis() - state.roster.startedAtMillis
        plugin.sessions().setMetadata(player, region.id, "race_rank", rank.toString())
        plugin.sessions().setMetadata(player, region.id, "race_time_ms", elapsed.toString())
        plugin.audit().record(
            player,
            region.id,
            "mode.race.finish",
            details = mapOf(
                "revision" to (region.publishedRevision?.toString() ?: "unknown"),
                "rank" to rank.toString(),
                "elapsed-ms" to elapsed.toString(),
            ),
        )
        plugin.sendGame(
            player,
            "game.race.your-result",
            mapOf("rank" to rank.toString(), "time" to String.format("%.2f", elapsed / 1000.0)),
        )
        plugin.triggers().fire(RegionTrigger.ON_FINISH, player, region)
        broadcast(state, "game.race.finished", mapOf("player" to player.name, "rank" to rank.toString()))
        maybeFinishAfterRosterChange(state, "all-finished")
    }

    /**
     * 名单变化后可能已经无人再跑。
     *
     * 判定只看**还在跑的人**（存活且未完赛）：退赛与死亡的记录留在名单里供结果使用，
     * 不能因为他们还"在册"就让比赛永远结束不了。
     */
    private fun maybeFinishAfterRosterChange(state: RaceState, reason: String) {
        if (state.roster.phase != MatchPhase.RUNNING) return
        val stillRacing = state.roster.aliveIds() - state.finishOrder
        if (stillRacing.isNotEmpty()) return
        if (state.finishOrder.isEmpty()) {
            finish(state.regionId, reason, MatchOutcome.ABORTED, "game.match.reason.roster-changed")
        } else {
            finish(state.regionId, reason, MatchOutcome.NATURAL, "game.match.reason.all-finished")
        }
    }

    // ------------------------------------------------------------ 死亡与断线

    /**
     * 比赛中死亡：按弃权淘汰。装备托管期间清空掉落，避免"地上一份、escrow 一份"的分叉；
     * 真正的恢复在重生时进行（[onRespawn]）。
     */
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
        state.progress.remove(player.uniqueId)
        plugin.sendGame(player, "game.race.removed", emptyMap())
        maybeFinishAfterRosterChange(state, "death")
        return true
    }

    @Synchronized
    fun onRespawn(player: Player) {
        if (!escrow.isEscrowed(player.uniqueId)) return
        pendingRespawn.remove(player.uniqueId)
        runCatching { escrow.restore(player, "race-respawn") }
            .onFailure { plugin.log().severe("Failed to restore race gear for ${player.name}: ${it.message}") }
    }

    /** 断线即弃权：即使服主关掉了 cleanup-on-quit，也不能把离线玩家当成还在跑的选手。 */
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
        if (!isRaceMode(region)) return false
        if (!plugin.authority().canJudge(sender, region).allowed) {
            plugin.lang().send(sender, "no-permission")
            return true
        }
        return forceEnd(regionId, reason, sender.name)
    }

    @Synchronized
    fun forceEnd(regionId: String, reason: String, forcedBy: String? = null): Boolean {
        if (!states.containsKey(regionId)) return false
        finish(regionId, reason, MatchOutcome.ABORTED, "game.match.reason.forced", forcedBy)
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
        // 无主 escrow（历史记录、场地已删、离线时停服）也必须还回去，否则永久扣着背包。
        escrow.restoreAllOnline("race-cleanup:$reason", immediate, shuttingDown)
    }

    /**
     * 结束一场比赛并写出唯一终态记录。
     *
     * 名次 = 完赛顺序（第 1 名在前），后面接上没完赛的人——他们没有名次，
     * 但必须出现在记录里，否则"谁参加过这一局"就查不到了。
     */
    @Synchronized
    private fun finish(
        regionId: String,
        reason: String,
        outcome: MatchOutcome,
        reasonKey: String,
        forcedBy: String? = null,
        immediate: Boolean = false,
        restorePlayers: Boolean = true,
    ) {
        val state = states[regionId] ?: return
        val region = state.region
        state.roster.movePhase(MatchPhase.FINISHING)
        state.starting = false
        endingRegions.add(regionId)
        states.remove(regionId, state)
        plugin.releaseMatchEntries(state.roster.matchId)

        val finishers = state.finishOrder.toList()
        val participants = state.roster.participantIds()
        // 名次 = 完赛顺序；没完赛的按"坚持得更久的排前面"接在后面（淘汰顺序取反），
        // 与大乱斗由淘汰顺序倒推名次是同一条约定。
        val eliminated = state.roster.eliminationOrderSnapshot().reversed()
        val standings = finishers +
            eliminated.filterNot { finishers.contains(it) } +
            participants.filterNot { finishers.contains(it) || eliminated.contains(it) }
        val resolvedOutcome = if (outcome == MatchOutcome.TIMEOUT && finishers.isEmpty()) MatchOutcome.DRAW else outcome
        val resolvedReason = if (outcome == MatchOutcome.TIMEOUT && finishers.isEmpty()) {
            "game.match.reason.no-finisher"
        } else {
            reasonKey
        }
        val result = MatchResult(
            resultId = UUID.randomUUID(),
            matchId = state.roster.matchId,
            regionId = regionId,
            modeType = region?.mode?.type ?: plugin.modeTypeOf(regionId),
            outcome = resolvedOutcome,
            winnerIds = finishers.take(1).toSet(),
            winnerTeamId = null,
            reasonKey = resolvedReason,
            forcedBy = forcedBy,
            rewardState = RewardState.NONE,
            finishedAtMillis = System.currentTimeMillis(),
            standings = standings,
        )
        if (!ModeMatchPersistence.finish(plugin.matchStore(), result)) {
            plugin.log().severe("Failed to persist race result for $regionId; result remains in memory for retry")
        }

        plugin.audit().record(
            null,
            regionId,
            "mode.race.end",
            reason,
            mapOf(
                "revision" to (region?.publishedRevision?.toString() ?: "unknown"),
                "participants" to participants.size.toString(),
                "finishers" to finishers.size.toString(),
                "outcome" to resolvedOutcome.name,
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
                            restoreParticipant(player, regionId, "race-end:$reason")
                        }
                        plugin.sendGame(player, "game.race.ended", emptyMap())
                    } finally {
                        if (remaining.decrementAndGet() == 0) endingRegions.remove(regionId)
                    }
                }
                runCatching { if (immediate) task.run() else plugin.regionScheduler().runAtEntity(player, task) }
                    .onFailure {
                        plugin.log().severe("Failed to schedule race cleanup for ${player.name} in $regionId: ${it.message}")
                        if (!immediate && remaining.decrementAndGet() == 0) endingRegions.remove(regionId)
                    }
            }
        }
        state.roster.movePhase(MatchPhase.CLOSED)
        plugin.log().debug("Ended race $regionId: $reason ($resolvedOutcome)")
    }

    private fun escrowedFor(regionId: String): Set<UUID> =
        escrow.pendingPlayerIds().filterTo(LinkedHashSet()) { escrow.escrowedRegion(it) == regionId }

    private fun restoreParticipant(player: Player, regionId: String, reason: String) {
        if (player.isDead) return
        // A delayed cleanup from an earlier match must never restore a newer match's escrow.
        if (escrow.escrowedRegion(player.uniqueId) != regionId) return
        pendingRespawn.remove(player.uniqueId, regionId)
        runCatching { escrow.restore(player, reason) }
            .onFailure { plugin.log().severe("Failed to restore race gear for ${player.name}: ${it.message}") }
    }

    /** 登录或手动清理时把还压着的装备还回去。 */
    fun restoreIfPending(player: Player, reason: String): Boolean =
        runCatching { escrow.restore(player, reason) }
            .onFailure { plugin.log().severe("Failed to restore race gear for ${player.name}: ${it.message}") }
            .getOrDefault(false)

    // ------------------------------------------------------------ 查询

    fun status(regionId: String): GameStatus {
        val modeType = plugin.modeTypeOf(regionId)
        val state = states[regionId] ?: return GameStatus(regionId, modeType, GamePhase.IDLE)
        return when (state.roster.phase) {
            MatchPhase.RUNNING -> GameStatus(
                regionId,
                modeType,
                GamePhase.RUNNING,
                players = state.roster.size(),
                extra = mapOf("finished" to state.finishOrder.size),
            )
            else -> GameStatus(
                regionId,
                modeType,
                GamePhase.WAITING,
                players = state.roster.size(),
                ready = state.roster.readyCount(),
            )
        }
    }

    fun result(regionId: String): MatchResult? = plugin.matchStore().lastResult(regionId)

    fun recoverPersisted(reason: String): Int =
        ModeMatchPersistence.recover(plugin.matchStore(), ::isRaceMode, reason)

    /** 本人在这块场地报名册里的状态；没报名返回 null。大厅按钮据此决定显示报名/准备/退出。 */
    fun participantState(regionId: String, playerId: java.util.UUID): org.cubexmc.regions.match.ParticipantState? =
        states[regionId]?.roster?.stateOf(playerId)

    /** 场地当前的比赛阶段；没有进行中的局返回 null。 */
    fun phaseOf(regionId: String): MatchPhase? = states[regionId]?.roster?.phase

    fun isSpectating(regionId: String, playerId: java.util.UUID): Boolean =
        states[regionId]?.roster?.isSpectator(playerId) == true

    fun isGearEscrowed(playerId: UUID): Boolean = escrow.isEscrowed(playerId)

    /** 比赛进行中的选手：命令封锁与伤害隔离都用它。 */
    fun isRacing(playerId: UUID): Boolean =
        states.values.any { it.roster.phase == MatchPhase.RUNNING && it.roster.isParticipant(playerId) }

    /** 成员关系查询：一名玩家在一场竞速里最多只有一条记录。 */
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

    fun participantIds(regionId: String): Set<UUID> = states[regionId]?.roster?.participantIds().orEmpty()

    // ------------------------------------------------------------ 辅助

    private fun stateFor(region: RegionDefinition): RaceState =
        states.computeIfAbsent(region.id) { RaceState(region) }

    private fun modeValues(region: RegionDefinition): Map<String, String> = region.mode?.values ?: emptyMap()

    private fun matchesVehicle(player: Player, constraint: String): Boolean =
        RaceCourse.matches(player.vehicle?.type?.name, constraint)

    private fun constraintArg(constraint: String): Map<String, GameArg> {
        val key = RaceCourse.constraintLabelKey(constraint)
        return mapOf("vehicle" to if (key != null) GameArg.key(key) else GameArg.literal(constraint))
    }

    private fun nearStart(player: Player, region: RegionDefinition): Boolean {
        val values = modeValues(region)
        if (values["require-start"]?.toBooleanStrictOrNull() == false) return true
        val start = parseLocation(values["start"]) ?: return true
        return near(player.location, start, RaceCourse.radius(values, "start-radius"))
    }

    private fun broadcast(state: RaceState, key: String, placeholders: Map<String, String> = emptyMap()) {
        broadcastLocalized(state, key, GameArg.literals(placeholders))
    }

    /** 占位符里有语言键时，每个接收者各自解析一次（PLAN.md §4.2）。观战者也收广播。 */
    private fun broadcastLocalized(state: RaceState, key: String, placeholders: Map<String, GameArg>) {
        val recipients = state.roster.participantIds() + state.roster.spectatorIds()
        for (playerId in recipients) {
            val player = plugin.server.getPlayer(playerId) ?: continue
            plugin.regionScheduler().runAtEntity(player, Runnable {
                plugin.sendGameLocalized(player, key, placeholders)
            })
        }
    }

    private fun outsideLocation(region: RegionDefinition): Location? {
        val values = modeValues(region)
        return parseLocation(values["respawn"] ?: values["outside"] ?: values["spectator"])
    }

    private fun near(current: Location, target: Location, radius: Double): Boolean {
        if (current.world?.uid != target.world?.uid) return false
        return current.distanceSquared(target) <= radius * radius
    }

    private fun parseLocations(raw: String?): List<Location> =
        raw?.split(';')?.mapNotNull { parseLocation(it) } ?: emptyList()

    private fun parseLocation(raw: String?): Location? {
        if (raw.isNullOrBlank()) return null
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

    private class RaceState(val region: RegionDefinition) {
        val regionId = region.id
        val roster = ModeRoster(regionId)
        val progress: ConcurrentHashMap<UUID, Int> = ConcurrentHashMap()

        /** 完赛顺序；`LinkedHashSet` 既保序又天然去重（重复触发只记一次名次）。 */
        val finishOrder: MutableSet<UUID> = java.util.Collections.synchronizedSet(LinkedHashSet())

        @Volatile
        var starting: Boolean = false

        /** 每开一局 +1；延迟任务据此确认自己属于哪一局。 */
        @Volatile
        var generation: Int = 0
    }

    private data class RaceStartCheck(val atStart: Boolean, val validVehicle: Boolean)

    private companion object {
        const val ESCROW_FILE = "race-escrow.yml"
        const val DEFAULT_PROMPT_COOLDOWN = 60L
    }
}
