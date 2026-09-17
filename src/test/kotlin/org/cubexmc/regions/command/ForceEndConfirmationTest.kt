package org.cubexmc.regions.command

import io.papermc.paper.command.brigadier.CommandSourceStack
import org.bukkit.entity.Player
import org.cubexmc.core.CubexLogger
import org.cubexmc.regions.RegionsPlugin
import org.cubexmc.regions.config.LanguageManager
import org.cubexmc.regions.mode.CombatModeService
import org.cubexmc.regions.mode.GamePhase
import org.cubexmc.regions.mode.GameStatus
import org.cubexmc.regions.mode.RaceModeService
import org.cubexmc.regions.mode.RoundModeService
import org.cubexmc.regions.model.ModeConfig
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionSourceRef
import org.cubexmc.regions.service.AuthorityDecision
import org.cubexmc.regions.service.RegionAuditService
import org.cubexmc.regions.service.RegionAuthorityService
import org.cubexmc.regions.service.RegionRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.UUID
import java.util.logging.Logger

/**
 * 强制结束的两步确认（PLAN.md §5.2：强制结束必须显示对象与影响后确认）。
 *
 * 这条是安全行为，必须有用例钉住"第一次调用不结束、第二次才结束"——
 * 否则改坏了会变成"一次误触直接结算掉正在打的比赛"。
 */
class ForceEndConfirmationTest {

    @Test
    fun `the first end command only reports the impact and the second one ends the match`() {
        val harness = Harness()
        harness.stubRunningCombatMatch()

        harness.execute("end")

        // 第一次只说明影响：比赛没有被结束。
        verify(harness.combat, never()).forceEnd(REGION_ID, "manual-command")
        verify(harness.lang).sendPlain(harness.player, "command.game-end-confirm", harness.lastArgs)

        harness.execute("end")

        verify(harness.combat, times(1)).forceEnd(REGION_ID, "manual-command")
        verify(harness.audit).record(harness.player, REGION_ID, "game.end", "manual-command")
    }

    @Test
    fun `ending a venue with no match in progress is reported instead of confirming`() {
        val harness = Harness()
        harness.stubIdleCombatVenue()

        harness.execute("end")

        verify(harness.combat, never()).forceEnd(REGION_ID, "manual-command")
        verify(harness.lang).sendPlain(harness.player, "command.game-not-running", mapOf("id" to REGION_ID))
    }

    private class Harness {
        val plugin: RegionsPlugin = mock(RegionsPlugin::class.java)
        val combat: CombatModeService = mock(CombatModeService::class.java)
        val races: RaceModeService = mock(RaceModeService::class.java)
        val rounds: RoundModeService = mock(RoundModeService::class.java)
        val lang: LanguageManager = mock(LanguageManager::class.java)
        val audit: RegionAuditService = mock(RegionAuditService::class.java)
        val player: Player = mock(Player::class.java)
        val region = RegionDefinition(
            id = REGION_ID,
            name = "Arena",
            source = RegionSourceRef("cuboid"),
            mode = ModeConfig("dual_pvp"),
        )
        var lastArgs: Map<String, String> = emptyMap()

        init {
            `when`(player.uniqueId).thenReturn(UUID.randomUUID())
            `when`(player.name).thenReturn("Judge")
            `when`(player.hasPermission(org.mockito.ArgumentMatchers.anyString())).thenReturn(true)
            `when`(plugin.log()).thenReturn(CubexLogger(Logger.getLogger("ForceEndConfirmationTest")))
            `when`(plugin.lang()).thenReturn(lang)
            `when`(plugin.audit()).thenReturn(audit)
            `when`(plugin.combatModes()).thenReturn(combat)
            `when`(plugin.raceModes()).thenReturn(races)
            `when`(plugin.roundModes()).thenReturn(rounds)
            val registry: RegionRegistry = mock(RegionRegistry::class.java)
            `when`(registry.find(REGION_ID)).thenReturn(region)
            `when`(plugin.regions()).thenReturn(registry)
            val authority: RegionAuthorityService = mock(RegionAuthorityService::class.java)
            `when`(authority.canManage(player, region)).thenReturn(AuthorityDecision.allow())
            `when`(authority.canEnterManagement(player)).thenReturn(AuthorityDecision.allow())
            `when`(authority.isRuler(player)).thenReturn(false)
            `when`(authority.isSuperAdmin(player)).thenReturn(false)
            `when`(plugin.authority()).thenReturn(authority)
            `when`(races.isRaceMode(region)).thenReturn(false)
            `when`(rounds.isRoundMode(region)).thenReturn(false)
            `when`(combat.isCombatMode(region)).thenReturn(true)
            `when`(combat.forceEnd(REGION_ID, "manual-command")).thenReturn(true)
            // 记录确认消息实际带了哪些参数，避免"发了消息但占位符全空"。
            org.mockito.Mockito.doAnswer { invocation ->
                @Suppress("UNCHECKED_CAST")
                lastArgs = invocation.getArgument(2) as Map<String, String>
                null
            }.`when`(lang).sendPlain(anyK(), anyK<String>(), anyK())
        }

        fun stubRunningCombatMatch() {
            `when`(combat.status(REGION_ID)).thenReturn(GameStatus(REGION_ID, "dual_pvp", GamePhase.RUNNING, players = 2))
        }

        fun stubIdleCombatVenue() {
            `when`(combat.status(REGION_ID)).thenReturn(GameStatus(REGION_ID, "dual_pvp", GamePhase.IDLE))
        }

        /**
         * 生产环境里 `RegionsCommand` 是插件启用时注册的**单实例**（确认状态挂在它身上），
         * 所以测试也必须复用同一个实例——每次新建会让"两步确认"永远停在第一步。
         */
        private val command: RegionsCommand = RegionsCommand(plugin)

        fun execute(action: String) {
            val source: CommandSourceStack = mock(CommandSourceStack::class.java)
            `when`(source.sender).thenReturn(player)
            command.execute(source, arrayOf("game", REGION_ID, action))
        }
    }

    private companion object {
        const val REGION_ID = "arena"

        /** Kotlin 非空参数位置上的 Mockito 匹配器（见仓库其它用例的同一写法）。 */
        @Suppress("UNCHECKED_CAST")
        fun <T> anyK(): T {
            org.mockito.ArgumentMatchers.any<T>()
            return null as T
        }
    }
}
