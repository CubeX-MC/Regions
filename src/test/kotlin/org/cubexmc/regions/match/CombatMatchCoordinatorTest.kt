package org.cubexmc.regions.match

import org.bukkit.GameMode
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.inventory.PlayerInventory
import org.cubexmc.core.CubexLogger
import org.cubexmc.regions.RegionsPlugin
import org.cubexmc.regions.config.LanguageManager
import org.cubexmc.regions.effect.ScopedEffectService
import org.cubexmc.regions.integration.UnionProvider
import org.cubexmc.regions.integration.UnionProviderRegistry
import org.cubexmc.regions.mode.CombatGearStore
import org.cubexmc.regions.mode.GamePhase
import org.cubexmc.regions.model.ModeConfig
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionSourceRef
import org.cubexmc.regions.model.UnionRef
import org.cubexmc.regions.reward.FundingResult
import org.cubexmc.regions.reward.FundingSettlement
import org.cubexmc.regions.reward.RewardFundingRuntime
import org.cubexmc.regions.service.RegionAuditService
import org.cubexmc.regions.service.RegionRegistry
import org.cubexmc.regions.service.RegionSessionService
import org.cubexmc.regions.service.RegionTriggerService
import org.cubexmc.regions.service.ServiceResult
import org.cubexmc.scheduler.CubexScheduler
import org.cubexmc.scheduler.CubexTask
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyMap
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.nio.file.Path
import java.util.UUID
import java.util.logging.Logger

/**
 * 比赛状态机、准备屏障、结算与恢复（PLAN.md §6、§10.1 的 `CombatMatchCoordinatorTest`）。
 *
 * 每个用例都通过真实的 [CombatMatchCoordinator]（只把 Bukkit 与调度器换成 mock）驱动，
 * 因此验证的是真实落盘、真实 escrow 与真实阶段迁移，而不是判定辅助函数。
 */
class CombatMatchCoordinatorTest {

    @TempDir
    lateinit var tempDir: Path

    /** 提供方对"两个 Nation 是否敌对"的回答：null 表示无法验证。 */
    private var enemyRelation: Boolean? = null

    @Test
    fun `combat waits for teleport completion and aborts a failed teleport`() {
        val h = harness()
        val region = duelRegion()
        val first = player("First")
        val second = player("Second")
        h.register(region, first, second)
        val pending = java.util.concurrent.CompletableFuture<Boolean>()
        `when`(h.scheduler.teleportAsync(anyK<Entity>(), anyK<org.bukkit.Location>())).thenReturn(pending)
        val coordinator = h.coordinator()
        coordinator.join(first, region.id)
        coordinator.join(second, region.id)
        coordinator.ready(first, region.id)
        coordinator.ready(second, region.id)
        assertEquals(MatchPhase.PREPARING, h.matchStore.activeForRegion(region.id)?.phase)
        pending.complete(false)
        assertEquals(MatchOutcome.ABORTED, coordinator.result(region.id)?.outcome)
    }

    @Test
    fun `prepare barrier failure aborts the match and releases the escrow it already took`() {
        val harness = harness()
        val region = duelRegion()
        val first = player("First")
        val second = player("Second")
        harness.register(region, first, second)
        // 之前的阶段已经托管了 first 的装备；第二个人在屏障期间掉线，必须整体撤销并交还装备。
        harness.gearStore.put(first.uniqueId, region.id, emptySnapshot())
        harness.online -= second.uniqueId
        val coordinator = harness.coordinator()

        assertEquals(JoinResult.Joined, coordinator.join(first, region.id))
        assertEquals(JoinResult.Joined, coordinator.join(second, region.id))

        assertEquals(GamePhase.IDLE, coordinator.status(region.id).phase)
        assertNull(harness.gearStore.peek(first.uniqueId), "the abort must release the escrow")
        assertNull(coordinator.result(region.id)?.winnerIds?.firstOrNull(), "an aborted start has no winner")
    }

