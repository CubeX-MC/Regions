package org.cubexmc.regions.mode

import org.cubexmc.regions.match.JoinResult
import org.cubexmc.regions.match.MatchOutcome
import org.cubexmc.regions.match.MatchPhase
import org.cubexmc.regions.match.ParticipantState
import org.cubexmc.regions.match.SpectateResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import java.nio.file.Path

/**
 * 竞速与捉迷藏补齐到与决斗同级之后的行为门禁。
 *
 * 每一条都对应一个**此前真实存在**的差距：走进区域就被拉进比赛、装备键设了不生效、
 * 打完没有结果、没有观战、退出没有入口。
 */
class ModeParityTest {

    @TempDir
    lateinit var tempDir: Path

    private fun harness(runEntityTasksImmediately: Boolean = true) =
        ModeServiceHarness(tempDir, "ModeParityTest", runEntityTasksImmediately)

    private fun raceRegion(values: Map<String, String> = emptyMap()) = ModeServiceHarness.region(
        "track",
        "run_race",
        mapOf(
            "min-players" to "1",
            "require-start" to "false",
            "vehicle" to "pass",
            "timeout-seconds" to "60",
        ) + values,
    )

    private fun roundRegion(values: Map<String, String> = emptyMap()) = ModeServiceHarness.region(
        "hide",
        "hide_and_seek",
        mapOf(
            "min-players" to "2",
            "hide-seconds" to "5",
            "round-seconds" to "30",
            "replace-gear" to "false",
        ) + values,
    )

    // ------------------------------------------------------ 走进区域不等于报名

    @Test
    fun `walking into a race prompts but never enrols`() {
        val h = harness()
        val region = raceRegion()
        val runner = ModeServiceHarness.player("Runner")
        h.register(region, runner.player)
        val service = RaceModeService(h.plugin)

        service.onEnter(runner.player, region)

        assertEquals(GamePhase.IDLE, service.status(region.id).phase, "只是路过就开了一场比赛")
        assertNull(service.participantState(region.id, runner.player.uniqueId), "走进赛道不该进名单")
        // 没有报名自然也不该动背包。
        verify(runner.inventory, never()).clear()
    }

    @Test
    fun `walking into a hide and seek venue prompts but never enrols`() {
        val h = harness()
        val region = roundRegion()
        val hider = ModeServiceHarness.player("Hider")
        h.register(region, hider.player)
        val service = RoundModeService(h.plugin)

        service.onEnter(hider.player, region)

        assertEquals(GamePhase.IDLE, service.status(region.id).phase)
        assertNull(service.participantState(region.id, hider.player.uniqueId))
    }

    /** 反复进出也只是提示，名单始终为空——提示冷却与战斗层共用同一个配置键。 */
    @Test
    fun `repeated entries never build up a roster`() {
        val h = harness()
        val region = raceRegion()
        val runner = ModeServiceHarness.player("Runner")
        h.register(region, runner.player)
        val service = RaceModeService(h.plugin)

        repeat(5) { service.onEnter(runner.player, region) }

        assertEquals(GamePhase.IDLE, service.status(region.id).phase)
        assertNull(service.participantState(region.id, runner.player.uniqueId))
    }

    // ------------------------------------------------------ 装备真的生效并归还

