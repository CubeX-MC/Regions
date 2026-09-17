package org.cubexmc.regions.match

import java.util.UUID

/**
 * 一名玩家与某场比赛的关系（PLAN.md §6.3）。
 *
 * 非参赛者只要在比赛场地内也会有一条记录：观战者（[spectator]）或局外人（[spectator] == false
 * 且 [participant] == false）。两者对参赛者的伤害、以及参赛者对两者的反向伤害都拒绝。
 */
data class MatchMembership(
    val regionId: String,
    val matchId: UUID,
    val participant: Boolean,
    val spectator: Boolean,
    val phase: MatchPhase,
    val teamId: String?,
    val state: ParticipantState,
    val friendlyFire: Boolean,
)

enum class DamageDecision {
    /** 允许本次 PVP（同一场、RUNNING、双方存活、敌对）。 */
    ALLOW,

    /** 明确拒绝：比赛成员隔离或阶段保护。 */
    DENY,

    /** 与任何比赛无关，交给原有 Flag / 其他插件处理。 */
    UNRELATED,
}

/**
 * 交战判定：只看数据，不碰事件对象，因此同局敌对、同队、不同局、候场、观战、局外人
 * 这些组合都能用普通单测穷举（PLAN.md §10.1 的 `CombatDamagePolicyTest`）。
 *
 * 约定：[decide] 只在"伤害可归因于玩家"时调用——近战、箭矢/三叉戟、药水与滞留云的投掷者、
 * 有主人的宠物、玩家点燃的 TNT。生物与环境伤害不经过这里（环境死亡同样计入淘汰）。
 * `attacker == null` 表示来源可归因于玩家但无法定位具体是谁，此时按"无法安全判定来源默认拒绝"处理。
 */
object CombatDamagePolicy {

    fun decide(attacker: MatchMembership?, victim: MatchMembership?): DamageDecision {
        if (attacker == null && victim == null) return DamageDecision.UNRELATED
        // 只有一方在比赛里：参赛者不伤害局外人，局外人也别想插手比赛。
        if (attacker == null || victim == null) return DamageDecision.DENY
        if (attacker.matchId != victim.matchId) return DamageDecision.DENY
        // 同一场比赛：只有正式战斗阶段、双方都是本局存活选手才可能放行 PVP。
        if (attacker.phase != MatchPhase.RUNNING || victim.phase != MatchPhase.RUNNING) return DamageDecision.DENY
        if (attacker.spectator || victim.spectator) return DamageDecision.DENY
        if (!attacker.participant || !victim.participant) return DamageDecision.DENY
        if (attacker.state != ParticipantState.ALIVE || victim.state != ParticipantState.ALIVE) {
            return DamageDecision.DENY
        }
        // 工会战同队永不开友伤；决斗与大乱斗每人一队，此项自然不触发。
        if (attacker.teamId != null && attacker.teamId == victim.teamId && !attacker.friendlyFire) {
            return DamageDecision.DENY
        }
        return DamageDecision.ALLOW
    }
}