    @Test
    fun `countdown then a death decides a bo1 duel and restores both inventories`() {
        val harness = harness()
        val region = duelRegion()
        val first = player("First")
        val second = player("Second")
        harness.register(region, first, second)
        harness.gearStore.put(first.uniqueId, region.id, emptySnapshot())
        harness.gearStore.put(second.uniqueId, region.id, emptySnapshot())
        val coordinator = harness.coordinator()

        coordinator.join(first, region.id)
        coordinator.join(second, region.id)
        assertEquals(GamePhase.RUNNING, coordinator.status(region.id).phase, "COUNTDOWN already counts as live")
        assertEquals(1, coordinator.status(region.id).extra["round"])
        harness.runDelayed()
        assertEquals(GamePhase.RUNNING, coordinator.status(region.id).phase)

        assertTrue(coordinator.onDeath(deathOf(first)))
        // 观察窗口内不判定：同一批事件还没到齐。
        assertEquals(GamePhase.RUNNING, coordinator.status(region.id).phase)
        harness.settle()

        assertEquals(GamePhase.IDLE, coordinator.status(region.id).phase)
        assertNull(harness.gearStore.peek(first.uniqueId))
        assertNull(harness.gearStore.peek(second.uniqueId))
        assertEquals(setOf(second.uniqueId), coordinator.result(region.id)?.winnerIds)
    }

    @Test
    fun `mutual elimination inside the settlement window is a draw instead of a win for whoever died last`() {
        val harness = harness()
        val region = duelRegion()
        val first = player("First")
        val second = player("Second")
        harness.register(region, first, second)
        val coordinator = harness.coordinator()
        coordinator.join(first, region.id)
        coordinator.join(second, region.id)
        harness.runDelayed(COUNTDOWN_TICKS)

        // 范围伤害同归于尽：两次死亡落在同一个观察窗口里。
        assertTrue(coordinator.onDeath(deathOf(first)))
        assertTrue(coordinator.onDeath(deathOf(second)))
        harness.settle()

        val result = coordinator.result(region.id)
        assertEquals(MatchOutcome.DRAW, result?.outcome)
        assertTrue(result?.winnerIds.orEmpty().isEmpty(), "a shared wipe must not crown a winner")
        assertEquals(DuelRules.REASON_MUTUAL, result?.reasonKey)
    }

    @Test
    fun `a preparing barrier that never reports back times out instead of hanging forever`() {
        val harness = harness(deferEntityTasks = true)
        val region = duelRegion()
        val first = player("First")
        val second = player("Second")
        harness.register(region, first, second)
        val coordinator = harness.coordinator()

        coordinator.join(first, region.id)
        coordinator.join(second, region.id)
        assertEquals(GamePhase.RUNNING, coordinator.status(region.id).phase, "PREPARING 在状态上算进行中")

        harness.runDelayed(PREPARING_TICKS)

        assertEquals(GamePhase.IDLE, coordinator.status(region.id).phase, "屏障超时必须撤销开赛")
        // 迟到的准备任务不能再改玩家状态。
        harness.runEntityTasks()
        assertNull(harness.gearStore.peek(first.uniqueId))
    }

    @Test
    fun `bo3 survives one round loss and finishes on the second win`() {
        val harness = harness()
        val region = duelRegion(mapOf("best-of" to "3"))
        val first = player("First")
        val second = player("Second")
        harness.register(region, first, second)
        val coordinator = harness.coordinator()

        coordinator.join(first, region.id)
        coordinator.join(second, region.id)
        harness.runDelayed(COUNTDOWN_TICKS)
        assertTrue(coordinator.onDeath(deathOf(second)))
        harness.settle()
        assertEquals(1, coordinator.status(region.id).extra["round"], "the intermission still belongs to round 1")

        // 回合间休整结束后进入第二回合；此时又死一次才凑够两胜。
        harness.runDelayed(INTERMISSION_TICKS)
        assertEquals(GamePhase.RUNNING, coordinator.status(region.id).phase)
        assertEquals(2, coordinator.status(region.id).extra["round"])
        assertTrue(coordinator.onDeath(deathOf(second)))
        harness.settle()

        assertEquals(GamePhase.IDLE, coordinator.status(region.id).phase)
        val result = coordinator.result(region.id)
        assertEquals(setOf(first.uniqueId), result?.winnerIds)
        assertEquals(MatchOutcome.NATURAL, result?.outcome)
        assertEquals("2", result?.reasonArgs?.get("wins"), "BO3 is decided by two round wins")
    }

