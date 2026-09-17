package org.cubexmc.regions.match

import org.cubexmc.regions.model.RegionDefinition
import java.util.UUID

/** 分队单位：决斗/大乱斗每人一队，工会战按 Nation 分队（PLAN.md §7.2）。 */
enum class TeamUnit {
    PLAYER,
    NATION,
}

/**
 * 一场比赛锁定后的设置快照。所有默认值都来自 [RegionDefinition.mode]，开赛后不再重算，
 * 因此发布新 revision 不会改变进行中的比赛（PLAN.md §5.2）。
 */
data class MatchSettings(
    val teamUnit: TeamUnit = TeamUnit.PLAYER,
    /** 每队规定人数；0 表示不组队（决斗/大乱斗）。 */
    val teamSize: Int = 0,
    val minParticipants: Int,
    /** 0 表示不限。 */
    val maxParticipants: Int = 0,
    val preparingSeconds: Int = 10,
    val countdownSeconds: Int = 5,
    /** 回合/整场时限（秒）；0 表示不限时。 */
    val roundSeconds: Int = 0,
    val maxRounds: Int = 1,
    val roundsToWin: Int = 1,
    val intermissionSeconds: Int = 5,
    /** 同队是否可以互相伤害；工会战固定 false，本轮不提供开关。 */
    val friendlyFire: Boolean = false,
    /** `require-ready: false` 时报名即视为准备（保留历史配置语义）。 */
    val requireReady: Boolean = true,
    /**
     * 高级 `diplomacy: enemy-only`：双方 Nation 必须满足**已验证**的敌对关系。
     * 无法验证时拒绝开赛，不把未知当成非敌对（PLAN.md §7.2）。
     */
    val requireVerifiedEnemyRelation: Boolean = false,
    /** `free_for_all` 明确拒绝奖励来源（PLAN.md §7.3）。 */
    val allowFunding: Boolean = true,
)

/** 判定使用的比赛视图；纯数据，便于用单调时钟做顺序化测试。 */
data class MatchView(
    val phase: MatchPhase,
    val round: Int,
    val participants: List<MatchParticipant>,
    val settings: MatchSettings,
    val roundElapsedMillis: Long = 0L,
    /** 决斗的回合胜场，按玩家。 */
    val roundWins: Map<UUID, Int> = emptyMap(),
    /** 工会战的回合胜场，按队伍。 */
    val unitRoundWins: Map<String, Int> = emptyMap(),
    val drawnRounds: Int = 0,
) {
    val alive: List<MatchParticipant> get() = participants.filter { it.state == ParticipantState.ALIVE }
    val eliminated: List<MatchParticipant> get() = participants.filter { it.state == ParticipantState.ELIMINATED || it.state == ParticipantState.LEFT }
    val aliveUnitIds: Set<String> get() = alive.mapNotNullTo(LinkedHashSet()) { it.teamId }
}

/** 一回合的结束判定。 */
data class RoundOutcome(
    val winnerIds: Set<UUID> = emptySet(),
    val winnerUnitId: String? = null,
    val draw: Boolean = false,
    val survivors: Set<UUID> = emptySet(),
    val reasonKey: String,
    val reasonArgs: Map<String, String> = emptyMap(),
)

/** 整场的终态判定。 */
data class MatchResolution(
    val outcome: MatchOutcome,
    val winnerIds: Set<UUID> = emptySet(),
    val winnerTeamId: String? = null,
    val reasonKey: String,
    val reasonArgs: Map<String, String> = emptyMap(),
)

sealed interface MatchVerdict {
    /** 继续当前阶段。 */
    data object Continue : MatchVerdict

    /** 本回合结束（可能还有下一回合）。 */
    data class RoundOver(val outcome: RoundOutcome) : MatchVerdict

    /** 整场结束。 */
    data class Finished(val resolution: MatchResolution) : MatchVerdict
}

/**
 * 一种玩法的胜负规则。规则只做纯判定：给定 [MatchView] 返回继续/回合结束/整场结束，
 * 不碰 Bukkit 对象，因此三类战斗的判定都能用普通单测覆盖（PLAN.md §6.1）。
 */
interface MatchRules {
    val modeType: String

    fun settings(region: RegionDefinition): MatchSettings

    fun evaluate(view: MatchView): MatchVerdict