    /**
     * 本轮最核心的回归：竞速此前**完全不读**任何装备键——校验通过、发布成功、
     * 运行时什么都不发生。这里断言两件事：换装之前先持久化了 escrow，
     * 比赛结束后又还了回去。
     *
     * 物品清单留空是刻意的：`ItemStack` 的构造需要真实的 Bukkit 注册表，
     * 在无服务端的单测里构造不出来。清空背包这一步只可能来自 [ModeKit.apply]，
     * 它被调用本身就足以证明装备链路接上了——此前它一次都不会被调用。
     */
    @Test
    fun `a race with replace-gear escrows the inventory and gives it back`() {
        val h = harness()
        val region = raceRegion(
            mapOf(
                "replace-gear" to "true",
                "respawn" to "world,0,64,0",
            ),
        )
        val runner = ModeServiceHarness.player("Runner")
        h.register(region, runner.player)
        val service = RaceModeService(h.plugin)

        assertTrue(service.join(runner.player, region.id) is JoinResult.Joined)
        assertTrue(service.ready(runner.player, region.id))
        assertEquals(GamePhase.RUNNING, service.status(region.id).phase)

        // 发装备：清空背包再按配置装填。
        verify(runner.inventory).clear()
        assertTrue(service.isGearEscrowed(runner.player.uniqueId), "换装前必须先持久化 escrow")

        service.forceEnd(region.id, "test")

        assertFalse(
            service.isGearEscrowed(runner.player.uniqueId),
            "比赛结束后 escrow 必须已经还给玩家并删除",
        )
    }

    @Test
    fun `a race without gear settings never touches the inventory`() {
        val h = harness()
        val region = raceRegion()
        val runner = ModeServiceHarness.player("Runner")
        h.register(region, runner.player)
        val service = RaceModeService(h.plugin)

        service.join(runner.player, region.id)
        service.ready(runner.player, region.id)

        verify(runner.inventory, never()).clear()
        assertFalse(service.isGearEscrowed(runner.player.uniqueId))
    }

    // ------------------------------------------------------ 结果

    @Test
    fun `a race writes a structured result that survives the match`() {
        val h = harness()
        val region = raceRegion()
        val runner = ModeServiceHarness.player("Runner")
        h.register(region, runner.player)
        val service = RaceModeService(h.plugin)

        service.join(runner.player, region.id)
        service.ready(runner.player, region.id)
        // 超时收尾：无人完赛时不能伪造一个冠军。
        h.delayedAt(60 * 20L).run()

        val result = service.result(region.id)
        assertNotNull(result, "竞速打完必须留下结果，否则 result 页永远是空的")
        assertEquals(MatchOutcome.DRAW, result!!.outcome, "无人完赛应当是平局而不是有胜者")
        assertEquals("game.match.reason.no-finisher", result.reasonKey)
        assertTrue(result.winnerIds.isEmpty())
        assertEquals("run_race", result.modeType)
    }

    @Test
    fun `a force ended race records who ended it`() {
        val h = harness()
        val region = raceRegion()
        val runner = ModeServiceHarness.player("Runner")
        h.register(region, runner.player)
        val service = RaceModeService(h.plugin)

        service.join(runner.player, region.id)
        service.ready(runner.player, region.id)
        assertTrue(service.forceEnd(region.id, "test", forcedBy = "Admin"))

        val result = service.result(region.id)
        assertEquals(MatchOutcome.ABORTED, result?.outcome)
        assertEquals("Admin", result?.forcedBy)
        assertEquals("game.match.reason.forced", result?.reasonKey)
    }

    @Test
    fun `hide and seek records the seekers as winners when every hider is found`() {
        val h = harness()
        val region = roundRegion(mapOf("seekers" to "1", "found-becomes-seeker" to "false"))
        val first = ModeServiceHarness.player("One")
        val second = ModeServiceHarness.player("Two")
        h.register(region, first.player, second.player)
        val service = RoundModeService(h.plugin)

        assertTrue(service.join(first.player, region.id) is JoinResult.Joined)
        assertTrue(service.join(second.player, region.id) is JoinResult.Joined)
        assertTrue(service.ready(first.player, region.id))
        assertTrue(service.ready(second.player, region.id))
        assertEquals(GamePhase.RUNNING, service.status(region.id).phase)

        // 时间到：隐藏者坚持到结束。
        h.delayedAt(30 * 20L).run()

        val result = service.result(region.id)
        assertNotNull(result)
        assertEquals(MatchOutcome.NATURAL, result!!.outcome)
        assertEquals("game.match.reason.hiders-survived", result.reasonKey)
        assertTrue(result.winnerIds.isNotEmpty(), "隐藏者获胜时必须记下是谁赢了")
    }