    @Test
    fun `a repeated death callback is ignored instead of eliminating twice`() {
        val harness = harness()
        val region = duelRegion(mapOf("best-of" to "3"))
        val first = player("First")
        val second = player("Second")
        harness.register(region, first, second)
        val coordinator = harness.coordinator()

        coordinator.join(first, region.id)
        coordinator.join(second, region.id)
        harness.runDelayed(COUNTDOWN_TICKS)
        assertTrue(coordinator.onDeath(deathOf(second)))
        // 第二次死亡回调（Folia 上不同区域任务、或重复事件）必须被拒绝，不能重复记淘汰。
        assertFalse(coordinator.onDeath(deathOf(second)))
        harness.settle()

        assertEquals(1, coordinator.participants(region.id).count { it.eliminatedOrder != null })
    }

    @Test
    fun `a player whose gear is still queued for recovery cannot sign up for a new match`() {
        val harness = harness()
        val region = duelRegion()
        val player = player("Waiting")
        harness.register(region, player)
        // 上一局只留下一条待恢复记录（运行时已经不在内存里）。
        harness.matchStore.put(
            MatchSnapshot(
                matchId = UUID.randomUUID(),
                regionId = "other-arena",
                publishedRevision = 1,
                modeType = "dual_pvp",
                createdAtMillis = 0,
                options = emptyMap(),
                participants = listOf(
                    MatchParticipant(player.uniqueId, player.name, null, ParticipantState.RESTORING),
                ),
                teams = emptyMap(),
                phase = MatchPhase.FINISHING,
                round = 1,
                result = null,
                pendingRestore = setOf(player.uniqueId),
                confirmedRestore = emptySet(),
            ),
        )
        harness.matchStore.save()
        val coordinator = harness.coordinator()

        val result = coordinator.join(player, region.id)

        assertTrue(result is JoinResult.Rejected)
        assertEquals("game.match.join.restoring", (result as JoinResult.Rejected).key)
    }

    @Test
    fun `restart recovery restores pending escrow and clears the durable record`() {
        val harness = harness()
        val region = duelRegion()
        val first = player("First")
        val second = player("Second")
        harness.register(region, first, second)
        val matchId = UUID.randomUUID()
        val snapshot = GearSnapshot(
            arrayOfNulls(0),
            arrayOfNulls(0),
            null,
            5,
            0f,
            GameMode.SURVIVAL,
            null,
        )
        harness.gearStore.put(first.uniqueId, region.id, snapshot)
        harness.matchStore.put(
            MatchSnapshot(
                matchId = matchId,
                regionId = region.id,
                publishedRevision = 1,
                modeType = "dual_pvp",
                createdAtMillis = 0,
                options = emptyMap(),
                participants = listOf(
                    MatchParticipant(first.uniqueId, "First", first.uniqueId.toString(), ParticipantState.ELIMINATED),
                    MatchParticipant(second.uniqueId, "Second", second.uniqueId.toString(), ParticipantState.RESTORING),
                ),
                teams = emptyMap(),
                phase = MatchPhase.FINISHING,
                round = 1,
                result = null,
                pendingRestore = setOf(first.uniqueId),
                confirmedRestore = emptySet(),
            ),
        )
        harness.matchStore.save()
        val coordinator = harness.coordinator()

        coordinator.recoverPersisted("test-restart")

        assertNull(harness.gearStore.peek(first.uniqueId), "an unfinished match must not keep gear hostage")
        assertTrue(harness.matchStore.all().isEmpty(), "the finished recovery record is cleared")
    }

