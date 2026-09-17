package org.cubexmc.regions.match

import org.cubexmc.regions.model.ModeConfig
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionSourceRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * 交战隔离矩阵（PLAN.md §6.3、§10.1 `CombatDamagePolicyTest`）：
 * 同局敌对、同队、不同局、候场、观战、局外人、非 RUNNING 阶段与无可归因来源。
 */
class CombatDamagePolicyTest {

    private val matchA = UUID.randomUUID()
    private val matchB = UUID.randomUUID()

    @Test
    fun `both outsiders stay unrelated so venue flags keep deciding`() {
        assertEquals(DamageDecision.UNRELATED, CombatDamagePolicy.decide(null, null))
    }

    @Test
    fun `same match running enemies may fight`() {
        val decision = CombatDamagePolicy.decide(
            member(matchA, team = "red", state = ParticipantState.ALIVE),
            member(matchA, team = "blue", state = ParticipantState.ALIVE),
        )

        assertEquals(DamageDecision.ALLOW, decision)
    }

    @Test
    fun `same team never takes friendly fire in a nation battle`() {
        val decision = CombatDamagePolicy.decide(
            member(matchA, team = "red", state = ParticipantState.ALIVE),
            member(matchA, team = "red", state = ParticipantState.ALIVE),
        )

        assertEquals(DamageDecision.DENY, decision)
    }

    @Test
    fun `friendly fire only opens when the rules allow it`() {
        val decision = CombatDamagePolicy.decide(
            member(matchA, team = "red", state = ParticipantState.ALIVE, friendlyFire = true),
            member(matchA, team = "red", state = ParticipantState.ALIVE, friendlyFire = true),
        )

        assertEquals(DamageDecision.ALLOW, decision)
    }

    @Test
    fun `different matches cannot touch each other`() {
        val decision = CombatDamagePolicy.decide(
            member(matchA, team = "red", state = ParticipantState.ALIVE),
            member(matchB, team = "red", state = ParticipantState.ALIVE),
        )

        assertEquals(DamageDecision.DENY, decision)
    }

    @Test
    fun `outsiders cannot hit participants and participants cannot hit outsiders`() {
        assertEquals(
            DamageDecision.DENY,
            CombatDamagePolicy.decide(null, member(matchA, team = "red", state = ParticipantState.ALIVE)),
        )
        assertEquals(
            DamageDecision.DENY,
            CombatDamagePolicy.decide(member(matchA, team = "red", state = ParticipantState.ALIVE), null),
        )
    }

    @Test
    fun `staged phases and waiting players are protected`() {
        for (phase in listOf(
            MatchPhase.WAITING,
            MatchPhase.PREPARING,
            MatchPhase.COUNTDOWN,
            MatchPhase.INTERMISSION,
            MatchPhase.FINISHING,
        )) {
            assertEquals(
                DamageDecision.DENY,
                CombatDamagePolicy.decide(
                    member(matchA, team = "red", state = ParticipantState.ALIVE, phase = phase),
                    member(matchA, team = "blue", state = ParticipantState.ALIVE, phase = phase),
                ),
                "phase $phase must not allow PvP",
            )
        }
    }

    @Test
    fun `spectators and eliminated players are protected in both directions`() {
        assertEquals(
            DamageDecision.DENY,
            CombatDamagePolicy.decide(
                member(matchA, team = "red", state = ParticipantState.ALIVE),
                member(matchA, team = null, state = ParticipantState.ELIMINATED, spectator = true),
            ),
        )
        assertEquals(
            DamageDecision.DENY,
            CombatDamagePolicy.decide(
                member(matchA, team = null, state = ParticipantState.ELIMINATED, spectator = true),
                member(matchA, team = "red", state = ParticipantState.ALIVE),
            ),
        )
        assertEquals(
            DamageDecision.DENY,
            CombatDamagePolicy.decide(
                member(matchA, team = "red", state = ParticipantState.ALIVE),
                member(matchA, team = "blue", state = ParticipantState.ELIMINATED),
            ),
        )
        assertEquals(
            DamageDecision.DENY,
            CombatDamagePolicy.decide(
                member(matchA, team = "red", state = ParticipantState.WAITING),
                member(matchA, team = "blue", state = ParticipantState.ALIVE),
            ),
        )
    }

    private fun member(
        matchId: UUID,
        team: String?,
        state: ParticipantState,
        phase: MatchPhase = MatchPhase.RUNNING,
        spectator: Boolean = false,
        friendlyFire: Boolean = false,
    ) = MatchMembership(
        regionId = "arena",
        matchId = matchId,
        participant = !spectator,
        spectator = spectator,
        phase = phase,
        teamId = team,
        state = state,
        friendlyFire = friendlyFire,
    )
}