    /** [MatchVerdict.RoundOver] 已记入胜场后调用：整场结束，还是进入下一回合。 */
    fun afterRound(view: MatchView, outcome: RoundOutcome): MatchVerdict
}

/** 只打一局的玩法（工会战、大乱斗）：回合结束即整场结束。 */
private fun singleRoundResolution(outcome: RoundOutcome): MatchResolution = MatchResolution(
    outcome = if (outcome.draw) {
        if (outcome.reasonKey.endsWith("timeout")) MatchOutcome.TIMEOUT else MatchOutcome.DRAW
    } else {
        MatchOutcome.NATURAL
    },
    winnerIds = outcome.winnerIds,
    winnerTeamId = outcome.winnerUnitId,
    reasonKey = outcome.reasonKey,
    reasonArgs = outcome.reasonArgs,
)

/**
 * 双人决斗 `dual_pvp`（PLAN.md §7.1）：恰好 2 人、每回合默认 180 秒、默认 BO1、
 * 可选 BO3（先赢两回合，最多 5 个实际回合，仍未两胜则整场平局）。
 */
object DuelRules : MatchRules {
    override val modeType: String = "dual_pvp"

    override fun settings(region: RegionDefinition): MatchSettings {
        val values = region.mode?.values ?: emptyMap()
        val bestOf = values["best-of"]?.toIntOrNull()?.let { if (it >= 3) 3 else 1 } ?: 1
        return MatchSettings(
            teamUnit = TeamUnit.PLAYER,
            minParticipants = 2,
            maxParticipants = 2,
            preparingSeconds = values["preparing-seconds"]?.toIntOrNull()?.coerceIn(1, 60) ?: 10,
            countdownSeconds = values["countdown-seconds"]?.toIntOrNull()?.coerceIn(1, 30) ?: 5,
            roundSeconds = values["round-seconds"]?.toIntOrNull()?.coerceAtLeast(0) ?: 180,
            maxRounds = if (bestOf >= 3) MAX_BO3_ROUNDS else 1,
            roundsToWin = if (bestOf >= 3) 2 else 1,
            intermissionSeconds = values["intermission-seconds"]?.toIntOrNull()?.coerceIn(0, 60) ?: 5,
            requireReady = values["require-ready"]?.toBooleanStrictOrNull() ?: true,
        )
    }

    override fun evaluate(view: MatchView): MatchVerdict {
        if (view.phase != MatchPhase.RUNNING) return MatchVerdict.Continue
        val alive = view.alive
        if (alive.isEmpty()) {
            return MatchVerdict.RoundOver(RoundOutcome(draw = true, reasonKey = REASON_MUTUAL))
        }
        val timedOut = view.settings.roundSeconds > 0 && view.roundElapsedMillis >= view.settings.roundSeconds * 1000L
        if (alive.size == 1 && view.eliminated.isNotEmpty()) {
            val winner = alive.single()
            return MatchVerdict.RoundOver(
                RoundOutcome(
                    winnerIds = setOf(winner.playerId),
                    winnerUnitId = winner.teamId,
                    reasonKey = REASON_ELIMINATION,
                ),
            )
        }
        if (timedOut) {
            return MatchVerdict.RoundOver(RoundOutcome(draw = true, reasonKey = REASON_TIMEOUT))
        }
        return MatchVerdict.Continue
    }

    override fun afterRound(view: MatchView, outcome: RoundOutcome): MatchVerdict {
        val champion = view.settings.roundsToWin
            .let { target -> view.participants.filter { (view.roundWins[it.playerId] ?: 0) >= target } }
        if (champion.size == 1) {
            val winner = champion.single()
            return MatchVerdict.Finished(
                MatchResolution(
                    outcome = MatchOutcome.NATURAL,
                    winnerIds = setOf(winner.playerId),
                    winnerTeamId = winner.teamId,
                    reasonKey = REASON_ROUNDS_WON,
                    reasonArgs = mapOf(
                        "wins" to (view.roundWins[winner.playerId] ?: 0).toString(),
                        "rounds" to view.round.toString(),
                    ),
                ),
            )
        }
        if (view.round >= view.settings.maxRounds) {
            // 回合用尽仍无人两胜。最后一回合若是平局（同归于尽 / 回合超时），整场就按那个
            // 原因收尾——不能一律报"回合用尽"，那会把"同归于尽"说成"打满了回合数"。
            if (outcome.draw) {
                return MatchVerdict.Finished(
                    singleRoundResolution(outcome).copy(
                        reasonArgs = outcome.reasonArgs + mapOf("rounds" to view.settings.maxRounds.toString()),
                    ),
                )
            }
            return MatchVerdict.Finished(
                MatchResolution(
                    outcome = if (view.drawnRounds > 0) MatchOutcome.DRAW else MatchOutcome.TIMEOUT,
                    reasonKey = REASON_ROUNDS_EXHAUSTED,
                    reasonArgs = mapOf("rounds" to view.settings.maxRounds.toString()),
                ),
            )
        }
        return MatchVerdict.Continue
    }