    @Test
    fun `a confirmed recovery only clears the record instead of overwriting the new inventory`() {
        val harness = harness()
        val player = player("Returning")
        // 场地仍存在，恢复才会走"续收尾"路径而不是无主 escrow 的兜底路径。
        harness.register(duelRegion().copy(id = "arena", name = "Arena"), player)
        harness.gearStore.put(
            player.uniqueId,
            "arena",
            GearSnapshot(arrayOfNulls(0), arrayOfNulls(0), null, 9, 0f, GameMode.SURVIVAL, null),
        )
        val matchId = UUID.randomUUID()
        harness.matchStore.put(
            MatchSnapshot(
                matchId = matchId,
                regionId = "arena",
                publishedRevision = 1,
                modeType = "dual_pvp",
                createdAtMillis = 0,
                options = emptyMap(),
                participants = listOf(
                    MatchParticipant(player.uniqueId, "Returning", null, ParticipantState.RESTORING),
                ),
                teams = emptyMap(),
                phase = MatchPhase.FINISHING,
                round = 1,
                result = null,
                pendingRestore = setOf(player.uniqueId),
                confirmedRestore = setOf(player.uniqueId),
            ),
        )
        harness.matchStore.save()
        val coordinator = harness.coordinator()

        // 重启恢复看到 confirmed 标记：只补删 escrow，绝不把旧快照再写回背包。
        coordinator.recoverPersisted("test-restart")

        assertNull(harness.gearStore.peek(player.uniqueId))
        assertFalse(coordinator.restoreIfPending(player, "join-recovery"))
        org.mockito.Mockito.verify(player, org.mockito.Mockito.never()).updateInventory()
    }

    @Test
    fun `nation battle refuses to start until the venue owner picks two nations`() {
        val harness = harness()
        val region = nationRegion()
        val redOne = player("RedOne")
        val redTwo = player("RedTwo")
        val blueOne = player("BlueOne")
        val blueTwo = player("BlueTwo")
        harness.register(region, redOne, redTwo, blueOne, blueTwo)
        harness.nations[redOne.uniqueId] = listOf(UnionRef("red", "Red", "lands"))
        harness.nations[redTwo.uniqueId] = listOf(UnionRef("red", "Red", "lands"))
        harness.nations[blueOne.uniqueId] = listOf(UnionRef("blue", "Blue", "lands"))
        harness.nations[blueTwo.uniqueId] = listOf(UnionRef("blue", "Blue", "lands"))
        val coordinator = harness.coordinator()

        // 对阵是开赛前由场地主选定的比赛选项；列出之后就不能再改，也不能降级成个人战。
        assertTrue(coordinator.selectTeams(region.id, "red", "blue", "Red", "Blue"))
        coordinator.join(redOne, region.id)
        coordinator.join(redTwo, region.id)
        coordinator.join(blueOne, region.id)
        assertEquals(GamePhase.WAITING, coordinator.status(region.id).phase, "one side is still short")
        assertEquals(JoinResult.Joined, coordinator.join(blueTwo, region.id))

        assertEquals(GamePhase.RUNNING, coordinator.status(region.id).phase)
    }

    @Test
    fun `a player in several nations has to pick a side explicitly`() {
        val harness = harness()
        val region = nationRegion()
        val player = player("Dual")
        harness.register(region, player)
        harness.nations[player.uniqueId] = listOf(UnionRef("red", "Red", "lands"), UnionRef("blue", "Blue", "lands"))
        val coordinator = harness.coordinator()
        coordinator.selectTeams(region.id, "red", "blue", "Red", "Blue")

        val result = coordinator.join(player, region.id)

        assertTrue(result is JoinResult.TeamSelection)
        assertEquals(listOf("red", "blue"), (result as JoinResult.TeamSelection).candidates.map { it.id })
        assertEquals(JoinResult.Joined, coordinator.join(player, region.id, "blue"))
    }

    @Test
    fun `shutdown cleanup restores escrow synchronously instead of leaving it on disk`() {
        val harness = harness()
        val region = duelRegion()
        val player = player("Leaver")
        harness.register(region, player)
        // 上一次运行留下的托管记录：停服时必须就地交还，不能指望调度器还会执行任务。
        harness.gearStore.put(player.uniqueId, region.id, emptySnapshot())
        val coordinator = harness.coordinator()
        coordinator.join(player, region.id)

        coordinator.cleanupAll("plugin-disable", shuttingDown = true)

        assertNull(harness.gearStore.peek(player.uniqueId))
        assertEquals(GamePhase.IDLE, coordinator.status(region.id).phase)
    }

