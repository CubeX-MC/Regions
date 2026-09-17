package org.cubexmc.regions.reward

import java.util.UUID

data class FundingResult(
    val successful: Boolean,
    val code: String,
    val detail: String = "",
    val contractId: String? = null,
    val partyA: UUID? = null,
    val partyB: UUID? = null,
) {
    companion object {
        fun ok(contractId: String? = null, partyA: UUID? = null, partyB: UUID? = null): FundingResult =
            FundingResult(true, "OK", contractId = contractId, partyA = partyA, partyB = partyB)

        fun fail(code: String, detail: String = ""): FundingResult = FundingResult(false, code, detail)
    }
}

/**
 * 结算证据（PLAN.md §7.2「奖励兼容」）。
 *
 * 工会战的赢家是 Nation，收款人仍是该 Nation 对应的合同签署人：开赛前把
 * `Nation ID → 合同签署方 UUID` 与完整比赛证据一起锁定，赛后成员退国、改名或换届都不能改变收款人。
 * [winnerKeys] 保留原始比赛成员集合，供 provider 不可用时进入待复核。
 */
data class FundingSettlement(
    val winnerKeys: Set<UUID>,
    /** 获胜的 Nation 稳定 ID；非 Nation 战为 null。 */
    val winnerUnit: String? = null,
    /** 开赛前锁定的 Nation ID → 合同签署方；空表示走历史实时解析。 */
    val unitParties: Map<String, UUID> = emptyMap(),
)
