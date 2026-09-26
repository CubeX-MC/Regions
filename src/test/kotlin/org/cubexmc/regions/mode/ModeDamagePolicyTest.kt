package org.cubexmc.regions.mode

import org.cubexmc.regions.match.DamageDecision
import org.cubexmc.regions.match.MatchEffectPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * 竞速与捉迷藏的成员隔离。
 *
 * 此前这两类玩法完全没有隔离：一个带弓的路人能决定整场竞速的胜负，
 * 躲藏者能反过来把搜寻者打死（搜寻者→躲藏者那一下会被当成"抓到"取消，
 * 反方向却是实打实的伤害）。这些用例把两个方向都钉住。
 */
class ModeDamagePolicyTest {

    private val matchA = UUID.randomUUID()
    private val matchB = UUID.randomUUID()

    private fun participant(matchId: UUID = matchA, regionId: String = "venue") =
        ModeDamagePolicy.Membership(regionId, matchId, participant = true, spectator = false)

    private fun spectator(matchId: UUID = matchA, regionId: String = "venue") =
        ModeDamagePolicy.Membership(regionId, matchId, participant = false, spectator = true)

    private fun bystander(matchId: UUID = matchA, regionId: String = "venue") =
        ModeDamagePolicy.Membership(regionId, matchId, participant = false, spectator = false)

    @Test
    fun `damage between two people with no race or round is left to the venue rules`() {
        assertEquals(DamageDecision.UNRELATED, ModeDamagePolicy.decide(null, null))
    }

    @Test
    fun `an outsider cannot interfere with a contestant`() {
        assertEquals(DamageDecision.DENY, ModeDamagePolicy.decide(null, participant()))
    }

    @Test
    fun `a contestant cannot hit someone outside the game`() {
        assertEquals(DamageDecision.DENY, ModeDamagePolicy.decide(participant(), null))
    }

    /**
     * 竞速与捉迷藏都不靠打架决胜负，所以同场选手之间也不放行——
     * 留一条 PVP 通道只会让一个带弓的人决定整场比赛。
     */
    @Test
    fun `two contestants in the same game still cannot damage each other`() {
        assertEquals(DamageDecision.DENY, ModeDamagePolicy.decide(participant(), participant()))
    }

    @Test
    fun `spectators neither deal nor take game damage`() {
        assertEquals(DamageDecision.DENY, ModeDamagePolicy.decide(spectator(), participant()))
        assertEquals(DamageDecision.DENY, ModeDamagePolicy.decide(participant(), spectator()))
    }

    @Test
    fun `people standing in the venue without signing up are isolated too`() {
        assertEquals(DamageDecision.DENY, ModeDamagePolicy.decide(bystander(), participant()))
        assertEquals(DamageDecision.DENY, ModeDamagePolicy.decide(participant(), bystander()))
    }

    @Test
    fun `contestants in different games cannot reach each other`() {
        assertEquals(
            DamageDecision.DENY,
            ModeDamagePolicy.decide(participant(matchA), participant(matchB, "other")),
        )
    }

    // ------------------------------------------------------------ 药水

    @Test
    fun `self inflicted potions always apply`() {
        assertEquals(
            DamageDecision.ALLOW,
            ModeDamagePolicy.decideEffect(
                participant(),
                participant(),
                MatchEffectPolicy.EffectKind.HARMFUL,
                selfInflicted = true,
            ),
        )
    }

    @Test
    fun `harmful potions are blocked exactly like damage`() {
        assertEquals(
            DamageDecision.DENY,
            ModeDamagePolicy.decideEffect(
                participant(),
                participant(),
                MatchEffectPolicy.EffectKind.HARMFUL,
                selfInflicted = false,
            ),
        )
        assertEquals(
            DamageDecision.DENY,
            ModeDamagePolicy.decideEffect(
                null,
                participant(),
                MatchEffectPolicy.EffectKind.HARMFUL,
                selfInflicted = false,
            ),
        )
    }

    /** 给一起跑的人递瓶速度不是漏洞。 */
    @Test
    fun `helpful potions still work between contestants of the same game`() {
        for (kind in listOf(MatchEffectPolicy.EffectKind.BENEFICIAL, MatchEffectPolicy.EffectKind.NEUTRAL)) {
            assertEquals(
                DamageDecision.ALLOW,
                ModeDamagePolicy.decideEffect(participant(), participant(), kind, selfInflicted = false),
                "$kind 在同一场比赛内部应当放行",
            )
        }
    }

    @Test
    fun `helpful potions from outside the game are still blocked`() {
        assertEquals(
            DamageDecision.DENY,
            ModeDamagePolicy.decideEffect(
                null,
                participant(),
                MatchEffectPolicy.EffectKind.BENEFICIAL,
                selfInflicted = false,
            ),
        )
        assertEquals(
            DamageDecision.DENY,
            ModeDamagePolicy.decideEffect(
                spectator(),
                participant(),
                MatchEffectPolicy.EffectKind.BENEFICIAL,
                selfInflicted = false,
            ),
        )
    }

    @Test
    fun `potions between unrelated people are left alone`() {
        assertEquals(
            DamageDecision.UNRELATED,
            ModeDamagePolicy.decideEffect(
                null,
                null,
                MatchEffectPolicy.EffectKind.HARMFUL,
                selfInflicted = false,
            ),
        )
    }
}