    @Test
    fun `enemy-only diplomacy refuses to start when the relation cannot be verified`() {
        val harness = harness()
        val region = nationRegion().copy(
            mode = ModeConfig(
                "union_war",
                (nationRegion().mode?.values ?: emptyMap()) + mapOf("diplomacy" to "enemy-only"),
            ),
        )
        val red = player("Red")
        val blue = player("Blue")
        harness.register(region, red, blue)
        harness.nations[red.uniqueId] = listOf(UnionRef("red", "Red", "lands"))
        harness.nations[blue.uniqueId] = listOf(UnionRef("blue", "Blue", "lands"))
        // 提供方无法证明敌对关系（返回 null）→ 拒绝报名，绝不把"未知"当成"非敌对"。
        enemyRelation = null
        val coordinator = harness.coordinator()
        coordinator.selectTeams(region.id, "red", "blue", "Red", "Blue")

        val result = coordinator.join(red, region.id)

        assertTrue(result is JoinResult.Rejected)
        assertEquals("game.match.diplomacy.unverifiable", (result as JoinResult.Rejected).key)

        enemyRelation = true
        assertEquals(JoinResult.Joined, coordinator.join(red, region.id))
    }

    @Test
    fun `damage is only allowed inside a running match with living opponents`() {
        val harness = harness()
        val region = duelRegion()
        val first = player("First")
        val second = player("Second")
        val outsider = player("Outsider")
        harness.register(region, first, second, outsider)
        val coordinator = harness.coordinator()
        coordinator.join(first, region.id)

        assertEquals(
            DamageDecision.DENY,
            coordinator.damageDecision(first.uniqueId, second.uniqueId, playerSourced = true),
        )
        assertEquals(
            DamageDecision.DENY,
            coordinator.damageDecision(outsider.uniqueId, first.uniqueId, playerSourced = true),
        )
        // 生物/环境伤害不参与成员隔离，环境死亡照样计入淘汰。
        assertEquals(
            DamageDecision.UNRELATED,
            coordinator.damageDecision(null, first.uniqueId, playerSourced = false),
        )
    }

    // ------------------------------------------------------------ 测试台

    private inner class Harness(
        val plugin: RegionsPlugin,
        val server: Server,
        val scheduler: CubexScheduler,
        val registry: RegionRegistry,
        val gearStore: CombatGearStore,
        val matchStore: MatchStore,
        val rewards: RecordingRewards,
        val online: MutableSet<UUID>,
        val worlds: MutableMap<String, World>,
        val nations: MutableMap<UUID, List<UnionRef>>,
        val entityTasks: MutableList<() -> Unit>,
        val delayed: MutableList<Pair<Long, () -> Unit>>,
    ) {
        val now = longArrayOf(1_000L)

        fun coordinator(): CombatMatchCoordinator {
            val clock = { now[0] }
            return CombatMatchCoordinator(plugin, gearStore, matchStore, clock)
        }

        fun register(region: RegionDefinition, vararg players: Player) {
            `when`(registry.find(region.id)).thenReturn(region)
            players.forEach { player ->
                val id = player.uniqueId
                online += id
                `when`(server.getPlayer(id)).thenAnswer { if (id in online) player else null }
            }
        }

        fun registerPlayers(vararg players: Player) {
            players.forEach { player ->
                val id = player.uniqueId
                online += id
                `when`(server.getPlayer(id)).thenAnswer { if (id in online) player else null }
            }
        }

        /**
         * 执行排队的延迟任务。传 [delayTicks] 时只跑该延迟的那一批（否则会把倒计时、休整、
         * 结算窗口一起跑掉，分不清是哪一步起了作用）；[advanceMillis] 决定推进多少毫秒。
         */
        fun runDelayed(delayTicks: Long? = null, advanceMillis: Long = 250L) {
            val pending = if (delayTicks == null) delayed.toList() else delayed.filter { it.first == delayTicks }
            delayed.removeAll(pending)
            now[0] += advanceMillis
            pending.forEach { it.second() }
        }

        /** 推进结算观察窗口（200 毫秒 / 4 tick）。 */
        fun settle() = runDelayed(CombatMatchCoordinator.SETTLEMENT_WINDOW_TICKS)

        /** 执行被推迟的实体任务（屏障超时用例里用来验证"迟到的回执不再生效"）。 */
        fun runEntityTasks() = entityTasks.toList().forEach { it() }
    }