/** 三类玩法的胜负规则（PLAN.md §7）：纯判定，可穷举场景。 */
class MatchRulesTest {

    private val duel = region("dual_pvp", mapOf("best-of" to "1", "round-seconds" to "180"))
    private val duelBo3 = region("dual_pvp", mapOf("best-of" to "3", "round-seconds" to "180"))
    private val war = region("union_war", mapOf("team-size" to "2", "timeout-seconds" to "600"))
    private val ffa = region("free_for_all", mapOf("min-players" to "2", "max-players" to "16", "timeout-seconds" to "600"))

    @Test
    fun `duel forces exactly two participants regardless of template values`() {
        val settings = DuelRules.settings(region("dual_pvp", mapOf("min-players" to "1", "max-players" to "8")))

        assertEquals(2, settings.minParticipants)
        assertEquals(2, settings.maxParticipants)
        assertEquals(1, settings.maxRounds)
        assertEquals(1, settings.roundsToWin)
        assertEquals(180, settings.roundSeconds)
    }

    @Test
    fun `duel bo3 allows five rounds and needs two wins`() {
        val settings = DuelRules.settings(duelBo3)

        assertEquals(5, settings.maxRounds)
        assertEquals(2, settings.roundsToWin)
    }

    @Test
    fun `duel round ends when one side is eliminated`() {
        val winner = participant("Winner", "w", ParticipantState.ALIVE)
        val loser = participant("Loser", "l", ParticipantState.ELIMINATED)
        val verdict = DuelRules.evaluate(
            view(MatchPhase.RUNNING, listOf(winner, loser), DuelRules.settings(duel), roundSeconds = 180),
        )

        val roundOver = verdict as MatchVerdict.RoundOver
        assertFalse(roundOver.outcome.draw)
        assertEquals(setOf(winner.playerId), roundOver.outcome.winnerIds)
    }

    @Test
    fun `both duelists dying in the observation window is a round draw`() {
        val verdict = DuelRules.evaluate(
            view(
                MatchPhase.RUNNING,
                listOf(participant("A", "a", ParticipantState.ELIMINATED), participant("B", "b", ParticipantState.ELIMINATED)),
                DuelRules.settings(duel),
                roundSeconds = 180,
            ),
        )

        assertTrue((verdict as MatchVerdict.RoundOver).outcome.draw)
        assertEquals(DuelRules.REASON_MUTUAL, verdict.outcome.reasonKey)
    }

    @Test
    fun `duel round times out into a draw while both are alive`() {
        val alive = listOf(participant("A", "a", ParticipantState.ALIVE), participant("B", "b", ParticipantState.ALIVE))
        val verdict = DuelRules.evaluate(view(MatchPhase.RUNNING, alive, DuelRules.settings(duel), roundSeconds = 181))

        assertTrue((verdict as MatchVerdict.RoundOver).outcome.draw)
        assertEquals(DuelRules.REASON_TIMEOUT, verdict.outcome.reasonKey)
    }

    @Test
    fun `bo1 finishes after a single round while bo3 continues to a second one`() {
        val alive = listOf(participant("A", "a", ParticipantState.ALIVE), participant("B", "b", ParticipantState.ELIMINATED))
        val roundOutcome = RoundOutcome(setOf(alive[0].playerId), "a", reasonKey = DuelRules.REASON_ELIMINATION)

        val bo1 = DuelRules.afterRound(
            view(MatchPhase.INTERMISSION, alive, DuelRules.settings(duel), roundSeconds = 0, roundWins = mapOf(alive[0].playerId to 1)),
            roundOutcome,
        )
        assertTrue(bo1 is MatchVerdict.Finished)

        val bo3 = DuelRules.afterRound(
            view(MatchPhase.INTERMISSION, alive, DuelRules.settings(duelBo3), roundSeconds = 0, roundWins = mapOf(alive[0].playerId to 1)),
            roundOutcome,
        )
        assertEquals(MatchVerdict.Continue, bo3)
    }

