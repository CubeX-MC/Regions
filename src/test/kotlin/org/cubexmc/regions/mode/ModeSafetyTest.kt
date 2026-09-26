package org.cubexmc.regions.mode

import org.bukkit.event.entity.PlayerDeathEvent
import org.cubexmc.regions.match.MatchOutcome
import org.cubexmc.regions.match.MatchPhase
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.*
import java.nio.file.Path

class ModeSafetyTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `interrupted race is recorded as aborted after restart`() {
        val h = ModeServiceHarness(directory, "race-recovery")
        val region = ModeServiceHarness.region("track", "run_race", mapOf(
            "min-players" to "1", "require-start" to "false", "vehicle" to "pass",
        ))
        val runner = ModeServiceHarness.player("Runner")
        h.register(region, runner.player)
        val race = RaceModeService(h.plugin)
        race.join(runner.player, region.id)
        race.ready(runner.player, region.id)
        assertEquals(MatchPhase.RUNNING, race.phaseOf(region.id))
        val persisted = h.matchStore.activeForRegion(region.id) ?: fail("Race has no durable active record")

        h.matchStore.reload()
        assertEquals(1, RaceModeService(h.plugin).recoverPersisted("test-restart"))
        assertEquals(null, h.matchStore.activeForRegion(region.id))
        val result = h.matchStore.lastResult(region.id) ?: fail("Race has no recovery result")
        assertEquals(persisted.matchId, result.matchId)
        assertEquals(MatchOutcome.ABORTED, result.outcome)
        assertEquals("game.match.reason.server-stop", result.reasonKey)
        h.matchStore.reload()
        assertEquals(result, h.matchStore.lastResult(region.id))
    }

    @Test
    fun `interrupted hide and seek is recorded as aborted after restart`() {
        val h = ModeServiceHarness(directory, "round-recovery")
        val region = ModeServiceHarness.region("hide", "hide_and_seek", mapOf("min-players" to "2"))
        val one = ModeServiceHarness.player("One")
        val two = ModeServiceHarness.player("Two")
        h.register(region, one.player, two.player)
        val round = RoundModeService(h.plugin)
        round.join(one.player, region.id)
        round.join(two.player, region.id)
        round.ready(one.player, region.id)
        round.ready(two.player, region.id)
        assertEquals(MatchPhase.RUNNING, round.phaseOf(region.id))
        val persisted = h.matchStore.activeForRegion(region.id) ?: fail("Round has no durable active record")

        h.matchStore.reload()
        assertEquals(1, RoundModeService(h.plugin).recoverPersisted("test-restart"))
        assertEquals(null, h.matchStore.activeForRegion(region.id))
        val result = h.matchStore.lastResult(region.id) ?: fail("Round has no recovery result")
        assertEquals(persisted.matchId, result.matchId)
        assertEquals(MatchOutcome.ABORTED, result.outcome)
        assertEquals("game.match.reason.server-stop", result.reasonKey)
        h.matchStore.reload()
        assertEquals(result, h.matchStore.lastResult(region.id))
    }

    @Test
    fun `old race cleanup cannot restore gear escrowed by the next race`() {
        val h = ModeServiceHarness(directory, "stale-cleanup", false)
        val first = ModeServiceHarness.region("first", "run_race", mapOf(
            "min-players" to "1", "require-start" to "false", "vehicle" to "pass",
        ))
        val second = ModeServiceHarness.region("second", "run_race", mapOf(
            "min-players" to "1", "require-start" to "false", "vehicle" to "pass", "replace-gear" to "true",
        ))
        val runner = ModeServiceHarness.player("Runner")
        h.register(first, runner.player)
        h.register(second, runner.player)
        val service = RaceModeService(h.plugin)
        var nextTask = 0
        fun drainUntilRunning(regionId: String) {
            while (service.phaseOf(regionId) != MatchPhase.RUNNING) {
                assertTrue(nextTask < h.entityTasks.size, "preparation must schedule an entity task")
                h.entityTasks[nextTask++].run()
            }
        }

        service.join(runner.player, first.id)
        service.ready(runner.player, first.id)
        drainUntilRunning(first.id)
        service.forceEnd(first.id, "test")
        val oldCleanup = h.entityTasks.last()
        h.entityTasks.removeAt(h.entityTasks.lastIndex)

        assertTrue(service.join(runner.player, second.id) is org.cubexmc.regions.match.JoinResult.Joined)
        service.ready(runner.player, second.id)
        drainUntilRunning(second.id)
        assertTrue(service.isGearEscrowed(runner.player.uniqueId))

        oldCleanup.run()

        assertTrue(service.isGearEscrowed(runner.player.uniqueId), "old cleanup removed the next match's escrow")
    }

    @Test
    fun `old hide and seek cleanup cannot restore gear escrowed by the next round`() {
        val h = ModeServiceHarness(directory, "stale-round-cleanup", false)
        val first = ModeServiceHarness.region("first", "hide_and_seek", mapOf("min-players" to "2"))
        val second = ModeServiceHarness.region("second", "hide_and_seek", mapOf(
            "min-players" to "2", "replace-gear" to "true",
        ))
        val one = ModeServiceHarness.player("One")
        val two = ModeServiceHarness.player("Two")
        h.register(first, one.player, two.player)
        h.register(second, one.player, two.player)
        val service = RoundModeService(h.plugin)
        var nextTask = 0
        fun start(regionId: String) {
            service.join(one.player, regionId)
            service.join(two.player, regionId)
            service.ready(one.player, regionId)
            service.ready(two.player, regionId)
            while (service.phaseOf(regionId) != MatchPhase.RUNNING) {
                assertTrue(nextTask < h.entityTasks.size)
                h.entityTasks[nextTask++].run()
            }
        }

        start(first.id)
        service.forceEnd(first.id, "test")
        val oldCleanup = h.entityTasks.takeLast(2)
        repeat(2) { h.entityTasks.removeAt(h.entityTasks.lastIndex) }

        start(second.id)
        assertTrue(service.isGearEscrowed(one.player.uniqueId))
        oldCleanup.forEach { it.run() }
        assertTrue(service.isGearEscrowed(one.player.uniqueId), "old round removed the next round's escrow")
    }

    @Test
    fun `a player cannot join races and hide and seek at the same time`() {
        val h = ModeServiceHarness(directory, "admission")
        val race = ModeServiceHarness.region("track", "run_race", emptyMap())
        val round = ModeServiceHarness.region("hide", "hide_and_seek", emptyMap())
        val runner = ModeServiceHarness.player("Runner")
        h.register(race, runner.player)
        h.register(round, runner.player)
        val races = RaceModeService(h.plugin)
        val rounds = RoundModeService(h.plugin)
        assertTrue(races.join(runner.player, race.id) is org.cubexmc.regions.match.JoinResult.Joined)
        val rejected = rounds.join(runner.player, round.id) as org.cubexmc.regions.match.JoinResult.Rejected
        assertEquals("game.match.join.other-match", rejected.key)
        races.leave(runner.player, race.id)
        assertTrue(rounds.join(runner.player, round.id) is org.cubexmc.regions.match.JoinResult.Joined)
    }

    @Test
    fun `a player whose data cannot be saved keeps the recovery lease`() {
        val h = ModeServiceHarness(directory, "save-failure")
        val runner = ModeServiceHarness.player("Runner")
        val escrow = ModeGearEscrow(h.plugin, "failure.yml", "test")
        escrow.capture(runner.player, "track", null)
        doThrow(IllegalStateException("disk unavailable")).`when`(runner.player).saveData()
        assertThrows(IllegalStateException::class.java) { escrow.restore(runner.player, "test") }
        assertTrue(escrow.isEscrowed(runner.player.uniqueId))
        doNothing().`when`(runner.player).saveData()
        assertTrue(escrow.restore(runner.player, "retry"))
        assertFalse(escrow.isEscrowed(runner.player.uniqueId))
    }

    @Test
    fun `eliminated runner cannot finish and a new published finish does not alter this race`() {
        val h = ModeServiceHarness(directory, "race-progress")
        val world = mock(org.bukkit.World::class.java)
        `when`(world.uid).thenReturn(java.util.UUID.randomUUID())
        `when`(h.server.getWorld("world")).thenReturn(world)
        val region = ModeServiceHarness.region("track", "run_race", mapOf(
            "min-players" to "2", "require-start" to "false", "vehicle" to "pass",
            "finish" to "world,20,64,0",
        ))
        val one = ModeServiceHarness.player("One")
        val two = ModeServiceHarness.player("Two")
        h.register(region, one.player, two.player)
        for (player in listOf(one.player, two.player)) {
            val playerId = player.uniqueId
            `when`(player.location).thenReturn(org.bukkit.Location(world, 20.0, 64.0, 0.0))
            `when`(h.sessions.activeSessions(playerId)).thenReturn(listOf(
                org.cubexmc.regions.model.RegionSession(java.util.UUID.randomUUID(), playerId, region.id, 0L),
            ))
        }
        val service = RaceModeService(h.plugin)
        service.join(one.player, region.id)
        service.join(two.player, region.id)
        service.ready(one.player, region.id)
        service.ready(two.player, region.id)
        service.leave(two.player, region.id)
        service.onMove(two.player)
        assertEquals(0, service.status(region.id).extra["finished"])
        h.register(region.copy(mode = region.mode!!.copy(values = region.mode.values + ("finish" to "world,100,64,0"))))
        service.onMove(one.player)
        assertEquals(setOf(one.player.uniqueId), service.result(region.id)?.winnerIds)
    }

    @Test
    fun `last runner death keeps gear until respawn even after match closes`() {
        val h = ModeServiceHarness(directory, "death")
        val region = ModeServiceHarness.region("track", "run_race", mapOf(
            "min-players" to "1", "require-start" to "false", "vehicle" to "pass",
            "replace-gear" to "true",
        ))
        val runner = ModeServiceHarness.player("Runner")
        h.register(region, runner.player)
        val service = RaceModeService(h.plugin)
        service.join(runner.player, region.id)
        service.ready(runner.player, region.id)
        val death = mock(PlayerDeathEvent::class.java)
        `when`(death.entity).thenReturn(runner.player)
        `when`(death.drops).thenReturn(mutableListOf())
        `when`(runner.player.isDead).thenReturn(true)

        assertTrue(service.onDeath(death))
        assertEquals(MatchOutcome.ABORTED, service.result(region.id)?.outcome)
        assertTrue(service.isGearEscrowed(runner.player.uniqueId))
        assertFalse(service.restoreIfPending(runner.player, "reload-while-dead"))

        `when`(runner.player.isDead).thenReturn(false)
        service.onRespawn(runner.player)
        assertFalse(service.isGearEscrowed(runner.player.uniqueId))
        verify(runner.player).saveData()
    }

    @Test
    fun `hide and seek waits for every entity preparation and expires missing callbacks`() {
        val h = ModeServiceHarness(directory, "preparing", false)
        val region = ModeServiceHarness.region("hide", "hide_and_seek", mapOf("min-players" to "2"))
        val one = ModeServiceHarness.player("One")
        val two = ModeServiceHarness.player("Two")
        h.register(region, one.player, two.player)
        val service = RoundModeService(h.plugin)
        service.join(one.player, region.id)
        service.join(two.player, region.id)
        service.ready(one.player, region.id)
        service.ready(two.player, region.id)
        assertEquals(MatchPhase.PREPARING, service.phaseOf(region.id))

        h.delayedAt(200).run()
        assertEquals(MatchOutcome.ABORTED, service.result(region.id)?.outcome)
        h.entityTasks.toList().forEach { it.run() }
        verify(one.inventory, never()).clear()
        verify(two.inventory, never()).clear()
    }

    @Test
    fun `race preparation with missing entity callback times out`() {
        val h = ModeServiceHarness(directory, "preparing", false)
        val region = ModeServiceHarness.region("track", "run_race", mapOf(
            "min-players" to "1", "require-start" to "false", "vehicle" to "pass",
        ))
        val runner = ModeServiceHarness.player("Runner")
        h.register(region, runner.player)
        val service = RaceModeService(h.plugin)
        service.join(runner.player, region.id)
        service.ready(runner.player, region.id)
        assertEquals(MatchPhase.PREPARING, service.phaseOf(region.id))
        h.delayedAt(200).run()
        assertEquals(MatchOutcome.ABORTED, service.result(region.id)?.outcome)
        h.entityTasks.toList().forEach { it.run() }
        assertEquals(GamePhase.IDLE, service.status(region.id).phase)
    }
}
