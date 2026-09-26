package org.cubexmc.regions.match

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * 药水／范围效果的成员隔离（PLAN.md §6.3.2）。
 *
 * 伤害类药水走伤害事件，早就被 [CombatDamagePolicy] 拦着；纯效果药水（中毒、缓慢、虚弱）
 * **不触发伤害事件**，在补上这一层之前能直接穿过比赛边界——局外人可以隔着场地给选手上负面状态。
 */
class MatchEffectPolicyTest {

    private val matchId = UUID.randomUUID()
    private val other = UUID.randomUUID()

    @Test
    fun `an outsider cannot poison a participant`() {
        assertEquals(
            DamageDecision.DENY,
            MatchEffectPolicy.decide(null, alive(), MatchEffectPolicy.EffectKind.HARMFUL, selfInflicted = false),
        )
    }

    @Test
    fun `a participant cannot debuff a spectator or an outsider`() {
        assertEquals(
            DamageDecision.DENY,
            MatchEffectPolicy.decide(alive(), spectator(), MatchEffectPolicy.EffectKind.HARMFUL, false),
        )
        assertEquals(
            DamageDecision.DENY,
            MatchEffectPolicy.decide(alive(), null, MatchEffectPolicy.EffectKind.HARMFUL, false),
        )
    }

    @Test
    fun `a different match is still a different match`() {
        assertEquals(
            DamageDecision.DENY,
            MatchEffectPolicy.decide(alive(), alive(match = other), MatchEffectPolicy.EffectKind.HARMFUL, false),
        )
    }

    @Test
    fun `opponents in the same running match may be debuffed`() {
        assertEquals(
            DamageDecision.ALLOW,
            MatchEffectPolicy.decide(alive(team = "red"), alive(team = "blue"), MatchEffectPolicy.EffectKind.HARMFUL, false),
        )
    }

    @Test
    fun `teammates keep friendly-fire protection for harmful effects but may be healed`() {
        val a = alive(team = "red")
        val b = alive(team = "red")
        assertEquals(DamageDecision.DENY, MatchEffectPolicy.decide(a, b, MatchEffectPolicy.EffectKind.HARMFUL, false))
        assertEquals(DamageDecision.ALLOW, MatchEffectPolicy.decide(a, b, MatchEffectPolicy.EffectKind.BENEFICIAL, false))
        assertEquals(DamageDecision.ALLOW, MatchEffectPolicy.decide(a, b, MatchEffectPolicy.EffectKind.NEUTRAL, false))
    }

    @Test
    fun `nothing lands before the match is actually running`() {
        for (phase in listOf(MatchPhase.WAITING, MatchPhase.PREPARING, MatchPhase.COUNTDOWN, MatchPhase.INTERMISSION)) {
            assertEquals(
                DamageDecision.DENY,
                MatchEffectPolicy.decide(alive(phase = phase), alive(phase = phase), MatchEffectPolicy.EffectKind.HARMFUL, false),
                phase.name,
            )
        }
    }

    @Test
    fun `an eliminated player neither gives nor takes effects`() {
        val dead = alive(state = ParticipantState.ELIMINATED)
        assertEquals(DamageDecision.DENY, MatchEffectPolicy.decide(dead, alive(), MatchEffectPolicy.EffectKind.HARMFUL, false))
        assertEquals(DamageDecision.DENY, MatchEffectPolicy.decide(alive(), dead, MatchEffectPolicy.EffectKind.HARMFUL, false))
    }

    @Test
    fun `drinking your own potion always works`() {
        // 自己喝到负面药水是自己的事，连候场阶段都不该拦。
        assertEquals(
            DamageDecision.ALLOW,
            MatchEffectPolicy.decide(alive(phase = MatchPhase.WAITING), alive(phase = MatchPhase.WAITING), MatchEffectPolicy.EffectKind.HARMFUL, selfInflicted = true),
        )
    }

    @Test
    fun `two bystanders are none of our business`() {
        assertEquals(
            DamageDecision.UNRELATED,
            MatchEffectPolicy.decide(null, null, MatchEffectPolicy.EffectKind.HARMFUL, false),
        )
    }

    private fun alive(
        match: UUID = matchId,
        team: String? = null,
        phase: MatchPhase = MatchPhase.RUNNING,
        state: ParticipantState = ParticipantState.ALIVE,
    ) = MatchMembership("arena", match, participant = true, spectator = false, phase = phase, teamId = team, state = state, friendlyFire = false)

    private fun spectator(match: UUID = matchId) =
        MatchMembership("arena", match, participant = false, spectator = true, phase = MatchPhase.RUNNING, teamId = null, state = ParticipantState.WAITING, friendlyFire = false)
}