    // ------------------------------------------------------ 退出与观战

    @Test
    fun `leaving a race roster is possible without walking out of the region`() {
        val h = harness()
        val region = raceRegion(mapOf("min-players" to "2"))
        val first = ModeServiceHarness.player("One")
        val second = ModeServiceHarness.player("Two")
        h.register(region, first.player, second.player)
        val service = RaceModeService(h.plugin)

        service.join(first.player, region.id)
        service.join(second.player, region.id)
        assertEquals(ParticipantState.WAITING, service.participantState(region.id, first.player.uniqueId))

        assertTrue(service.leave(first.player, region.id))
        assertNull(service.participantState(region.id, first.player.uniqueId))
        // 没报名的人退不出来，调用方据此给出"你不在名单里"。
        assertFalse(service.leave(first.player, region.id))
    }

    @Test
    fun `spectating a race needs a live game and excludes participants`() {
        val h = harness()
        val region = raceRegion()
        val runner = ModeServiceHarness.player("Runner")
        val watcher = ModeServiceHarness.player("Watcher")
        h.register(region, runner.player, watcher.player)
        val service = RaceModeService(h.plugin)

        // 一场都还没有：说清楚没有可看的，而不是默默加进空名单。
        val noMatch = service.spectate(watcher.player, region.id)
        assertTrue(noMatch is SpectateResult.Rejected)
        assertEquals("game.match.spectate.no-match", (noMatch as SpectateResult.Rejected).key)

        service.join(runner.player, region.id)
        assertTrue(service.spectate(watcher.player, region.id) is SpectateResult.Joined)

        val asParticipant = service.spectate(runner.player, region.id)
        assertTrue(asParticipant is SpectateResult.Rejected)
        assertEquals("game.match.spectate.is-participant", (asParticipant as SpectateResult.Rejected).key)
    }

    @Test
    fun `spectators and contestants are both visible to the isolation policy`() {
        val h = harness()
        val region = raceRegion()
        val runner = ModeServiceHarness.player("Runner")
        val watcher = ModeServiceHarness.player("Watcher")
        val outsider = ModeServiceHarness.player("Outsider")
        h.register(region, runner.player, watcher.player, outsider.player)
        val service = RaceModeService(h.plugin)

        service.join(runner.player, region.id)
        service.spectate(watcher.player, region.id)

        val contestant = service.membership(runner.player.uniqueId)
        val spectator = service.membership(watcher.player.uniqueId)
        assertNotNull(contestant)
        assertNotNull(spectator)
        assertTrue(contestant!!.participant)
        assertFalse(spectator!!.participant)
        assertTrue(spectator.spectator)
        assertNull(service.membership(outsider.player.uniqueId), "局外人不该有比赛成员记录")
    }

    // ------------------------------------------------------ 报名册规则

    @Test
    fun `a race roster refuses a second signup and respects capacity`() {
        val h = harness()
        val region = raceRegion(mapOf("max-players" to "1"))
        val first = ModeServiceHarness.player("One")
        val second = ModeServiceHarness.player("Two")
        h.register(region, first.player, second.player)
        val service = RaceModeService(h.plugin)

        assertTrue(service.join(first.player, region.id) is JoinResult.Joined)

        val again = service.join(first.player, region.id)
        assertEquals("game.match.join.already-joined", (again as JoinResult.Rejected).key)

        val full = service.join(second.player, region.id)
        assertEquals("game.match.join.full", (full as JoinResult.Rejected).key)
    }

    @Test
    fun `signing up is refused while the previous round still owes you gear`() {
        val h = harness()
        val region = raceRegion(
            mapOf("replace-gear" to "true", "kit" to "IRON_SWORD:1", "respawn" to "world,0,64,0"),
        )
        val runner = ModeServiceHarness.player("Runner")
        h.register(region, runner.player)
        // 上一局留下的待恢复记录。
        CombatGearStore(h.plugin, "race-escrow.yml").put(
            runner.player.uniqueId,
            region.id,
            org.cubexmc.regions.match.GearSnapshot(
                emptyArray(),
                emptyArray(),
                null,
                1,
                0.0f,
                org.bukkit.GameMode.SURVIVAL,
                null,
            ),
        )
        val service = RaceModeService(h.plugin)

        val rejected = service.join(runner.player, region.id)
        assertEquals("game.match.join.restoring", (rejected as JoinResult.Rejected).key)
    }

