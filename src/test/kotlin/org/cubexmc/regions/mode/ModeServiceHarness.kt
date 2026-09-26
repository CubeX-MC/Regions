package org.cubexmc.regions.mode

import org.bukkit.GameMode
import org.bukkit.Server
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import org.cubexmc.core.CubexLogger
import org.cubexmc.regions.RegionsPlugin
import org.cubexmc.regions.config.LanguageManager
import org.cubexmc.regions.effect.ScopedEffectService
import org.cubexmc.regions.match.MatchStore
import org.cubexmc.regions.model.ModeConfig
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionSourceRef
import org.cubexmc.regions.reward.FundingResult
import org.cubexmc.regions.reward.RewardFundingRuntime
import org.cubexmc.regions.service.RegionAuditService
import org.cubexmc.regions.service.RegionAuthorityService
import org.cubexmc.regions.service.RegionRegistry
import org.cubexmc.regions.service.RegionSessionService
import org.cubexmc.regions.service.RegionTriggerService
import org.cubexmc.regions.service.ServiceResult
import org.cubexmc.scheduler.CubexScheduler
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.nio.file.Path
import java.util.UUID
import java.util.logging.Logger

/**
 * 玩法服务的共享测试装置。
 *
 * 三类玩法（战斗 / 竞速 / 捉迷藏）现在共用同一批依赖——报名册、装备托管、
 * 结果 store、调度器——测试装置也就没有理由各写一份。调度器把实体任务与延迟任务
 * 收集起来，测试可以自己决定什么时候跑，用来验证"旧局的计时器不能结束新局"这类问题。
 */
