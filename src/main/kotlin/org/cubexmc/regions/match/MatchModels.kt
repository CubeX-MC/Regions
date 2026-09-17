package org.cubexmc.regions.match

import java.util.UUID

/**
 * 一场比赛的阶段（PLAN.md §6.2 状态机）。
 *
 * 同一时刻一块场地只有一个 [MatchPhase]；[WAITING] 之前的报名状态属于 roster，
 * 不属于阶段。显示名走 `labels.state.*`，代码里不出现英文串。
 */
enum class MatchPhase {
    /** 收人中：报名的玩家进 roster，等待满员与全部准备。 */
    WAITING,

    /** 资金预留、装备托管、传送与发装备的屏障阶段；任何人失败都撤销本次开赛。 */
    PREPARING,

    /** 全部回执成功后的开赛倒计时；此阶段禁止伤害。 */
    COUNTDOWN,

    /** 正式战斗；唯一允许同局 PVP 的阶段。 */
    RUNNING,

    /** 决斗回合之间的休整；禁止伤害，重置装备与点位。 */
    INTERMISSION,

    /** 结算与恢复：资金终态 + 逐人恢复装备，未完成前阻止同场新局。 */
    FINISHING,

    /** 已完全收尾，记录可从运行态移除。 */
    CLOSED,
}

/** 一名选手在一场比赛里的状态；原始 roster 独立于存活集合（PLAN.md §6.1）。 */
enum class ParticipantState {
    /** 已报名但未准备。 */
    WAITING,

    /** 已准备，等待开赛。 */
    READY,

    /** 本回合/本局存活。 */
    ALIVE,

    /** 已淘汰：死亡、弃权或断线。 */
    ELIMINATED,

    /** 主动退赛（报名阶段退出或整场弃权）。 */
    LEFT,

    /** 正在恢复入场前状态。 */
    RESTORING,

    /** 已恢复完成。 */
    RESTORED,
    ;

    val isContestant: Boolean
        get() = this == ALIVE || this == WAITING || this == READY || this == RESTORING
}

/** 比赛终态的种类；显示名走 `labels.result.*`。 */
enum class MatchOutcome {
    /** 正常分出胜负。 */
    NATURAL,

    /** 按规则平局（同归于尽、超时仍多方存活、回合耗尽）。 */
    DRAW,

    /** 超时结束且规则规定超时为平局。 */
    TIMEOUT,

    /** 一方弃权/断线导致的结束（仍可能产生胜者）。 */
    ABANDONED,

    /** 中止：裁判强停、依赖失败、准备屏障失败等，不按单方弃权发奖。 */
    ABORTED,
}

/** 资金处理状态；只有终态才允许写终态记录（PLAN.md §6.2）。 */
enum class RewardState {
    /** 本场没有配置奖励。 */
    NONE,
    PENDING,
    SETTLED,
    REFUNDED,
    /** provider 未确认，保留 lease 等待复核。 */
    REVIEW_REQUIRED,
}

/** 一支队伍/一个阵营的不可变快照。决斗与大乱斗每人一队（[id] == 玩家 UUID 字符串）。 */
data class TeamSnapshot(
    val id: String,
    val name: String,
    /** 工会战里该队对应的 Nation 稳定 ID；非 Nation 队伍为 null。 */
    val nationId: String? = null,
)

/** 一名选手的不可变快照。 */
data class MatchParticipant(
    val playerId: UUID,
    val name: String,
    /** [TeamSnapshot.id]，无队伍时为 null。 */
    val teamId: String?,
    val state: ParticipantState,
    /** 淘汰顺序，1 为第一个出局；未淘汰为 null。 */
    val eliminatedOrder: Int? = null,
    /** 淘汰原因码（`labels.reason.*` / `game.match.*`），用于结果页。 */
    val eliminationReason: String? = null,
)

/** 一场比赛的唯一终态记录；一场比赛只会产生一个 [resultId]。 */
data class MatchResult(
    val resultId: UUID,
    val matchId: UUID,
    val regionId: String,
    val modeType: String,
    val outcome: MatchOutcome,
    val winnerIds: Set<UUID>,
    val winnerTeamId: String?,
    /** 结束原因，渲染走 `game.match.result.*`。 */
    val reasonKey: String,
    val reasonArgs: Map<String, String> = emptyMap(),
    /** 强停/强制的操作者名；自然结束为 null。 */
    val forcedBy: String? = null,
    val rewardState: RewardState = RewardState.NONE,
    val finishedAtMillis: Long,
    /** 结算批次内的淘汰顺序（先出局的在前）；仍存活者不在其中。 */
    val eliminationOrder: List<UUID> = emptyList(),
)

/**
 * 一场比赛的完整不可变快照，也是 [MatchStore] 的落盘单元。
 *
 * 只保存恢复元数据，不复制物品或余额：装备仍在 `CombatGearStore`、资金 lease 仍在
 * `RewardFundingStore`（PLAN.md §6.1）。
 */
data class MatchSnapshot(
    val matchId: UUID,
    val regionId: String,
    val publishedRevision: Long,
    val modeType: String,
    val createdAtMillis: Long,
    /** 本场开赛时锁定、之后不再重算的比赛选项（例如工会战的双方 Nation）。 */
    val options: Map<String, String>,
    val participants: List<MatchParticipant>,
    val teams: Map<String, TeamSnapshot>,
    val phase: MatchPhase,
    val round: Int,
    val result: MatchResult?,
    /** 仍待恢复装备的玩家。 */
    val pendingRestore: Set<UUID>,
    /** 已经确认写回背包的恢复 operation（重启时据此只补清理、不覆盖新背包）。 */
    val confirmedRestore: Set<UUID>,
) {
    fun participant(playerId: UUID): MatchParticipant? = participants.firstOrNull { it.playerId == playerId }

    fun isTerminal(): Boolean = phase == MatchPhase.FINISHING && pendingRestore.isEmpty()
}