    @Test
    fun `ready and unready move a contestant between roster states`() {
        val h = harness()
        val region = raceRegion(mapOf("min-players" to "2"))
        val first = ModeServiceHarness.player("One")
        val second = ModeServiceHarness.player("Two")
        h.register(region, first.player, second.player)
        val service = RaceModeService(h.plugin)

        service.join(first.player, region.id)
        service.join(second.player, region.id)
        assertTrue(service.ready(first.player, region.id))
        assertEquals(ParticipantState.READY, service.participantState(region.id, first.player.uniqueId))

        assertTrue(service.unready(first.player, region.id))
        assertEquals(ParticipantState.WAITING, service.participantState(region.id, first.player.uniqueId))
        assertEquals(MatchPhase.WAITING, service.phaseOf(region.id))
    }

    // ------------------------------------------------------ 结果里的完整名单

    /**
     * 中途退赛的人**仍然属于这一局**。把他从名单里删掉会让结果记录里查不到他，
     * "谁参加过这局"就永远对不上了。
     */
    @Test
    fun `someone who leaves mid race still appears in the standings`() {
        val h = harness()
        val region = raceRegion(mapOf("min-players" to "2"))
        val first = ModeServiceHarness.player("One")
        val second = ModeServiceHarness.player("Two")
        h.register(region, first.player, second.player)
        val service = RaceModeService(h.plugin)

        service.join(first.player, region.id)
        service.join(second.player, region.id)
        service.ready(first.player, region.id)
        service.ready(second.player, region.id)
        assertEquals(GamePhase.RUNNING, service.status(region.id).phase)

        service.leave(second.player, region.id)
        assertEquals(
            ParticipantState.LEFT,
            service.participantState(region.id, second.player.uniqueId),
            "退赛的人应当留在名单里并标记为已退出",
        )

        // 场上只剩一个人，还没人完赛：比赛继续，不该因为名单"看起来满了"就收尾。
        assertEquals(GamePhase.RUNNING, service.status(region.id).phase)

        h.delayedAt(60 * 20L).run()

        val result = service.result(region.id)
        assertNotNull(result)
        assertTrue(
            result!!.standings.contains(second.player.uniqueId),
            "退赛者必须出现在名次表里",
        )
        assertTrue(result.standings.contains(first.player.uniqueId))
    }

    /** 最后一个还在跑的人退出后，比赛必须收尾，不能永远挂在 RUNNING。 */
    @Test
    fun `a race ends once nobody is still running`() {
        val h = harness()
        val region = raceRegion()
        val runner = ModeServiceHarness.player("Runner")
        h.register(region, runner.player)
        val service = RaceModeService(h.plugin)

        service.join(runner.player, region.id)
        service.ready(runner.player, region.id)
        assertEquals(GamePhase.RUNNING, service.status(region.id).phase)

        service.leave(runner.player, region.id)

        assertEquals(GamePhase.IDLE, service.status(region.id).phase)
        assertEquals(MatchOutcome.ABORTED, service.result(region.id)?.outcome)
        assertEquals("game.match.reason.roster-changed", service.result(region.id)?.reasonKey)
    }

    @Test
    fun `an unpublished venue cannot be joined`() {
        val h = harness()
        val region = raceRegion().copy(enabled = false)
        val runner = ModeServiceHarness.player("Runner")
        h.register(region, runner.player)
        val service = RaceModeService(h.plugin)

        val rejected = service.join(runner.player, region.id)
        assertEquals("game.match.join.unavailable", (rejected as JoinResult.Rejected).key)
    }
}