    private const val MAX_BO3_ROUNDS = 5
    const val REASON_MUTUAL = "game.match.reason.mutual"
    const val REASON_TIMEOUT = "game.match.reason.round-timeout"
    const val REASON_ELIMINATION = "game.match.reason.opponent-down"
    const val REASON_ROUNDS_WON = "game.match.reason.rounds-won"
    const val REASON_ROUNDS_EXHAUSTED = "game.match.reason.rounds-exhausted"
}

/**
 * 工会战 `union_war`（PLAN.md §7.2）：两个 Nation、固定等额阵容、每人一条命、同队永不友伤。
 * 一队全员淘汰/弃权且另一队仍有存活者则另一 Nation 获胜；双方同归于尽为平局；超时平局。
 */
object NationBattleRules : MatchRules {
    override val modeType: String = "union_war"

    override fun settings(region: RegionDefinition): MatchSettings {
        val values = region.mode?.values ?: emptyMap()
        val teamSize = values["team-size"]?.toIntOrNull()
            ?: values["min-players"]?.toIntOrNull()?.div(2)?.takeIf { it > 0 }
            ?: DEFAULT_TEAM_SIZE
        val normalized = teamSize.coerceIn(MIN_TEAM_SIZE, MAX_TEAM_SIZE)
        return MatchSettings(
            teamUnit = TeamUnit.NATION,
            teamSize = normalized,
            minParticipants = normalized * 2,
            maxParticipants = normalized * 2,
            preparingSeconds = values["preparing-seconds"]?.toIntOrNull()?.coerceIn(1, 60) ?: 10,
            countdownSeconds = values["countdown-seconds"]?.toIntOrNull()?.coerceIn(1, 30) ?: 5,
            roundSeconds = values["timeout-seconds"]?.toIntOrNull()?.coerceAtLeast(0) ?: DEFAULT_TIMEOUT_SECONDS,
            maxRounds = 1,
            roundsToWin = 1,
            intermissionSeconds = 0,
            friendlyFire = false,
            requireReady = values["require-ready"]?.toBooleanStrictOrNull() ?: true,
            requireVerifiedEnemyRelation = values["diplomacy"].equals("enemy-only", ignoreCase = true),
        )
    }

    override fun evaluate(view: MatchView): MatchVerdict {
        if (view.phase != MatchPhase.RUNNING) return MatchVerdict.Continue
        if (view.alive.isEmpty()) {
            return MatchVerdict.RoundOver(RoundOutcome(draw = true, reasonKey = REASON_MUTUAL))
        }
        val units = view.aliveUnitIds
        val timedOut = view.settings.roundSeconds > 0 && view.roundElapsedMillis >= view.settings.roundSeconds * 1000L
        if (units.size == 1 && view.eliminated.isNotEmpty()) {
            return MatchVerdict.RoundOver(
                RoundOutcome(
                    winnerIds = view.alive.mapTo(LinkedHashSet()) { it.playerId },
                    winnerUnitId = units.single(),
                    survivors = view.alive.mapTo(LinkedHashSet()) { it.playerId },
                    reasonKey = REASON_UNIT_WIPED,
                ),
            )
        }
        if (timedOut) {
            return MatchVerdict.RoundOver(
                RoundOutcome(
                    draw = true,
                    survivors = view.alive.mapTo(LinkedHashSet()) { it.playerId },
                    reasonKey = REASON_TIMEOUT,
                ),
            )
        }
        return MatchVerdict.Continue
    }

    override fun afterRound(view: MatchView, outcome: RoundOutcome): MatchVerdict =
        MatchVerdict.Finished(singleRoundResolution(outcome))