    private fun harness(deferEntityTasks: Boolean = false): Harness {
        val plugin = mock(RegionsPlugin::class.java)
        val server = mock(Server::class.java)
        val scheduler = mock(CubexScheduler::class.java)
        val registry = mock(RegionRegistry::class.java)
        val sessions = mock(RegionSessionService::class.java)
        val triggers = mock(RegionTriggerService::class.java)
        val effects = mock(ScopedEffectService::class.java)
        val audit = mock(RegionAuditService::class.java)
        val rewards = RecordingRewards()
        val unions = mock(UnionProviderRegistry::class.java)
        val nations = HashMap<UUID, List<UnionRef>>()
        val provider = object : UnionProvider {
            override val type = "test-lands"
            override fun isAvailable() = true
            override fun getUnion(playerId: UUID) = nations[playerId]?.firstOrNull()
            override fun getUnions(playerId: UUID) = nations[playerId].orEmpty()
            override fun areSameUnion(a: UUID, b: UUID) = false
            override fun areAllied(a: UUID, b: UUID) = false
            override fun areEnemies(a: UUID, b: UUID) = enemyRelation == true
            override fun areNationsEnemy(a: String, b: String) = enemyRelation
            override fun placeholder(playerId: UUID, key: String): String? = null
            override fun allUnions() = nations.values.flatten().distinctBy { it.id }
        }
        val config = mock(FileConfiguration::class.java)
        val worlds = HashMap<String, World>()
        val entityTasks = mutableListOf<() -> Unit>()
        val delayed = mutableListOf<Pair<Long, () -> Unit>>()

        `when`(plugin.server).thenReturn(server)
        `when`(plugin.dataFolder).thenReturn(tempDir.toFile())
        `when`(plugin.log()).thenReturn(CubexLogger(Logger.getLogger("CombatMatchCoordinatorTest")))
        `when`(plugin.regionScheduler()).thenReturn(scheduler)
        `when`(plugin.regions()).thenReturn(registry)
        `when`(plugin.sessions()).thenReturn(sessions)
        `when`(plugin.triggers()).thenReturn(triggers)
        `when`(plugin.effects()).thenReturn(effects)
        `when`(plugin.audit()).thenReturn(audit)
        `when`(plugin.rewards()).thenReturn(rewards)
        `when`(plugin.unions()).thenReturn(unions)
        `when`(plugin.config).thenReturn(config)
        `when`(unions.active()).thenReturn(provider)
        `when`(config.getLong(anyString(), anyLong())).thenAnswer { it.getArgument<Long>(1) }
        val lang = mock(LanguageManager::class.java)
        // Kotlin 的默认参数会走 `$default` 合成方法（真实字节码，不在 mock 覆盖范围内），
        // 所以这里用返回非 null 的匹配器，避免参数空检查在桩设置阶段就抛错。
        `when`(lang.message(anyString(), anyMap<String, String>())).thenAnswer { it.getArgument<String>(0) }
        `when`(lang.messageFor(any(), anyString(), anyMap<String, String>())).thenAnswer { it.getArgument<String>(1) }
        `when`(lang.label(anyString(), anyString())).thenAnswer { it.getArgument<String>(1) }
        `when`(plugin.lang()).thenReturn(lang)
        `when`(scheduler.isFolia).thenReturn(false)
        `when`(server.getWorld(anyString())).thenAnswer { worlds[it.getArgument<String>(0)] }
        `when`(effects.apply(anyK(), anyK(), anyK())).thenReturn(ServiceResult.ok())
        doAnswer { invocation ->
            val task = invocation.getArgument<Runnable>(1)
            entityTasks += { task.run() }
            if (!deferEntityTasks) task.run()
            null
        }.`when`(scheduler).runAtEntity(anyK<Entity>(), anyK<Runnable>())
        doAnswer { invocation ->
            val task = invocation.getArgument<Runnable>(0)
            val delay = invocation.getArgument<Long>(1)
            delayed += delay to { task.run() }
            null
        }.`when`(scheduler).runGlobalLater(anyK<Runnable>(), anyLong())
        `when`(scheduler.runGlobalTimer(anyK<Runnable>(), anyLong(), anyLong()))
            .thenReturn(mock(CubexTask::class.java))
        doAnswer { java.util.concurrent.CompletableFuture.completedFuture(true) }
            .`when`(scheduler).teleportAsync(anyK<Entity>(), anyK<org.bukkit.Location>())

        val world = mock(World::class.java)
        worlds["world"] = world
        val gearStore = CombatGearStore(plugin).apply { load() }
        val matchStore = MatchStore(tempDir.resolve("matches.yml").toFile(), CubexLogger(Logger.getLogger("MatchStore")))
            .apply { reload() }
        return Harness(
            plugin,
            server,
            scheduler,
            registry,
            gearStore,
            matchStore,
            rewards,
            HashSet(),
            worlds,
            nations,
            entityTasks,
            delayed,
        )
    }

