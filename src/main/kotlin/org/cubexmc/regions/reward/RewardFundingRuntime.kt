package org.cubexmc.regions.reward

import org.cubexmc.regions.model.RegionDefinition
import java.util.UUID

interface RewardFundingRuntime : RewardFundingValidator {
    fun reserve(region: RegionDefinition): FundingResult

    fun settle(region: RegionDefinition, winnerCandidates: Set<UUID>): FundingResult

    /**
     * 带锁定证据的结算。工会战用它把"赢的是 Nation、收款人是开赛前锁定的签署方"传下去；
     * 默认实现退化为按原始成员集合结算，兼容没有 Nation 映射的玩法与历史 lease。
     */
    fun settle(region: RegionDefinition, evidence: FundingSettlement): FundingResult =
        settle(region, evidence.winnerKeys)

    fun refund(region: RegionDefinition, reason: String): FundingResult

    fun reconcile(): List<FundingResult>
}
