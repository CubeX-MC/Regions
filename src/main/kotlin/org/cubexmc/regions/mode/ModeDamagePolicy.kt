package org.cubexmc.regions.mode

import org.cubexmc.regions.match.DamageDecision
import org.cubexmc.regions.match.MatchEffectPolicy
import java.util.UUID

/**
 * 竞速与捉迷藏的成员隔离。
 *
 * 在这之前这两类玩法**完全没有**隔离：竞速一局比赛能被任何路人用弓箭决定胜负，
 * 捉迷藏里躲藏者可以反过来把搜寻者打死（搜寻者→躲藏者那一下会被当成"抓到"取消，
 * 反方向却是实打实的伤害）。战斗层的隔离在 M3 做完了，这两类没跟上。
 *
 * 规则只有一条，不分方向也不分角色：
 *
 * > **只要有一方是某场竞速／捉迷藏的参赛者或观战者，玩家造成的伤害与负面状态一律拒绝。**
 *
 * 为什么不像战斗层那样"同局敌对才放行"——因为这两类玩法压根没有"敌对"这回事：
 * 竞速由谁先到终点决定，捉迷藏由抓到与否决定，两者都不靠打架。留一条 PVP 通道
 * 只会让一个带弓的人决定整场比赛。
 *
 * **搜寻者抓人是例外**，但它不走这里：那一下由 `RoundModeService.onDamage` 先行接管，
 * 取消伤害并记成"抓到"，根本不会落到这个判定上。
 *
 * 生物与环境伤害不经过这里（掉进岩浆仍然算数），自己给自己用的药也不拦。
 */
object ModeDamagePolicy {

    /**
     * 一名玩家与某场竞速／捉迷藏的关系。
     *
     * 不在任何一场里的人是 null；在场地内围观但没报名的人是 [spectator] = false、
     * [participant] = false 的局外人——对他同样双向拒绝。
     */
    data class Membership(
        val regionId: String,
        val matchId: UUID,
        val participant: Boolean,
        val spectator: Boolean,
    )

    fun decide(attacker: Membership?, victim: Membership?): DamageDecision {
        if (attacker == null && victim == null) return DamageDecision.UNRELATED
        // 任何一侧牵涉到一场进行中的竞速／捉迷藏,就不放行玩家来源的伤害。
        return DamageDecision.DENY
    }

    /**
     * 药水与滞留云。负面效果按伤害处理；有益／中性效果在**同一场**比赛内部放行
     * （给一起跑的人递瓶速度不是漏洞），跨场地与局外人一律拒绝。
     */
    fun decideEffect(
        thrower: Membership?,
        target: Membership?,
        kind: MatchEffectPolicy.EffectKind,
        selfInflicted: Boolean,
    ): DamageDecision {
        if (selfInflicted) return DamageDecision.ALLOW
        if (thrower == null && target == null) return DamageDecision.UNRELATED
        if (thrower == null || target == null) return DamageDecision.DENY
        if (thrower.matchId != target.matchId) return DamageDecision.DENY
        if (thrower.spectator || target.spectator) return DamageDecision.DENY
        if (!thrower.participant || !target.participant) return DamageDecision.DENY
        return when (kind) {
            MatchEffectPolicy.EffectKind.BENEFICIAL, MatchEffectPolicy.EffectKind.NEUTRAL -> DamageDecision.ALLOW
            MatchEffectPolicy.EffectKind.HARMFUL -> DamageDecision.DENY
        }
    }
}