    @Test
    fun `bo3 finishes without a champion when five rounds produce no two-time winner`() {
        val alive = listOf(participant("A", "a", ParticipantState.ALIVE), participant("B", "b", ParticipantState.ALIVE))
        val verdict = DuelRules.afterRound(
            view(
                MatchPhase.INTERMISSION,
                alive,
                DuelRules.settings(duelBo3),
                roundSeconds = 0,
                round = 5,
                roundWins = mapOf(alive[0].playerId to 1, alive[1].playerId to 1),
                drawnRounds = 3,
            ),
            RoundOutcome(draw = true, reasonKey = DuelRules.REASON_TIMEOUT),
        )

        val finished = verdict as MatchVerdict.Finished
        // 没有两胜就没有冠军；最后一回合是超时平局，所以整场按超时收尾而不是"回合用尽"。
        assertTrue(finished.resolution.winnerIds.isEmpty())
        assertEquals(MatchOutcome.TIMEOUT, finished.resolution.outcome)
        assertEquals(DuelRules.REASON_TIMEOUT, finished.resolution.reasonKey)
        assertEquals("5", finished.resolution.reasonArgs["rounds"])
    }

    @Test
    fun `bo1 mutual elimination reports the wipe instead of running out of rounds`() {
        val alive = listOf(participant("A", "a", ParticipantState.ALIVE), participant("B", "b", ParticipantState.ALIVE))
        val verdict = DuelRules.afterRound(
            view(MatchPhase.INTERMISSION, alive, DuelRules.settings(duel), roundSeconds = 0, round = 1),
            RoundOutcome(draw = true, reasonKey = DuelRules.REASON_MUTUAL),
        )

        val finished = verdict as MatchVerdict.Finished
        assertEquals(MatchOutcome.DRAW, finished.resolution.outcome)
        assertEquals(DuelRules.REASON_MUTUAL, finished.resolution.reasonKey)
        assertTrue(finished.resolution.winnerIds.isEmpty())
    }

    @Test
    fun `nation battle is two fixed equal teams with friendly fire off`() {
        val settings = NationBattleRules.settings(war)

        assertEquals(TeamUnit.NATION, settings.teamUnit)
        assertEquals(2, settings.teamSize)
        assertEquals(4, settings.minParticipants)
        assertEquals(4, settings.maxParticipants)
        assertFalse(settings.friendlyFire)
        assertEquals(600, settings.roundSeconds)
    }

    @Test
    fun `nation battle ends only when one nation has no living member left`() {
        val aliveRed = participant("Red1", "red", ParticipantState.ALIVE)
        val aliveRedTwo = participant("Red2", "red", ParticipantState.ALIVE)
        val deadBlue = participant("Blue1", "blue", ParticipantState.ELIMINATED)
        val deadBlueTwo = participant("Blue2", "blue", ParticipantState.ELIMINATED)

        val verdict = NationBattleRules.evaluate(
            view(MatchPhase.RUNNING, listOf(aliveRed, aliveRedTwo, deadBlue, deadBlueTwo), NationBattleRules.settings(war), roundSeconds = 10),
        )

        val roundOver = verdict as MatchVerdict.RoundOver
        assertEquals("red", roundOver.outcome.winnerUnitId)
        assertFalse(roundOver.outcome.draw)
    }

    @Test
    fun `nation battle with one survivor still standing is not over`() {
        val verdict = NationBattleRules.evaluate(
            view(
                MatchPhase.RUNNING,
                listOf(
                    participant("Red1", "red", ParticipantState.ALIVE),
                    participant("Red2", "red", ParticipantState.ELIMINATED),
                    participant("Blue1", "blue", ParticipantState.ALIVE),
                    participant("Blue2", "blue", ParticipantState.ELIMINATED),
                ),
                NationBattleRules.settings(war),
                roundSeconds = 10,
            ),
        )

        assertEquals(MatchVerdict.Continue, verdict)
    }

    @Test
    fun `nation battle times out into a draw with survivors recorded`() {
        val verdict = NationBattleRules.evaluate(
            view(
                MatchPhase.RUNNING,
                listOf(participant("Red1", "red", ParticipantState.ALIVE), participant("Blue1", "blue", ParticipantState.ALIVE)),
                NationBattleRules.settings(war),
                roundSeconds = 601,
            ),
        )

        val roundOver = verdict as MatchVerdict.RoundOver
        assertTrue(roundOver.outcome.draw)
        assertEquals(2, roundOver.outcome.survivors.size)
    }

    @Test
    fun `free for all clamps the roster and refuses funding`() {
        val settings = LastPlayerStandingRules.settings(ffa)

        assertEquals(2, settings.minParticipants)
        assertEquals(16, settings.maxParticipants)
        assertFalse(settings.allowFunding)

        val capped = LastPlayerStandingRules.settings(region("free_for_all", mapOf("max-players" to "40")))
        assertEquals(16, capped.maxParticipants)
        val lowest = LastPlayerStandingRules.settings(region("free_for_all", mapOf("min-players" to "2")))
        assertEquals(2, lowest.minParticipants)
    }

