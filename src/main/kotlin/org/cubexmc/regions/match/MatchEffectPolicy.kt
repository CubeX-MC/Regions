package org.cubexmc.regions.match

/**
 * 药水／范围效果能不能落到某个人身上（PLAN.md §6.3.2 把"药水／范围效果"列入必须隔离的范围）。
 *
 * 伤害类药水（瞬间伤害）本来就走 [CombatDamagePolicy]——它会触发伤害事件。
 * **纯效果药水不会**：中毒、缓慢、虚弱、失明既不触发伤害事件，也就完全绕开了成员隔离，
 * 于是局外人可以隔着场地给选手上负面状态，选手也能给观战者上状态。这里补上这一层。
 *
 * 判定沿用同一套成员关系，只多一条区分：**有益效果不按敌对拦**——
 * 同一场比赛里给队友回血是正当战术，自己给自己喝药更是。
 */
object MatchEffectPolicy {

    enum class EffectKind {
        /** 中毒、虚弱、缓慢、失明这类。 */
        HARMFUL,

        /** 回血、抗性、速度这类。 */
        BENEFICIAL,

        /** 发光、饱和之类说不清好坏的，按有益处理：宁可放行，也不要把正常玩法拦掉。 */
        NEUTRAL,
    }

    /**
     * @param thrower 投掷者与比赛的关系；null = 无法归因到玩家（发射器、命令方块）
     * @param target 受影响者与比赛的关系；null = 这个人与任何比赛无关
     * @param selfInflicted 投掷者与受影响者是不是同一个人
     */
    fun decide(
        thrower: MatchMembership?,
        target: MatchMembership?,
        kind: EffectKind,
        selfInflicted: Boolean,
    ): DamageDecision {
        // 自己喝的药永远算数,哪怕是负面的(误伤自己是玩家自己的事)。
        if (selfInflicted) return DamageDecision.ALLOW
        if (thrower == null && target == null) return DamageDecision.UNRELATED

        // 一方在比赛里、另一方不在:双向都拒绝。局外人不能插手,选手也不能影响场外。
        if (thrower == null || target == null) return DamageDecision.DENY
        if (thrower.matchId != target.matchId) return DamageDecision.DENY

        // 同一场比赛:观战者既不能施加也不能承受。
        if (thrower.spectator || target.spectator) return DamageDecision.DENY
        if (!thrower.participant || !target.participant) return DamageDecision.DENY

        // 候场、准备、恢复阶段一律不许互相上状态——与阶段保护同一条理由。
        if (thrower.phase != MatchPhase.RUNNING || target.phase != MatchPhase.RUNNING) return DamageDecision.DENY
        if (thrower.state != ParticipantState.ALIVE || target.state != ParticipantState.ALIVE) {
            return DamageDecision.DENY
        }

        val sameTeam = thrower.teamId != null && thrower.teamId == target.teamId
        return when (kind) {
            // 有益/中性效果:同队与敌方都放行——给敌人加速是玩家自己的选择,不是漏洞。
            EffectKind.BENEFICIAL, EffectKind.NEUTRAL -> DamageDecision.ALLOW
            // 负面效果按友伤规则:工会战同队默认不许,决斗与大乱斗每人一队,自然放行。
            EffectKind.HARMFUL -> if (sameTeam && !thrower.friendlyFire) DamageDecision.DENY else DamageDecision.ALLOW
        }
    }
}