    private fun player(name: String): Player {
        val player = mock(Player::class.java)
        val inventory = mock(PlayerInventory::class.java)
        `when`(player.uniqueId).thenReturn(UUID.randomUUID())
        `when`(player.name).thenReturn(name)
        `when`(player.inventory).thenReturn(inventory)
        `when`(inventory.contents).thenReturn(arrayOfNulls(0))
        `when`(inventory.armorContents).thenReturn(arrayOfNulls(0))
        `when`(player.gameMode).thenReturn(GameMode.SURVIVAL)
        return player
    }

    /** 与 [duelRegion] 的 `countdown-seconds: 1` / `intermission-seconds: 1` 对应。 */
    private val COUNTDOWN_TICKS = 20L
    private val INTERMISSION_TICKS = 20L
    private val PREPARING_TICKS = 200L

    private fun deathOf(player: Player): PlayerDeathEvent {
        val event = mock(PlayerDeathEvent::class.java)
        `when`(event.entity).thenReturn(player)
        `when`(event.drops).thenReturn(mutableListOf())
        return event
    }

    /** 单测里不能碰 Paper 的物品序列化，所以托管快照一律用空物品数组。 */
    private fun emptySnapshot() =
        GearSnapshot(arrayOfNulls(0), arrayOfNulls(0), null, 0, 0f, GameMode.SURVIVAL, null)

    /** 与仓库其它用例一致：用于 Kotlin 非空参数位置的 Mockito 匹配器。 */
    @Suppress("UNCHECKED_CAST")
    private fun <T> anyK(): T {
        any<T>()
        return null as T
    }

    /**
     * 决斗场地。故意不配 `kit`：单测环境没有 Paper 的物品注册表，
     * `ItemStack` 序列化会抛错；托管路径由预置的 escrow 记录驱动（同 [CombatGearRestoreTest]）。
     */
    private fun duelRegion(extra: Map<String, String> = emptyMap()) = RegionDefinition(
        id = "duel",
        name = "Duel",
        source = RegionSourceRef("cuboid"),
        mode = ModeConfig(
            "dual_pvp",
            mapOf(
                "require-ready" to "false",
                "replace-gear" to "false",
                "spawn-points" to "world,0,64,0;world,10,64,0",
                "countdown-seconds" to "1",
                "intermission-seconds" to "1",
            ) + extra,
        ),
    )

    private fun nationRegion() = RegionDefinition(
        id = "war",
        name = "War",
        source = RegionSourceRef("cuboid"),
        mode = ModeConfig(
            "union_war",
            mapOf(
                "team-size" to "2",
                "require-ready" to "false",
                "replace-gear" to "false",
                "countdown-seconds" to "1",
                "spawn-points" to "world,0,64,0;world,1,64,0",
                "spawn-points-b" to "world,10,64,0;world,11,64,0",
            ),
        ),
    )

    private class RecordingRewards : RewardFundingRuntime {        var reserves = 0
        var refunds = 0
        var settlements = mutableListOf<FundingSettlement>()

        override fun check(region: RegionDefinition) = FundingResult.ok()
        override fun reserve(region: RegionDefinition): FundingResult {
            reserves++
            return FundingResult.ok()
        }

        override fun settle(region: RegionDefinition, winnerCandidates: Set<UUID>) = FundingResult.ok()
        override fun settle(region: RegionDefinition, evidence: FundingSettlement): FundingResult {
            settlements += evidence
            return FundingResult.ok()
        }

        override fun refund(region: RegionDefinition, reason: String): FundingResult {
            refunds++
            return FundingResult.ok()
        }

        override fun reconcile(): List<FundingResult> = emptyList()
    }
}