    @Test
    fun `free for all ends on the last survivor and on a shared wipe`() {
        val lastAlive = LastPlayerStandingRules.evaluate(
            view(
                MatchPhase.RUNNING,
                listOf(
                    participant("A", "a", ParticipantState.ALIVE),
                    participant("B", "b", ParticipantState.ELIMINATED),
                    participant("C", "c", ParticipantState.ELIMINATED),
                ),
                LastPlayerStandingRules.settings(ffa),
                roundSeconds = 30,
            ),
        )
        assertEquals(1, (lastAlive as MatchVerdict.RoundOver).outcome.winnerIds.size)

        val wiped = LastPlayerStandingRules.evaluate(
            view(
                MatchPhase.RUNNING,
                listOf(
                    participant("A", "a", ParticipantState.ELIMINATED),
                    participant("B", "b", ParticipantState.ELIMINATED),
                ),
                LastPlayerStandingRules.settings(ffa),
                roundSeconds = 30,
            ),
        )
        assertTrue((wiped as MatchVerdict.RoundOver).outcome.draw)
    }

    @Test
    fun `free for all timeout keeps every survivor as a joint result`() {
        val verdict = LastPlayerStandingRules.evaluate(
            view(
                MatchPhase.RUNNING,
                listOf(participant("A", "a", ParticipantState.ALIVE), participant("B", "b", ParticipantState.ALIVE)),
                LastPlayerStandingRules.settings(ffa),
                roundSeconds = 601,
            ),
        )

        val roundOver = verdict as MatchVerdict.RoundOver
        assertTrue(roundOver.outcome.draw)
        assertEquals(2, roundOver.outcome.survivors.size)
        assertTrue(roundOver.outcome.winnerIds.isEmpty())
    }

    @Test
    fun `rules catalog only claims modes the coordinator can run`() {
        assertEquals(
            setOf("dual_pvp", "union_war", "free_for_all"),
            MatchRulesCatalog.COMBAT_MODES,
        )
        assertEquals(null, MatchRulesCatalog.rulesFor("run_race"))
        assertNotNull(MatchRulesCatalog.rulesFor("dual_pvp"))
    }

    private fun view(
        phase: MatchPhase,
        participants: List<MatchParticipant>,
        settings: MatchSettings,
        roundSeconds: Long,
        round: Int = 1,
        roundWins: Map<UUID, Int> = emptyMap(),
        drawnRounds: Int = 0,
    ) = MatchView(
        phase = phase,
        round = round,
        participants = participants,
        settings = settings,
        roundElapsedMillis = roundSeconds * 1000L,
        roundWins = roundWins,
        drawnRounds = drawnRounds,
    )

    private fun participant(name: String, team: String, state: ParticipantState) =
        MatchParticipant(UUID.randomUUID(), name, team, state)

    private fun region(type: String, values: Map<String, String>) = RegionDefinition(
        id = "arena",
        name = "Arena",
        source = RegionSourceRef("cuboid"),
        mode = ModeConfig(type, values),
    )
}

/** 出生点解析与校验（PLAN.md §7.1/§7.3）。 */
class MatchSpawnsTest {

    @Test
    fun `list parsing keeps valid points and reports the broken ones`() {
        val points = MatchSpawns.parseList("world,0,64,0;world,10,64,0;broken;world,20,64,0,90,0")

        assertEquals(3, points.size)
        assertEquals(listOf("broken"), MatchSpawns.invalidEntries("world,0,64,0;broken"))
        assertEquals(90f, points[2].yaw)
    }

    @Test
    fun `duplicate coordinates are not counted as distinct spawns`() {
        val points = MatchSpawns.parseList("world,0,64,0;world,0,64,0;world,5,64,0")

        assertEquals(2, MatchSpawns.distinctCount(points))
    }

    @Test
    fun `spacing violations are reported for points closer than the recommendation`() {
        val points = MatchSpawns.parseList("world,0,64,0;world,2,64,0;world,20,64,0")

        assertEquals(listOf(0 to 1), MatchSpawns.spacingViolations(points))
        assertTrue(MatchSpawns.spacingViolations(points, minimum = 1.0).isEmpty())
    }

    @Test
    fun `points in different worlds never count as close`() {
        val points = MatchSpawns.parseList("alpha,0,64,0;beta,0,64,0")

        assertTrue(MatchSpawns.spacingViolations(points).isEmpty())
    }
}