internal class ModeServiceHarness(
    tempDir: Path,
    loggerName: String,
    runEntityTasksImmediately: Boolean = true,
) {
    val plugin: RegionsPlugin = mock(RegionsPlugin::class.java)
    val server: Server = mock(Server::class.java)
    val scheduler: CubexScheduler = mock(CubexScheduler::class.java)
    val registry: RegionRegistry = mock(RegionRegistry::class.java)
    val sessions: RegionSessionService = mock(RegionSessionService::class.java)
    val triggers: RegionTriggerService = mock(RegionTriggerService::class.java)
    val effects: ScopedEffectService = mock(ScopedEffectService::class.java)
    val audit: RegionAuditService = mock(RegionAuditService::class.java)
    val authority: RegionAuthorityService = mock(RegionAuthorityService::class.java)
    val matchStore: MatchStore =
        MatchStore(tempDir.resolve("matches.yml").toFile(), CubexLogger(Logger.getLogger(loggerName)))

    /** 收集到的延迟任务（倒计时、超时、释放搜寻者）。 */
    val delayed: MutableList<DelayedTask> = mutableListOf()

    /** 收集到的实体任务（发装备、传送、写背包）。 */
    val entityTasks: MutableList<Runnable> = mutableListOf()

    init {
        val admission = org.cubexmc.regions.match.MatchAdmission()
        `when`(plugin.reserveMatchEntry(anyK(), anyK())).thenAnswer {
            admission.reserve(it.getArgument(0), it.getArgument(1))
        }
        doAnswer { admission.release(it.getArgument(0), it.getArgument(1)); null }
            .`when`(plugin).releaseMatchEntry(anyK(), anyK())
        doAnswer { admission.releaseMatch(it.getArgument(0)); null }
            .`when`(plugin).releaseMatchEntries(anyK())
        val rewards = object : RewardFundingRuntime {
            override fun check(region: RegionDefinition) = FundingResult.ok()
            override fun reserve(region: RegionDefinition) = FundingResult.ok()
            override fun settle(region: RegionDefinition, winnerCandidates: Set<UUID>) = FundingResult.ok()
            override fun refund(region: RegionDefinition, reason: String) = FundingResult.ok()
            override fun reconcile(): List<FundingResult> = emptyList()
        }
        `when`(plugin.server).thenReturn(server)
        `when`(plugin.dataFolder).thenReturn(tempDir.toFile())
        `when`(plugin.log()).thenReturn(CubexLogger(Logger.getLogger(loggerName)))
        `when`(plugin.regionScheduler()).thenReturn(scheduler)
        `when`(plugin.regions()).thenReturn(registry)
        `when`(plugin.sessions()).thenReturn(sessions)
        `when`(plugin.triggers()).thenReturn(triggers)
        `when`(plugin.effects()).thenReturn(effects)
        `when`(plugin.audit()).thenReturn(audit)
        `when`(plugin.authority()).thenReturn(authority)
        `when`(plugin.rewards()).thenReturn(rewards)
        `when`(plugin.matchStore()).thenReturn(matchStore)
        // 玩法服务会读 modes.entry-prompt-cooldown-seconds 之类的配置；给一份空配置，
        // 让它们走各自的默认值，而不是 NPE。
        `when`(plugin.config).thenReturn(YamlConfiguration())
        // 玩法服务的每一句玩家可见文案都走语言文件；把键原样回显，
        // 让这些用例考察状态转移而不是措辞。
        val lang = mock(LanguageManager::class.java)
        `when`(lang.message(anyK(), anyK())).thenAnswer { it.getArgument<String>(0) }
        `when`(lang.messageFor(anyK(), anyK(), anyK())).thenAnswer { it.getArgument<String>(1) }
        `when`(plugin.lang()).thenReturn(lang)
        `when`(scheduler.isFolia).thenReturn(false)
        `when`(effects.apply(anyK(), anyK(), anyK())).thenReturn(ServiceResult.ok())
        doAnswer { invocation ->
            val task = invocation.getArgument<Runnable>(1)
            entityTasks += task
            if (runEntityTasksImmediately) task.run()
            null
        }.`when`(scheduler).runAtEntity(anyK<Entity>(), anyK<Runnable>())
        doAnswer { invocation ->
            delayed += DelayedTask(invocation.getArgument(0), invocation.getArgument(1))
            null
        }.`when`(scheduler).runGlobalLater(anyK<Runnable>(), anyLong())
    }

    fun register(region: RegionDefinition, vararg players: Player) {
        `when`(registry.find(region.id)).thenReturn(region)
        players.forEach { `when`(server.getPlayer(it.uniqueId)).thenReturn(it) }
    }

    /** 收集到的延迟任务里，延迟恰好为 [ticks] 的那一个。 */
    fun delayedAt(ticks: Long): Runnable = delayed.single { it.delay == ticks }.task

    fun runEntityTasks() {
        entityTasks.toList().forEach(Runnable::run)
    }

    data class DelayedTask(val task: Runnable, val delay: Long)

    companion object {
        fun region(id: String, type: String, values: Map<String, String>) = RegionDefinition(
            id = id,
            name = id,
            source = RegionSourceRef("cuboid"),
            mode = ModeConfig(type, values),
        )

        /**
         * 带可用背包的玩家 mock：装备托管会读 `contents` / `armorContents` / `itemInOffHand`，
         * 所以这三个必须给出可克隆的值，否则快照捕获会 NPE。
         */
        fun player(name: String): PlayerMock {
            val player = mock(Player::class.java)
            val inventory = mock(PlayerInventory::class.java)
            val offhand = mock(ItemStack::class.java)
            `when`(offhand.clone()).thenReturn(offhand)
            // escrow 落盘会把副手物品序列化；mock 默认返回 null，会在写盘时 NPE。
            `when`(offhand.serializeAsBytes()).thenReturn(byteArrayOf(1))
            `when`(inventory.contents).thenReturn(emptyArray())
            `when`(inventory.armorContents).thenReturn(emptyArray())
            `when`(inventory.itemInOffHand).thenReturn(offhand)
            `when`(player.inventory).thenReturn(inventory)
            `when`(player.name).thenReturn(name)
            `when`(player.uniqueId).thenReturn(UUID.randomUUID())
            // 入场前快照会读游戏模式；mock 默认返回 null，会让快照捕获直接 NPE。
            `when`(player.gameMode).thenReturn(GameMode.SURVIVAL)
            return PlayerMock(player, inventory)
        }
    }

    data class PlayerMock(val player: Player, val inventory: PlayerInventory)
}

@Suppress("UNCHECKED_CAST")
private fun <T> anyK(): T {
    org.mockito.Mockito.any<T>()
    return null as T
}