    const val REASON_MUTUAL = "game.match.reason.mutual"
    const val REASON_TIMEOUT = "game.match.reason.timeout"
    const val REASON_UNIT_WIPED = "game.match.reason.nation-wiped"
    const val MIN_TEAM_SIZE = 2
    const val MAX_TEAM_SIZE = 10
    const val DEFAULT_TEAM_SIZE = 5
    const val DEFAULT_TIMEOUT_SECONDS = 600
}

/**
 * 单命大乱斗 `free_for_all`（PLAN.md §7.3）：每人一条命，最后存活者获胜；
 * 同一结算批次淘汰视为并列；超时仍多人生存为并列，不伪造唯一冠军。
 */
object LastPlayerStandingRules : MatchRules {
    override val modeType: String = "free_for_all"

    override fun settings(region: RegionDefinition): MatchSettings {
        val values = region.mode?.values ?: emptyMap()
        val min = (values["min-players"]?.toIntOrNull() ?: DEFAULT_MIN).coerceIn(ABSOLUTE_MIN, DEFAULT_MAX)
        val max = (values["max-players"]?.toIntOrNull() ?: DEFAULT_MAX).coerceIn(min, DEFAULT_MAX)
        return MatchSettings(
            teamUnit = TeamUnit.PLAYER,
            minParticipants = min,
            maxParticipants = max,
            preparingSeconds = values["preparing-seconds"]?.toIntOrNull()?.coerceIn(1, 60) ?: 10,
            countdownSeconds = values["countdown-seconds"]?.toIntOrNull()?.coerceIn(1, 30) ?: 5,
            roundSeconds = values["timeout-seconds"]?.toIntOrNull()?.coerceAtLeast(0) ?: DEFAULT_TIMEOUT_SECONDS,
            maxRounds = 1,
            roundsToWin = 1,
            intermissionSeconds = 0,
            friendlyFire = false,
            requireReady = values["require-ready"]?.toBooleanStrictOrNull() ?: true,
            allowFunding = false,
        )
    }

    override fun evaluate(view: MatchView): MatchVerdict {
        if (view.phase != MatchPhase.RUNNING) return MatchVerdict.Continue
        val alive = view.alive
        if (alive.isEmpty()) {
            return MatchVerdict.RoundOver(RoundOutcome(draw = true, reasonKey = REASON_MUTUAL))
        }
        val timedOut = view.settings.roundSeconds > 0 && view.roundElapsedMillis >= view.settings.roundSeconds * 1000L
        if (alive.size == 1 && view.eliminated.isNotEmpty()) {
            val winner = alive.single()
            return MatchVerdict.RoundOver(
                RoundOutcome(
                    winnerIds = setOf(winner.playerId),
                    winnerUnitId = winner.teamId,
                    survivors = setOf(winner.playerId),
                    reasonKey = REASON_LAST_STANDING,
                ),
            )
        }
        if (timedOut) {
            return MatchVerdict.RoundOver(
                RoundOutcome(
                    draw = true,
                    survivors = alive.mapTo(LinkedHashSet()) { it.playerId },
                    reasonKey = REASON_TIMEOUT,
                ),
            )
        }
        return MatchVerdict.Continue
    }

    override fun afterRound(view: MatchView, outcome: RoundOutcome): MatchVerdict =
        MatchVerdict.Finished(singleRoundResolution(outcome))

    const val REASON_MUTUAL = "game.match.reason.mutual"
    const val REASON_TIMEOUT = "game.match.reason.timeout"
    const val REASON_LAST_STANDING = "game.match.reason.last-standing"
    const val ABSOLUTE_MIN = 2
    const val DEFAULT_MIN = 4
    const val DEFAULT_MAX = 16
    const val DEFAULT_TIMEOUT_SECONDS = 600
}

/** 玩法类型 → 规则；未实现规则的玩法返回 null（不会进入战斗状态机）。 */
object MatchRulesCatalog {
    private val RULES: Map<String, MatchRules> = listOf(
        DuelRules,
        NationBattleRules,
        LastPlayerStandingRules,
    ).associateBy { it.modeType }

    /** 本轮由 [org.cubexmc.regions.match.CombatMatchCoordinator] 接管的玩法。 */
    val COMBAT_MODES: Set<String> = RULES.keys

    fun rulesFor(modeType: String?): MatchRules? = modeType?.lowercase()?.let { RULES[it] }
}
